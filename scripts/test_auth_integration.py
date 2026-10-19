"""Disposable MySQL/HTTP tests. Does not read .env or touch the application DB volume."""
import argparse
import os
from pathlib import Path
import secrets
import subprocess
import sys
import time
import urllib.request
import urllib.error

ROOT = Path(__file__).resolve().parents[1]


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
    compose = ["docker", "compose", "-p", "sakezuki-auth-it", "-f", str(ROOT / "compose.auth-test.yaml")]
    children = []
    logs = []
    tsconfig = ROOT / "frontend/tsconfig.json"
    tsconfig_before = tsconfig.read_bytes()
    tsconfig_generated = None
    try:
        subprocess.run(compose + ["up", "-d", "--wait"], env=env, cwd=ROOT, check=True)
        gradle = [str(ROOT / "backend" / ("gradlew.bat" if os.name == "nt" else "gradlew")), "authIntegrationTest", "--no-daemon"]
        result = subprocess.run(gradle, env=env, cwd=ROOT / "backend")
        if result.returncode or not args.e2e:
            return result.returncode
        subprocess.run([gradle[0], "authE2eClasspath", "--no-daemon"], env=env, cwd=ROOT / "backend", check=True)
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
            if "AUTH_E2E_READY" in (ROOT / "backend/build/auth-e2e-backend.log").read_text(encoding="utf-8", errors="replace"):
                break
            time.sleep(0.1)
        else:
            raise RuntimeError("E2E fixture initialization failed; inspect the ignored backend/build log")
        npm = "npm.cmd" if os.name == "nt" else "npm"
        frontend = start([npm, "run", "dev", "--", "--port", "3001"], ROOT / "frontend", "auth-e2e-frontend.log")
        wait_http("http://localhost:3001/login", frontend)
        tsconfig_generated = tsconfig.read_bytes()
        return subprocess.run([npm, "run", "test:e2e"], cwd=ROOT / "frontend", env=env).returncode
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
