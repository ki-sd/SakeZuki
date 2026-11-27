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
