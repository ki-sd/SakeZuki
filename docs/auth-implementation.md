# 인증 구현 및 검증 보고

검증일: 2026-10-19. 로컬 `main` 기준으로 구현했으며 커밋·푸시는 하지 않았다.

## 기존 구현 조사

- 이메일 로그인은 AuthenticationManager → BCrypt → JWT 발급까지 구현되어 있었다. `/api/user/me`와 회원·권한 Mapper도 존재했다.
- Google OIDC 서비스, provider/sub 조회, 기존 회원·신규 회원 분기, 소셜 회원 생성 및 기본 권한·등급 부여 트랜잭션이 존재했다.
- 작업 시작 당시 로컬 변경에는 OAuth 성공 핸들러 연결, IF_REQUIRED, 가입정보 반환 DTO 수정이 이미 포함되어 있어 유지했다.
- 프론트엔드 로그인·콜백·소셜 가입 페이지와 인증 상태 연결, Refresh Token·로그아웃은 없었다. 일반 이메일 회원가입 API 및 가입 정책은 없어 새로 만들지 않았다. 기존 이메일 계정의 로그인과 Google 신규 가입을 제공한다.
- 미지정 API 공개, 전체 CSRF 해제, OAuth 임시 상태의 명시적 만료·원자적 소비 부재, 로그인 완료 후 OAuth 인증 세션 잔존, 하드코딩된 리다이렉트, OAuth 경로의 프록시 누락을 보완했다.
- 기존 추천 서비스 및 추천 API 파일의 미커밋 수정은 수정하지 않았다. AuthController, SecurityConfig, next.config에는 기존 로컬 변경을 반영한 상태로 인증 기능을 확장했다.

## 최종 흐름과 보안 정책

1. 이메일 로그인은 기존 BCrypt 검증을 사용한다. Google 로그인은 검증된 Google OIDC의 이메일 확인 여부를 검사하고, GOOGLE + sub로만 기존 계정을 식별한다. 이메일 일치만으로 연결하지 않는다.
2. OAuth 전용 SecurityFilterChain은 세션에 인증 요청/state를 저장하고 Spring Security의 state 검증과 세션 ID 교체를 사용한다. 성공 시 OAuth 인증 세션을 폐기하고 별도 임시 세션을 만든다. 실패·취소 시 세션을 폐기하고 로그인 화면으로 보낸다.
3. 임시 상태는 생성 후 10분간 유효하며 서버 세션에서 관리한다. 기존 회원은 `/social/token`, 신규 회원은 추가정보 입력 후 `/social/signup`으로 교환한다. 제출 시 한 번만 소비한다. 입력 오류·중복·네트워크 오류로 교환에 실패하면 Google 로그인을 다시 시작해야 한다.
4. 소셜 회원·소셜 계정·기본 권한·등급과 최초 Refresh Token 저장은 회원가입 서비스의 같은 트랜잭션에 참여한다. 이메일·전화번호·provider/sub DB 유일 제약을 유지하고 동시 중복은 409로 반환한다. 전화번호는 서식을 제거한 7~15자리 숫자와 선택적 국가번호 `+`로 검증한다. 소셜 회원 비밀번호는 NULL을 유지한다.
5. Access Token은 HS256, issuer 검증, 회원번호 subject, email·roles, 15분 유효기간을 사용한다. 키는 Base64로 인코딩한 32바이트 이상의 무작위 값이어야 한다. Access Token은 메모리에만 두고 Authorization 헤더로 전송한다. URL·localStorage에 넣지 않는다.
6. Refresh Token은 256비트 무작위 문자열이며 DB에는 SHA-256 해시만 저장한다. HttpOnly, SameSite=Lax, Path=/api/auth 쿠키를 사용하고 Secure는 환경변수로 설정한다. 토큰 계열의 절대 유효기간은 최초 발급부터 30일이며 회전해도 연장되지 않는다. 쿠키가 남아 있어도 서버의 절대 만료를 우선한다.
7. 재발급은 계열 행을 FOR UPDATE로 잠그고 토큰 행을 다시 잠금 조회한다. MySQL REPEATABLE READ에서도 최신 used 상태를 읽도록 했다. 사용된 토큰이 재전송되면 같은 계열을 전부 폐기한다. 이 폐기는 401 예외에도 커밋한다. 사용 이력은 계열 만료까지 유지하고 만료 계열은 하루 간격으로 삭제한다.
8. 로그아웃은 현재 Refresh Token 계열을 폐기하고 쿠키·임시 세션·브라우저 인증 상태·Query 캐시를 정리한다. 잘못된 쿠키의 로그아웃도 허용한다. 이미 발급된 Access Token은 만료 전까지 유효하다(기본 15분, Spring JWT 시간 검증의 기본 허용 오차 적용).
9. API 전용 체인은 세션의 OAuth 인증을 읽지 않는다. `/api/user/**`는 USER 또는 ADMIN, `/api/admin/**`는 ADMIN을 요구한다. 사케·추천·인증 진입점은 공개하고 나머지는 기본 차단한다. 인증 실패는 401, 권한·CSRF 실패는 403으로 구분한다.
10. 쿠키·세션을 사용하는 `/api/auth/**`의 쓰기 요청은 CSRF 토큰을 요구한다. 프론트는 매 인증 작업 전 `/csrf`에서 토큰을 읽어 X-CSRF-TOKEN으로 보낸다. 일반 API는 Bearer 방식이다. 브라우저는 동일 출처 프록시를 사용하므로 교차 출처 자격증명 CORS 허용을 추가하지 않았다. 8080 직접 호출 방식은 지원 경로가 아니다.
11. 새로고침 시 Refresh Token으로 로그인 상태를 복원한다. Axios 401 재시도는 요청당 한 번, 재발급은 단일 진행 요청으로 합친다. 지원 브라우저의 Web Locks로 탭 사이 쿠키 회전도 직렬화한다. OAuth 콜백은 React Strict Mode의 중복 effect에도 한 번만 교환한다. 재발급 실패 후에도 공개 사케·추천 요청은 비로그인으로 재시도할 수 있다.

