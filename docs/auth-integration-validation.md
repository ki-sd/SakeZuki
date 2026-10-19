# 인증 실환경 통합 검증 결과

검증일: 2026-10-20 (Asia/Seoul). 커밋·푸시는 하지 않았다.

## 기준과 데이터 보존

- GitHub `ki-sd/SakeZuki`의 main과 로컬 HEAD가 `d67a3b21ab12143d5ac2b3a08827e86b5b455188`로 일치했다. 시작 시 로컬 작업 트리는 깨끗했고 이전 인증 구현이 커밋에 포함되어 있었다.
- 기존 Docker MySQL 8.4 컨테이너는 이미 실행 중이었다. 데이터 볼륨을 삭제하거나 초기화하지 않았다.
- 기존 DB는 V3까지 적용된 상태였다. 로컬 최신 백엔드 기동으로 Flyway 검증 후 V4가 정상 적용됐다. V1~V4 파일은 모두 변경하지 않았다. 새 마이그레이션도 없다.
- 검증 전 기존 MEMBER/SOCIAL_ACCOUNT는 각각 0행이었다. 사용자가 실제 Google 가입을 완료한 뒤 각각 1행이고, 재로그인 후에도 1행이다. 사케 데이터는 10,337행을 유지했다.
- 자동 테스트의 가상 이메일·비밀번호·회원·추천 데이터는 별도 MySQL `sakezuki_auth_it`에만 생성했다. 포트는 3308이고 tmpfs를 사용한다. 기존 앱의 3307 DB 및 볼륨과 연결하지 않는다. 기본 실행 완료 시 해당 테스트 컨테이너와 네트워크만 제거한다.
- `.env`를 수정하거나 값들을 출력하지 않았다. 테스트 DB 암호, 테스트 로그인 암호, E2E JWT 키는 매 실행 무작위로 생성하고 환경변수/메모리로 전달한다. 추적 파일에 저장하지 않는다.

## 발견 사항과 수정

1. 실행 중인 Docker 이미지가 로컬 소스보다 오래되어 `/login`이 404이고 Flyway도 V3까지만 포함되어 있었다. 현재 코드로 이미지를 다시 빌드했다. 소스의 인증 기능을 중복 구현하지 않았다.
2. 실제 Google 요청이 `redirect_uri_mismatch`로 거절됐다. 사용자가 Google Cloud에 `http://localhost:3000/login/oauth2/code/google`를 등록한 후 로그인 화면 진입과 실제 가입·재로그인을 확인했다. Google Cloud 설정을 대신 변경하지 않았다.
3. Chromium에서 프록시를 통한 로그인 POST가 `Invalid CORS request`로 거절되는 문제를 발견했다. WebConfig의 고정 허용 출처 `localhost:3000`을 기존 `auth.frontend-url` 설정값으로 교체했다. 와일드카드나 임의의 출처를 허용하지 않는다. 설정된 출처 허용·다른 출처 거절을 통합 테스트로 검증했다.
4. 사용자 추가 요청에 따라 전화번호 입력에서 숫자 이외 문자를 제거하고 11자리까지 3-4-4 형식(`010-1234-5678`)으로 자동 표시한다. 모바일 숫자 키패드와 완성된 형식의 브라우저 유효성 검사를 적용했다. 기존 백엔드의 하이픈 제거·전화번호 저장 로직을 재사용한다. 주소 입력은 유지했고 다음/네이버 주소 API를 추가하지 않았다.
5. 테스트 전용 Next 서버를 기존 개발 서버와 동시에 실행하도록 `BACKEND_ORIGIN`과 `AUTH_TEST_DIST_DIR` 설정을 추가했다. 기본 개발 동작은 기존 8080 프록시와 `.next`를 유지한다. 테스트 생성물은 Git·ESLint·Docker 컨텍스트에서 제외했다. 테스트 실행 중 Next가 자동 변경한 tsconfig는 동시 사용자 변경이 없는 경우에만 복원한다.

추천·사케 서비스의 비즈니스 로직, 기존 인증 토큰 정책, 회원 테이블 구조는 변경하지 않았다. 새 운영 API 엔드포인트도 없다.

## 실행 결과

| 검증 | 결과 |
|---|---|
| 기존 MySQL Flyway V4 | 성공. history의 1~4 모두 success=1, 실제 인증 테이블 생성 |
| 전체 백엔드 `gradlew.bat test --no-daemon` | 20개 통과. 기존 애플리케이션 기동 테스트 포함 |
| 별도 MySQL `authIntegrationTest` | 11개 통과, 실패·skip 없음 |
| Chromium E2E | 5개 통과, 실패·skip 없음 |
| 프론트 인증 모듈 단위 테스트 | 7개 통과 |
| 프론트 타입 검사 | 통과 |
| ESLint | 오류 0, 기존 이미지·미사용 import 경고 5개 |
| 프론트 프로덕션 빌드 | 통과 |
| Docker 백엔드·프론트 이미지 빌드 | 통과 |
| 기존 마이그레이션·추천 로직 diff | 변경 없음 |

