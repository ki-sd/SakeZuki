"""Disposable MySQL/HTTP tests. Does not read .env or touch the application DB volume."""
import argparse
import base64
import os
from pathlib import Path
import secrets
import re
import subprocess
import sys
import time
import urllib.request
import urllib.error
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def verify_test_reports(module, task, exit_code):
    reports = list((ROOT / module / "build/test-results" / task).glob("TEST-*.xml"))
    suites = [ET.parse(report).getroot() for report in reports]
    counts = {key: sum(int(suite.attrib[key]) for suite in suites)
              for key in ("tests", "failures", "errors", "skipped")}
    print(f"{module}/{task}: {counts}", flush=True)
    if exit_code == 0 and (not counts["tests"] or any(counts[k] for k in ("failures", "errors", "skipped"))):
        raise RuntimeError(f"Required tests missing or unsuccessful: {module}/{task}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--keep-database", action="store_true", help="Keep only the isolated test container for further testing")
    parser.add_argument("--e2e", action="store_true", help="Also start isolated backend/frontend and run Chromium tests")
    args = parser.parse_args()
    env = os.environ.copy()
    env.update(AUTH_TEST_DB_PASSWORD=secrets.token_urlsafe(32), AUTH_TEST_DB_USER="auth_it",
               AUTH_TEST_JDBC_URL="jdbc:mysql://127.0.0.1:3308/sakezuki_auth_it?serverTimezone=UTC&characterEncoding=UTF-8")
    env.update(AUTH_TEST_LOGIN_EMAIL=secrets.token_hex(12) + "@example.invalid", AUTH_TEST_LOGIN_PASSWORD=secrets.token_urlsafe(24),
               AUTH_TEST_BASE_URL="http://localhost:3001", BACKEND_ORIGIN="http://127.0.0.1:8081", AUTH_TEST_DIST_DIR=".next-auth-e2e")
    env.update(AUTH_TEST_SECOND_EMAIL=secrets.token_hex(12) + "@example.invalid", AUTH_TEST_SECOND_PASSWORD=secrets.token_urlsafe(24))
    # 일반 기동 테스트도 같은 분리 DB만 사용한다. 기존 .env를 테스트 설정으로 가져오지 않는다.
    env.update(SPRING_CONFIG_IMPORT="", MYSQL_HOST="127.0.0.1", MYSQL_PORT="3308", MYSQL_DATABASE="sakezuki_auth_it",
               MYSQL_USER=env["AUTH_TEST_DB_USER"], MYSQL_PASSWORD=env["AUTH_TEST_DB_PASSWORD"], JWT_SECRET=base64.b64encode(secrets.token_bytes(32)).decode("ascii"),
               GOOGLE_CLIENT_ID="isolated-test", GOOGLE_CLIENT_SECRET="isolated-test", GEMINI_API_KEY="isolated-test")
    compose = ["docker", "compose", "-p", "sakezuki-auth-it", "-f", str(ROOT / "compose.auth-test.yaml")]
    children = []
    logs = []
    tsconfig = ROOT / "frontend/tsconfig.json"
    tsconfig_before = tsconfig.read_bytes()
    tsconfig_generated = None
    try:
        subprocess.run(compose + ["up", "-d", "--wait"], env=env, cwd=ROOT, check=True)
        # Flyway upgrade/repair tests use a separate disposable schema in the same container.
        subprocess.run(compose + ["exec", "-T", "mysql-auth-test", "sh", "-c",
                                  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -u root'], env=env, cwd=ROOT,
                       input="CREATE DATABASE IF NOT EXISTS sakezuki_catalog_it;\n"
                             "GRANT ALL ON sakezuki_catalog_it.* TO 'auth_it'@'%';\n",
                       text=True, check=True)
        # 저장소 wrapper의 실행 비트와 관계없이 Linux에서도 동일한 검증을 실행한다.
        wrapper = [str(ROOT / "backend" / "gradlew.bat")] if os.name == "nt" else ["bash", str(ROOT / "backend" / "gradlew")]
        gradle = wrapper + ["cleanTest", "cleanAuthIntegrationTest", "test", "authIntegrationTest", "--no-daemon"]
        result = subprocess.run(gradle, env=env, cwd=ROOT / "backend")
        for task in ("test", "authIntegrationTest"):
            verify_test_reports("backend", task, result.returncode)
        if result.returncode:
            return result.returncode
        tools_dir = ROOT / "tools"
        tools_wrapper = ([str(tools_dir / "gradlew.bat")] if os.name == "nt"
                         else ["bash", str(tools_dir / "gradlew")])
        tools_result = subprocess.run(tools_wrapper + ["--no-daemon", "cleanCatalogIntegrationTest",
                                                      "catalogIntegrationTest"], env=env, cwd=tools_dir)
        verify_test_reports("tools", "catalogIntegrationTest", tools_result.returncode)
        if tools_result.returncode:
            return tools_result.returncode
        if not args.e2e:
            return 0
        subprocess.run(wrapper + ["authE2eClasspath", "--no-daemon"], env=env, cwd=ROOT / "backend", check=True)
        classpath = (ROOT / "backend/build/auth-e2e-classpath.txt").read_text().replace("\\", "/")
        java_args = ROOT / "backend/build/auth-e2e.args"
        java_args.write_text('-cp\n"' + classpath + '"\ncom.sakezuki.backend.auth.AuthE2eServer\n', encoding="utf-8")
        def start(command, cwd, log_name):
            log = open(ROOT / "backend/build" / log_name, "w", encoding="utf-8")
            logs.append(log)
            process = subprocess.Popen(command, cwd=cwd, env=env, stdout=log, stderr=subprocess.STDOUT,
                                       creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
            children.append(process)
            return process
        backend = start(["java", "@" + str(java_args)], ROOT / "backend", "auth-e2e-backend.log")
        wait_http("http://127.0.0.1:8081/api/sake/list", backend)
        # The fixture insertion occurs immediately after the HTTP server starts.
        for _ in range(100):
            backend_log = (ROOT / "backend/build/auth-e2e-backend.log").read_text(encoding="utf-8", errors="replace")
            food = re.search(r"AUTH_E2E_FOOD=(.+)", backend_log)
            if "AUTH_E2E_READY" in backend_log and food:
                # 합성 캐시 조건을 전달해 크레딧 E2E가 환경 누락으로 skip되지 않게 한다.
                env["AUTH_TEST_FOOD"] = food.group(1).strip()
                break
            time.sleep(0.1)
        else:
            raise RuntimeError("E2E fixture initialization failed; inspect the ignored backend/build log")
        npm = "npm.cmd" if os.name == "nt" else "npm"
        frontend = start([npm, "run", "dev", "--", "--port", "3001"], ROOT / "frontend", "auth-e2e-frontend.log")
        wait_http("http://localhost:3001/login", frontend)
        tsconfig_generated = tsconfig.read_bytes()
        result = subprocess.run([npm, "run", "test:e2e"], cwd=ROOT / "frontend", env=env)
        if result.returncode:
            return result.returncode
        # The second fixture only receives a fresh login. A stale write must not affect its rows or quota.
        counts = subprocess.run(compose + ["exec", "-T", "mysql-auth-test", "sh", "-c",
                                'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -u root -N -B sakezuki_auth_it'],
                                env=env, cwd=ROOT, capture_output=True, text=True, check=True, input=f"""
            SELECT (SELECT COUNT(*) FROM CATALOG_SUBMISSION WHERE owner_no=m.no),
                   (SELECT COUNT(*) FROM SUBMISSION_ATTACHMENT WHERE owner_no=m.no),
                   (SELECT COALESCE(SUM(reserved_bytes),0) FROM SUBMISSION_ATTACHMENT WHERE owner_no=m.no),
                   (SELECT balance FROM CREDIT_ACCOUNT WHERE member_no=m.no)
            FROM MEMBER m WHERE m.email='{env["AUTH_TEST_SECOND_EMAIL"]}';
            """)
        if counts.stdout.strip().split() != ["0","0","0","10"]:
            raise RuntimeError("Cross-session fixture data changed")
        print("Cross-session fixture: submissions=0, attachments=0, reserved_bytes=0, unchanged_credit=10", flush=True)
        return 0
    finally:
        for child in reversed(children):
            if child.poll() is None:
                if os.name == "nt":
                    subprocess.run(["taskkill", "/PID", str(child.pid), "/T", "/F"], capture_output=True)
                else:
                    child.terminate()
                child.wait(timeout=15)
        for log in logs:
            log.close()
        # Next may add generated type paths. Restore only its known edit, never concurrent user edits.
        if tsconfig_generated is not None and tsconfig.read_bytes() == tsconfig_generated:
            tsconfig.write_bytes(tsconfig_before)
        if not args.keep_database:
            # This project has a tmpfs DB and no application data volumes.
            subprocess.run(compose + ["down"], env=env, cwd=ROOT, check=False)


def wait_http(url, process):
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError("Test server stopped; inspect the ignored backend/build test logs")
        try:
            if urllib.request.urlopen(url, timeout=2).status == 200:
                return
        except (urllib.error.URLError, OSError):
            pass
        time.sleep(0.3)
    raise RuntimeError("Timed out waiting for the isolated test server")


if __name__ == "__main__":
    sys.exit(main())