## API

| 구분 | 메서드·경로 | 설명 |
|---|---|---|
| 추가 | GET /api/auth/csrf | 세션 CSRF 토큰, 캐시 금지 |
| 추가 | POST /api/auth/refresh | Refresh 쿠키 회전, Access Token 반환 |
| 추가 | POST /api/auth/logout | Refresh 계열 폐기·쿠키 삭제, 204 |
| 기존 확장 | POST /api/auth/login | 이메일 로그인·Refresh 쿠키 발급 |
| 기존 수정 | GET /api/auth/social/signup-info | 만료 검사, provider/email/name만 반환 |
| 기존 수정 | POST /api/auth/social/signup | 일회성 가입정보·주소·전화번호로 가입 및 로그인 |
| 기존 수정 | POST /api/auth/social/token | 기존 Google 회원의 일회성 토큰 교환 |
| 기존 연결 | GET /api/user/me | Bearer JWT의 회원번호·이메일·권한 조회 |
| 프록시 연결 | GET /oauth2/authorization/google | Spring Security Google 인증 시작 |
| 프록시 연결 | GET /login/oauth2/code/google | Google 등록 콜백 |

로그인 화면 `/login`, Google 완료 화면 `/oauth/callback`, 소셜 가입 화면 `/signup/social`, 회원 전용 화면 `/account`를 추가했다. 헤더에서 권한에 따라 일반 회원·관리자 계정을 표시한다. 실제 권한 통제는 백엔드에서 한다. 기존 관리자 업무 API나 관리자 화면은 없어 새 업무 기능은 만들지 않았다.

## DB·실행 설정

새 마이그레이션은 `backend/src/main/resources/db/migration/V4__add_refresh_tokens.sql`이다. AUTH_TOKEN_FAMILY와 AUTH_REFRESH_TOKEN을 생성하며 기존 V1~V3은 변경하지 않았다. 실제 DB에는 이번 작업에서 적용하지 않았다. 설정된 MySQL로 다음 백엔드 기동 시 Flyway가 적용한다.

개발 환경 `.env`에 기존 MySQL·Gemini·JWT·Google 키를 설정하고 다음 값을 사용한다. 비밀값은 예시 파일이나 저장소에 기록하지 않는다.