### MySQL 통합 테스트 11개

- 실제 Flyway V4 이력과 테이블 조회
- 실제 BCrypt 이메일 로그인, 오입력 401, CSRF 누락 403, HttpOnly 쿠키 및 사용자 조회
- Refresh Token 해시 저장, 회전, 재사용 시 계열 폐기, 로그아웃 후 재사용 차단
- 만료된 Refresh 계열 거절
- MySQL REPEATABLE READ에서 동시 회전: 한 요청 성공, 한 요청 재사용 거절, 성공한 후속 토큰도 폐기
- 실제 Mapper를 사용하는 소셜 가입·기존 소셜 회원 로그인·중복 이메일/소셜 계정 거절
- 전화번호 유일 제약 충돌 시 가입 데이터 롤백
- 동시 소셜 가입 시 회원·소셜 계정·권한·등급·토큰 계열 각 1개만 생성
- 서명된 만료 Access Token의 실제 HTTP 401, 일반 회원 관리자 경로 403, 관리자 역할의 사용자 조회
- 공개 목록·상세·추천 입력 검증, 구성된 CORS 출처 허용과 타 출처 차단
- 실제 DB의 양방향 추천 캐시 3개 조회. 외부 AI 호출 없이 기존 서비스와 Mapper를 검증

위 목록은 시나리오를 설명한 것으로 테스트 메서드 수는 11개다. 소셜 서비스 테스트의 Google 식별자는 합성 데이터이며 실제 Google 인증 통과와 구분한다.

### Chromium E2E 5개

- Next 프록시를 통하는 공개 API, CSRF 403·미인증 401
- Google 시작 요청의 302·state·콜백 URI·세션 쿠키와 잘못된 state/취소 콜백의 복귀 경로
- 소셜 임시 세션 누락 시 다시 로그인 안내 및 보호 화면의 로그인 이동
- 전화번호의 숫자 필터·붙여넣기·3-4-4 자동 하이픈·숫자 키패드 속성. 이 입력폼 테스트 하나는 가입정보 GET 응답만 stub 처리하며 실제 가입을 수행하지 않는다.
- 실제 이메일 로그인 오입력/성공, 원래 페이지 복귀, 새로고침 후 상태 복원, Refresh 쿠키 회전, 일반 회원 403, 로그아웃 후 재발급 거절. 테스트 전용 Access Token은 2초이고 JWT 기본 시간 허용 오차 60초를 포함해 실제 만료될 때까지 기다렸다. 만료 후 사케 화면 진입 시 브라우저가 Refresh를 정확히 한 번 수행함을 검증했다.

E2E 테스트는 테스트 전용 서버 8081·Next 3001·MySQL 3308을 사용한다. 실제 Google 로그인을 브라우저 자동화 테스트의 성공으로 대체하지 않는다. 인증 토큰과 쿠키가 trace에 저장되지 않도록 Playwright trace는 비활성화했다. 테스트 결과·스크린샷은 Git과 Docker 이미지에서 제외한다.

## 실제 Google 및 기존 데이터 회귀 확인

- 사용자가 직접 Google 로그인 및 추가정보 입력을 완료한 후 `/sake`의 로그인된 헤더를 확인했다.
- DB 집계에서 회원 1명, GOOGLE 계정 1개, NULL 비밀번호, ROLE_USER 1개를 확인했다. 개인 이름·이메일·전화번호·provider sub는 출력하지 않았다.
- 페이지 새로고침 후 로그인 상태가 복원되고 이전 Refresh Token의 used 기록이 증가했다.
- 로그아웃 후 로그인 링크가 표시됐고, 기존 토큰 계열은 revoked 상태였다.
- 같은 Google 계정으로 재로그인 시 `/oauth/callback`을 거쳐 `/sake`로 돌아왔다. 가입 화면이 다시 표시되지 않았고 회원·소셜 계정은 추가 생성되지 않았다.
- 로그인 상태에서 기존 ‘스키야키’ 추천 3개가 정상 표시됐다. 기존 추천 캐시를 사용하므로 새 Gemini 호출·새 추천 저장을 하지 않았다.
- `http://localhost:3000`의 Next 개발 프록시와 `http://localhost`의 Nginx 양쪽에서 로그인 화면·CSRF API·사케 목록 200, 미인증 사용자 API 401을 확인했다.

