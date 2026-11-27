# Codex Cloud와 Windows 개발 환경

[공식 Cloud 환경 안내](https://learn.chatgpt.com/docs/environments/cloud-environments)에서 Work in → Cloud → Create environment로 들어가 GitHub의 ki-sd/SakeZuki를 선택한다. Java 21, Node 24 및 npm, Python 3을 기준으로 하고 Chromium 시스템 의존성, Docker Compose와 OpenSSL은 필요한 검증 범위에 따라 준비한다. 준비 보고서에서 실제 설치·실행 결과를 확인한 후 Publish한다. 새 작업은 게시한 환경으로 시작하고 [HANDOFF](HANDOFF.md)를 읽는다. 로컬 및 다른 작업의 미커밋 작업은 새 환경에 따라오지 않는다.

저장소를 루트로 설치 단계에서 다음을 실행할 수 있다. 의존성 설치, Gradle wrapper 다운로드, Next 빌드의 원격 폰트, Docker 이미지·Chromium 설치에 필요한 네트워크 접근만 사용한다. 운영 시크릿은 환경에 등록할 필요가 없다.

```bash
java -version
node --version
python3 --version
(cd frontend && npm ci)
(cd backend && bash ./gradlew testClasses)
```

일회성 환경으로 시작했다면 실제로 설치가 끝났는지 확인한다. Docker 클라이언트가 있어도 데몬 접근은 `docker info`로 확인한다. 데몬·네트워크 제한이 있으면 가능한 검사만 실행하고 GitHub Actions의 격리 검증에 나머지를 맡긴다. Cloud 환경 게시와 첫 기준선은 별도 단계로 확인한다.

| 범위 | Bash | Windows PowerShell |
| --- | --- | --- |
| backend 단위 | backend에서 `bash ./gradlew test` | backend에서 `.\gradlew.bat test` |
| backend 개발 서버 | backend에서 `bash ./gradlew bootRun` | backend에서 `.\gradlew.bat bootRun` |
| tools 검사 | tools에서 `bash ./gradlew test` | tools에서 `.\gradlew.bat test` |
| frontend | frontend에서 `npm ci`, `npm run dev`, `npm run test:auth`, `npx tsc --noEmit`, `npm run lint`, `npm run build` | 동일 |
| 격리 DB·브라우저 | frontend에서 `npx playwright install --with-deps chromium`, 루트에서 `python3 scripts/test_auth_integration.py --e2e` | frontend에서 `npx playwright install chromium`, 루트에서 `py -3 scripts/test_auth_integration.py --e2e` |
| Linux 컨테이너 | 루트에서 `python3 scripts/test_public_containers.py` | Linux CI 또는 승인된 분리 Linux 환경 |

개발 서버 수동 기동은 [README](../../README.md)의 로컬 설정과 [.env.example](../../.env.example)을 참조하고 분리 DB와 개발용 값만 넣는다. 통합 실행기는 별도 MySQL(3308)과 E2E 서버(8081/3001), 합성 캐시 및 테스트 자격증명을 사용한다. Docker·포트가 준비되지 않으면 E2E를 통과로 집계하지 않는다. 데이터 수집·번역 실행과 운영 OCI 접근은 별도 작업이다.

Claude Code는 [공식 import](https://code.claude.com/docs/en/memory#import-additional-files), Gemini CLI는 [공식 GEMINI.md import](https://geminicli.com/docs/cli/gemini-md/)로 공통 지침을 읽는다. 다른 에이전트에는 AGENTS.md와 HANDOFF.md 경로를 직접 전달한다.

## DEV-01 Cloud 기준선

2026-11-27 KST. 최신 원격 `main` 및 Cloud 검증 대상은 `35029601ec34dc1f89768764bf5ec7fe14def01d`다. 종합 판정은 **PARTIALLY READY**다. 게시된 환경에서 일상 개발과 격리 인증 검증이 가능하다. 공개 컨테이너 HTTP/HTTPS는 GitHub Actions를 사용한다.

게시된 환경의 새 작업에서 기존 체크아웃과 캐시를 재사용한다. 각 셸에서 `source /workspace/sakezuki-dev/activate.sh`를 실행하면 `CI=true`, `TZ=Asia/Seoul`, Temurin `JAVA_HOME`, Java Cloud 프록시 및 시스템 신뢰 저장소가 적용된다. Gradle Wrapper·의존성, npm 및 Playwright 캐시는 `/workspace/sakezuki-dev` 아래에 둔다. lockfile이 바뀌면 `npm ci`를 실행하고 Playwright 버전이 바뀌었거나 관리 브라우저가 없으면 frontend에서 `npx playwright install chromium`을 실행한다. 관리 Chromium이 정상 실행되는 환경에서는 `--with-deps`를 반복할 필요가 없다.

- 복원 확인은 Debian 13.7 x86_64, Temurin 21.0.13+9, Node.js 24.22.3, npm 11.19.1, Python 3.12.14, Docker CLI·데몬 29.9.2, Compose v2.40.3 및 MySQL 8.4 계열 이미지를 대상으로 했다. 시작 전 격리 포트 3308·8081·3001이 비어 있었다.
- 게시된 허용 정책에 `fonts.googleapis.com`, `fonts.gstatic.com`, `cdn.playwright.dev`, `storage.googleapis.com`, `playwright.download.prss.microsoft.com`이 적용됐다. 기존 Geist·Geist Mono 구성으로 Next.js 프로덕션 빌드가 통과했다. Playwright 1.64.1 관리 Chromium 156.0.8078.4, headless shell 및 FFmpeg를 설치했고 기본 headless 실행과 DOM 검사를 통과했다. 기존 Linux 라이브러리를 사용했다.

| 실행 범위 | Cloud 결과 |
| --- | --- |
| Backend Gradle Wrapper 9.7.1 | `bash ./gradlew --no-daemon bootJar` 통과. DB 기동 없이 실행한 `test`는 28개 중 `contextLoads` 1개 실패. Flyway가 DB에 연결할 수 없었으며 격리 실행기로 같은 테스트를 재검증했다. |
| Tools Gradle Wrapper 9.3.0 | `bash ./gradlew --no-daemon test` 성공, `NO-SOURCE`로 테스트 0개. |
| Frontend | `npm ci`, `npm run test:auth` 16개, `npx tsc --noEmit`, `npm run lint` 오류 0·경고 4, `npm run build`, `npm audit --audit-level=low` 취약점 0 통과. |
| `python3 scripts/test_auth_integration.py --e2e` | 격리 MySQL에서 Backend 기본 28개, MySQL 통합 24개, 관리 Chromium E2E 9개 통과. |
| `python3 scripts/test_public_containers.py` | Cloud Docker의 Backend 이미지 빌드에서 Gradle Wrapper가 `services.gradle.org`를 해석하지 못했다. 공개 HTTP/HTTPS 응답 검증에 도달하지 못했다. |

GitHub Actions `verify`는 PR #5 head `e52938a72a34ebfc500074346c9f5e22694b477e`에 연결된 실행에서 통과했다. PR head와 검증 대상 main의 Git tree가 동일함을 확인했다. CI의 프론트 정적 검사·빌드, 격리 MySQL·Chromium, Linux Docker HTTP/HTTPS 단계가 성공했다. main SHA 자체의 별도 실행은 확인되지 않았다. Cloud Docker에서 공개 컨테이너 빌드가 막히면 같은 소스의 GitHub Actions 실행을 확인한다. [CI 실행](https://github.com/ki-sd/SakeZuki/actions/runs/45968711820/job/140403581647)