```properties
GOOGLE_REDIRECT_URI=http://localhost:3000/login/oauth2/code/google
AUTH_FRONTEND_URL=http://localhost:3000
AUTH_SECURE_COOKIES=false
```

Google Cloud OAuth 클라이언트의 승인된 리디렉션 URI에도 위 `GOOGLE_REDIRECT_URI`를 정확히 등록한다. Next.js 3000과 Spring 8080을 기동한 뒤 브라우저는 3000만 사용한다. API뿐 아니라 `/oauth2/**`, `/login/oauth2/**`도 Next 개발 rewrite를 통한다. 배포에서는 Nginx가 세 경로를 Spring으로 전달한다.

배포에서는 실제 HTTPS 도메인으로 AUTH_FRONTEND_URL·GOOGLE_REDIRECT_URI를 변경하고 AUTH_SECURE_COOKIES=true를 설정한다. 현재 Compose의 Nginx는 HTTP 80만 제공하므로 HTTPS 종단 설정은 별도 필요하다. OAuth 세션은 현재 서버 메모리를 사용하므로 여러 백엔드 인스턴스를 운영하려면 sticky session 또는 공유 세션 구성이 필요하다. 기존 Google 콜백을 8080에 등록해 두었다면 3000 경로 또는 배포 도메인 경로로 수정해야 한다.

## 검증 결과

| 실행 | 결과 |
|---|---|
| Gradle test --tests 'com.sakezuki.backend.auth.*' | 19개 통과: Security 8, AuthService 7, RefreshTokenService 4 |
| npm run test:auth | 7개 통과 |
| npx tsc --noEmit | 통과 |
| npm run lint | 오류 0, 기존 코드의 이미지·미사용 import 경고 5개 |
| npm run build | 프로덕션 빌드 통과, 새 인증 경로 생성 확인 |
| git diff --check | 통과 |
| 전체 Gradle test | 실패: 기존 BackendApplicationTests.contextLoads가 MySQL 연결 실패 |

프론트 빌드는 제한된 환경에서 Google Fonts 다운로드에 한 차례 실패했으며, 네트워크 접근이 가능한 실행에서 성공했다.

백엔드 테스트는 실제 BCrypt·JWT 서명/만료 검사, MockMvc 실제 SecurityFilterChain의 401/403·CSRF·쿠키·OAuth 실패·세션 격리, 서비스 단위 중복 검증, 재사용 시 폐기 트랜잭션 커밋을 검증한다. DB 접근은 mock이므로 실제 MySQL 마이그레이션·MyBatis 매핑·동시 트랜잭션의 통합 성공을 의미하지 않는다. 프론트 테스트는 인증 모듈과 요청 계층을 검증하며 실제 브라우저 E2E 테스트가 아니다.

## 실제 환경에서 수동으로 확인할 항목

1. MySQL 기동 후 Flyway V4 적용과 새 두 테이블을 확인한다. 기존 BCrypt 이메일 회원으로 정상 로그인, 오입력·미가입 이메일의 401, 보호 화면의 원래 경로 복귀를 확인한다. 일반 회원가입 API는 추가하지 않았다.
2. 새 Google 계정으로 로그인해 이름·이메일 자동 입력, 주소·전화번호 입력, 가입 후 `/account` 진입을 확인한다. DB에서 MEMBER, SOCIAL_ACCOUNT, ROLE_USER, 일반회원 등급이 함께 생성되고 비밀번호가 NULL인지 확인한다.
3. 같은 Google 계정으로 다시 로그인해 가입 화면 없이 완료되는지 확인한다. 같은 이메일의 기존 일반 계정은 연결하지 않고 409를 반환하는지, 같은 전화번호·중복 제출이 회원을 추가 생성하지 않는지 확인한다.
4. Google 인증 취소, 잘못된 state, 10분이 지난 임시 가입정보, 세션 쿠키 삭제, 콜백 새로고침이 안전하게 다시 로그인을 안내하는지 확인한다. 브라우저 주소에 JWT가 없는지도 확인한다.
5. 개발자 도구에서 Refresh 쿠키의 HttpOnly·SameSite·Path와 배포 HTTPS의 Secure를 확인한다. 인증 쓰기 요청에서 CSRF 헤더를 제거하면 403인지 확인한다. 로그인 후 페이지를 새로고침해 상태가 복원되는지 확인한다.
6. Access Token 만료 후 보호 API 호출이 한 번 재발급·재시도되는지 확인한다. 이전 Refresh Token을 시험용 클라이언트에서 재전송하면 401이고 새 토큰도 폐기되는지, 로그아웃 뒤 이전 토큰으로 재발급되지 않는지 확인한다. 계열 만료와 여러 탭의 동시 재발급도 확인한다.
7. 비로그인 보호 API는 401, 일반 회원의 관리자 API는 403인지 확인한다. 관리자 권한 JWT는 관리자 API를 통과하는지 확인한다. 현재 실제 관리자 업무 API는 없으므로 테스트의 검증용 컨트롤러만 자동 검증했다.
8. 비로그인·로그인·만료 상태에서 기존 사케 목록·상세·추천 기능과 작은 화면의 헤더·입력폼을 확인한다. 실제 Google 사용자 동의 화면, 쿠키 프록시 왕복, MySQL 잠금·롤백은 이번 환경에서 끝까지 실행하지 못했다.

