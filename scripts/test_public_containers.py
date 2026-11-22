"""Build and verify disposable Linux containers; never connects to the application database."""
import base64
import http.cookiejar
import json
import os
from pathlib import Path
import secrets
import socket
import ssl
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def free_port():
    with socket.socket() as server:
        server.bind(("127.0.0.1", 0))
        return server.getsockname()[1]


def main():
    # 고정 운영 container_name·포트·프로젝트·볼륨을 재사용하지 않는다.
    project = "sakezuki-public-it-" + secrets.token_hex(5)
    http_port, tls_port = free_port(), free_port()
    while tls_port == http_port:
        tls_port = free_port()
    with tempfile.TemporaryDirectory(prefix="sakezuki-container-it-") as temporary:
        temp = Path(temporary)
        env_file = temp / "test.env"
        settings = dict(MYSQL_DATABASE="sakezuki", MYSQL_USER="test_user", MYSQL_PASSWORD=secrets.token_urlsafe(32),
                        MYSQL_ROOT_PASSWORD=secrets.token_urlsafe(32), GEMINI_API_KEY="not-a-real-key",
                        GOOGLE_CLIENT_ID="test-client", GOOGLE_CLIENT_SECRET="not-a-real-secret",
                        JWT_SECRET=base64.b64encode(secrets.token_bytes(32)).decode("ascii"),
                        NEXT_PUBLIC_GOOGLE_MAPS_API_KEY="", PUBLIC_HOST="localhost", TLS_CERT_DIR=str(temp))
        env_file.write_text("\n".join(f"{key}={value}" for key, value in settings.items()) + "\n", encoding="utf-8")
        os.chmod(env_file, 0o600)
        compose = ["docker", "compose", "-p", project, "--env-file", str(env_file), "-f", str(ROOT / "docker-compose.yaml")]

        def override(https):
            origin = f"{'https' if https else 'http'}://localhost:{tls_port if https else http_port}"
            # env_file 재설정으로 개발자 .env를 읽지 않고 임시 값만 내부 backend에 전달한다.
            backend_env = {**settings, "MYSQL_HOST": "mysql", "MYSQL_PORT": "3306", "SPRING_CONFIG_IMPORT": "",
                           "AUTH_FRONTEND_URL": origin, "GOOGLE_REDIRECT_URI": origin + "/login/oauth2/code/google",
                           "AUTH_SECURE_COOKIES": str(https).lower()}
            text = "services:\n"
            for service in ["mysql", "backend", "frontend", "nginx"]:
                text += f"  {service}:\n    container_name: {project}-{service}\n"
                if service == "mysql":
                    text += "    ports: !reset []\n    volumes: !reset []\n    tmpfs:\n      - /var/lib/mysql\n"
                elif service == "backend":
                    text += "    env_file: !reset []\n    environment: " + json.dumps(backend_env) + "\n"
                elif service == "nginx":
                    ports = [f"127.0.0.1:{http_port}:80"] + ([f"127.0.0.1:{tls_port}:443"] if https else [])
                    text += "    ports: !override " + json.dumps(ports) + "\n"
            target = temp / ("https-test.yaml" if https else "http-test.yaml")
            target.write_text(text, encoding="utf-8")
            return ["-f", str(target)]

        plain = compose + override(False)
        secure = compose + ["-f", str(ROOT / "compose.https.yaml")] + override(True)
        def run(command):
            subprocess.run(command, cwd=ROOT, check=True)

        def fetch(opener, path, expected=200, data=None):
            request = urllib.request.Request(path, data=data, headers={"Content-Type": "application/json"} if data is not None else {})
            try:
                response = opener.open(request, timeout=10)
            except urllib.error.HTTPError as error:
                response = error
            assert response.status == expected, f"Unexpected HTTP status for {urllib.parse.urlsplit(path).path}: {response.status}"
            body = response.read()
            return response.headers, body

        def ready(opener, origin):
            deadline = time.monotonic() + 150
            while time.monotonic() < deadline:
                try:
                    _, body = fetch(opener, origin + "/api/health/ready")
                    if json.loads(body)["status"] == "UP":
                        return
                except (AssertionError, OSError, urllib.error.URLError):
                    pass
                time.sleep(1)
            raise RuntimeError("Isolated container readiness timed out")

        try:
            run(plain + ["config", "--quiet"])
            run(plain + ["build", "backend", "frontend"])
            run(plain + ["up", "-d"])
            http_origin = f"http://localhost:{http_port}"
            plain_client = urllib.request.build_opener(NoRedirect())
            ready(plain_client, http_origin)
            _, landing = fetch(plain_client, http_origin + "/")
            assert "오늘의 음식에서".encode() in landing
            _, examples = fetch(plain_client, http_origin + "/api/examples/recommendations")
            assert len(json.loads(examples)) == 4
            fetch(plain_client, http_origin + "/api/user/account", 401)
            fetch(plain_client, http_origin + "/api/auth/login", 403, b"{}")
            print("Linux HTTP containers: landing, public examples, readiness, 401/403 PASS", flush=True)
            # 셀프서명 인증서는 이 테스트의 trust store에만 넣는다. 운영 인증서 검증을 비활성화하지 않는다.
            subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", str(temp / "privkey.pem"),
                            "-out", str(temp / "fullchain.pem"), "-days", "1", "-subj", "/CN=localhost", "-addext", "subjectAltName=DNS:localhost"], check=True, capture_output=True)
            run(secure + ["config", "--quiet"])
            run(secure + ["up", "-d"])
            tls_context = ssl.create_default_context(cafile=str(temp / "fullchain.pem"))
            jar = http.cookiejar.CookieJar()
            client = urllib.request.build_opener(NoRedirect(), urllib.request.HTTPSHandler(context=tls_context), urllib.request.HTTPCookieProcessor(jar))
            origin = f"https://localhost:{tls_port}"
            ready(client, origin)
            headers, _ = fetch(client, origin + "/api/auth/csrf")
            assert "Secure" in headers.get("Set-Cookie", "") and "HttpOnly" in headers.get("Set-Cookie", "")
            headers, _ = fetch(client, origin + "/oauth2/authorization/google", 302)
            query = urllib.parse.parse_qs(urllib.parse.urlsplit(headers["Location"]).query)
            assert query["redirect_uri"] == [origin + "/login/oauth2/code/google"]
            headers, _ = fetch(client, origin + "/login/oauth2/code/google?error=access_denied&state=invalid", 302)
            assert headers["Location"] == origin + "/login?error=oauth"
            fetch(client, origin + "/api/examples/recommendations")
            headers, _ = fetch(plain_client, http_origin + "/api/health/ready", 308)
            assert headers["Location"].startswith("https://localhost/")
            print("Linux HTTPS containers: trusted TLS, Secure cookie, OAuth URI/failure, examples, redirect PASS", flush=True)
        finally:
            # 새 tmpfs DB만 포함한 고유 프로젝트를 중지한다. 기존 볼륨을 삭제하는 명령은 사용하지 않는다.
            subprocess.run(secure + ["down", "--remove-orphans"], cwd=ROOT, check=False)


if __name__ == "__main__":
    main()