실제 Google의 인증·신규 가입·기존 회원 흐름은 **3000 개발 주소**에서 확인했다. Nginx 주소의 OAuth 전체 왕복은 별도로 확인하지 않았다. 배포 도메인에서는 AUTH_FRONTEND_URL·GOOGLE_REDIRECT_URI와 Google Cloud 승인 URI를 같은 공개 출처로 맞춰야 한다. 3000과 Docker 백엔드는 세션 저장소가 다르므로 인증 시작은 Docker, 콜백은 로컬 서버로 섞어 보내면 안 된다.

## 재실행

Docker 실행과 Java 21·Node·Python이 준비된 저장소 루트에서 실행한다.

```powershell
# 처음 한 번: 프론트 의존성 설치 후 Chromium 설치
cd frontend
npm ci
npx playwright install chromium
cd ..

# 분리된 MySQL 통합 테스트
python scripts/test_auth_integration.py

# MySQL 통합 테스트 + 별도 서버 + Chromium E2E + 임시 환경 정리
python scripts/test_auth_integration.py --e2e
```

스크립트가 사용하는 테스트 포트는 3308·8081·3001이다. 이미 사용 중이면 임의로 기존 프로세스를 종료하지 말고 충돌을 해결한 뒤 실행한다. `authIntegrationTest`는 올바른 분리 DB 환경변수가 없으면 실패하도록 하고, DB 상태가 바뀌므로 Gradle의 up-to-date 캐시로 실행을 생략하지 않는다. 테스트 전용 초기화 서버 클래스는 test 소스에만 있으며 운영 JAR에는 포함되지 않는다.

## Google 로그인 표시 이름을 SakeZuki로 변경

1. [Google Cloud의 Google Auth Platform → 브랜딩](https://console.cloud.google.com/auth/branding)을 연다.
2. 현재 GOOGLE_CLIENT_ID가 속한 프로젝트를 선택하고 **앱 이름(App name)**을 `SakeZuki`로 저장한다. OAuth 클라이언트 목록의 관리용 이름 변경과는 다르다.
3. 이미 검증된 앱의 이름 변경은 Draft Branding으로 저장되고 브랜드 재검증이 필요할 수 있다. 콘솔에 표시되는 검증/게시 절차를 확인한 뒤 새 로그인 요청으로 표시를 확인한다.

공식 참고: [Google Identity 설정](https://developers.google.com/identity/gsi/web/guides/get-google-api-clientid), [브랜드 변경·검증](https://developers.google.com/identity/verification/authentication-verification).

## 남아 있는 제한과 후속 확인

- 실제 Google 동의 취소와 임시 상태 10분 경과를 사람이 끝까지 기다리는 시나리오는 수행하지 않았다. 잘못된 state/취소 콜백은 실제 HTTP/E2E로, 임시 상태의 누락·만료·일회성은 기존 단위 테스트로 확인했다.
- 실서비스의 관리자 업무 API는 없으므로 일반 회원의 관리자 경로 차단과 관리자 역할의 사용자 API 접근을 확인했다. 관리자 업무 화면 전체를 검증했다는 의미는 아니다.
- 사케→음식의 실제 Gemini 신규 생성은 수행하지 않았다. 이 방향은 분리 DB의 기존 추천 캐시 조회로 검증했다. HTTPS 배포의 Secure 쿠키·여러 백엔드 세션 공유도 미검증이다.
- 로그아웃 후 이미 발급된 Access Token은 기존 설계대로 만료 전까지 유효하다. Refresh 계열의 폐기는 검증했다.
- `npm audit --omit=dev`에서 기존 Next.js 16.3.5(critical), sharp(high), source-map-js(high)의 알려진 취약점이 보고됐다. 이번 작업에서는 프레임워크 버전 업그레이드를 수행하지 않았다. Next.js의 보고 범위는 16.3.7 이하이며 [관련 Next.js 보안 권고](https://github.com/advisories/GHSA-vcvr-r3jv-pc5j)와 패치 버전 적용을 별도 후속 작업으로 다뤄야 한다. 테스트 통과가 이러한 의존성 취약점 해결을 의미하지 않는다.

## 변경 파일

```text
backend/build.gradle
backend/src/main/java/com/sakezuki/backend/config/WebConfig.java
backend/src/test/java/com/sakezuki/backend/auth/AuthMySqlIntegrationTests.java
backend/src/test/java/com/sakezuki/backend/auth/AuthE2eServer.java
compose.auth-test.yaml
scripts/test_auth_integration.py
frontend/.dockerignore
frontend/.gitignore
frontend/eslint.config.mjs
frontend/next.config.ts
frontend/package.json
frontend/package-lock.json
frontend/playwright.config.ts
frontend/tests/e2e/auth.spec.ts
frontend/commons/phone.ts
frontend/app/signup/social/page.tsx
docs/auth-integration-validation.md
```