## 변경 파일

아래는 이번 작업에서 추가·수정한 파일이다. 경로는 저장소 기준이다.

```text
.env.example
backend/src/main/java/com/sakezuki/backend/auth/AuthProperties.java
backend/src/main/java/com/sakezuki/backend/auth/controller/AuthController.java
backend/src/main/java/com/sakezuki/backend/auth/dto/LoginResponseDTO.java
backend/src/main/java/com/sakezuki/backend/auth/mapper/RefreshTokenMapper.java
backend/src/main/java/com/sakezuki/backend/auth/service/AuthService.java
backend/src/main/java/com/sakezuki/backend/auth/service/AuthServiceImpl.java
backend/src/main/java/com/sakezuki/backend/auth/service/InvalidRefreshTokenException.java
backend/src/main/java/com/sakezuki/backend/auth/service/RefreshTokenService.java
backend/src/main/java/com/sakezuki/backend/commons/ControllerException.java
backend/src/main/java/com/sakezuki/backend/config/SecurityConfig.java
backend/src/main/java/com/sakezuki/backend/security/CustomUserDetailsService.java
backend/src/main/java/com/sakezuki/backend/security/jwt/JwtConfig.java
backend/src/main/java/com/sakezuki/backend/security/jwt/JwtProperties.java
backend/src/main/java/com/sakezuki/backend/security/jwt/JwtTokenProvider.java
backend/src/main/java/com/sakezuki/backend/security/oauth/CustomOidcUserService.java
backend/src/main/java/com/sakezuki/backend/security/oauth/OAuth2LoginSuccessHandler.java
backend/src/main/java/com/sakezuki/backend/security/oauth/SocialLoginState.java
backend/src/main/java/com/sakezuki/backend/security/oauth/SocialSignupDTO.java
backend/src/main/resources/application.yaml
backend/src/main/resources/db/migration/V4__add_refresh_tokens.sql
backend/src/test/java/com/sakezuki/backend/auth/AuthSecurityTests.java
backend/src/test/java/com/sakezuki/backend/auth/AuthServiceTests.java
backend/src/test/java/com/sakezuki/backend/auth/RefreshTokenServiceTests.java
frontend/app/account/page.tsx
frontend/app/api/auth.ts
frontend/app/login/page.tsx
frontend/app/oauth/callback/page.tsx
frontend/app/providers.tsx
frontend/app/signup/social/page.tsx
frontend/components/auth/AuthProvider.tsx
frontend/components/auth/LoginForm.tsx
frontend/components/layout/Header.tsx
frontend/next.config.ts
frontend/package.json
frontend/tests/auth.test.mjs
nginx/default.conf
docs/auth-implementation.md
```

구현 API 확인에는 [Spring Security CSRF 문서](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html), [OAuth2 문서](https://docs.spring.io/spring-security/reference/servlet/oauth2/index.html), [Next.js rewrite 문서](https://nextjs.org/docs/app/api-reference/config/next-config-js/rewrites)와 설치된 Next.js 16.3.5의 App Router 문서를 참고했다.
