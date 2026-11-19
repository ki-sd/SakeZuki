# 인증·본인확인·크레딧 검증 보고서

기준: 2026-11-19 KST, main `0d9cd987cd3ebc9be5fe75c604e9cfbf0c082a29`. 작업 브랜치 `fix/auth-security-oci-20261119`.

## 시작 상태와 진단

전체 Git 체크아웃의 시작 작업 트리는 깨끗했다. README, 인증 구현·통합 검증 문서, Java/TS 소스, Flyway V1-V4, Compose, Nginx, Jenkins와 테스트를 확인했다. Notion 개발 기록 및 요구사항도 읽었으나 오래된 요구사항보다 코드를 우선했다. 최초 셸의 DNS 제한은 다른 실행 도구로 해결하여 전체 Git 이력과 의존성을 확보했다. 사용자 PC의 다른 체크아웃은 수정하지 않았다.

| 영역 | 시작 상태 | 실제 문제 및 이번 처리 |
|---|---|---|
| 이메일·비밀번호 로그인 | 구현 완료 | BCrypt 유지, UTF-8 72바이트 경계 명시. 일반 이메일 회원가입은 추가하지 않음 |
| Google OIDC 신규·기존 로그인 | 구현 완료 / 외부 재검증 불가 | provider+subject 식별·이메일 검증·이메일 자동 연결 금지 유지. 신규 추가정보 저장 전 승인된 약관 버전 확인 추가 |
| Naver, PASS 등 | 미구현 | Naver 추가 안 함. 사업자 독립 서버 인터페이스만 마련, 기본 구현은 503으로 닫힘 |
| JWT HS256·issuer·시간 | 구현 완료 / 보완 필요 | 필수 exp·iat·양의 회원 식별자·허용 역할 계약 검증, 비활성 회원의 기존 JWT 접근 즉시 차단 |
| Refresh 해시·회전·재사용 계열 폐기 | 구현 완료 | 기존 family 잠금과 noRollbackFor 정책 보존. 동시 재사용 시 계열 폐기 테스트 재실행 |
| OAuth state·가입 임시 상태 | 구현 완료 | Spring state 검사와 TTL·일회성 소비·고정 복귀 경로 유지. 프론트 복귀 URL 정규화 보완 |
| 세션·쿠키·CSRF | 구현 완료 / HTTPS 검증 필요 | 인증 전용 세션, 고정 공격 방어, HttpOnly/Lax/host-only 유지. Secure 쿠키·HTTPS 구성을 환경변수로 통합 |
| 인가·401/403 | 구현 완료 / 확장 | 추천을 인증·본인확인·크레딧 경계로 전환. 사케 목록·검색·상세는 무료 유지 |
| CORS·Axios 경합 | 구현 완료 / 보완 필요 | 동일 출처 배포 유지. Web Locks 미지원 시 현재 탭 직렬화 보완, 유료 추천의 익명 재시도 제거 |
| 중복 가입·트랜잭션 롤백 | 구현 완료 / 확장 | 기존 회원·소셜 유일 제약 보존. 식별 HMAC 유일 제약과 최초 지급 원장 추가 |
| 요청 제한·비용 방어 | 미구현 | Nginx 인증·추천 제한, 본문 크기 제한, 생성 동시성·UTC 일일 생성 시도 한도 추가 |
| 인증·개인정보 로그 | 보완 필요 | OAuth 쿼리 없는 접근 로그, 원문 예외·추천 입력 로그 제거. 상태·예외 클래스만 기록 |
| 본인확인·크레딧·개인 기록 | 미구현 | V5 새 테이블, 예약/확정/복구, 멱등성, 스냅샷, 마이페이지 구현 |
| 알려진 의존성 권고 | 보완 필요 | 공식 권고·실제 설치 가능 버전을 대조해 패치 및 재검사 |

이미 존재하던 Google 로그인과 토큰 시스템을 교체하지 않았다. 기존 회원 데이터·DB 볼륨에 연결하거나 초기화하지 않았으며 V1-V4는 변경하지 않았다.

## 설계와 경계

- `MEMBER.status`는 계정 활성 여부다. 정식 전환 여부는 `MEMBER_VERIFICATION.verified_at_epoch` 및 `adult`로 별도 판정한다. 기존 회원은 로그인과 무료 탐색을 유지하고, 확인 전 추천·최초 지급은 차단한다. 기존 회원도 최초 본인확인 시 동일한 1인 1회 정책을 적용한다.
- 본인확인 식별값은 서버 사업자 조회 결과만 사용한다. 브라우저의 성공·성인 여부·DI 입력을 받지 않는다. 서버 거래번호, 소유 회원, 만료·일회성, 확인 시각, 성인 결과를 검사한다.
- `IdentityProvider` 기본 구현은 사용 불가이며 운영용 성공 Mock·수동 완료 API·크레딧 충전 우회 API가 없다. 합성 완료 상태는 test 소스와 별도 DB에만 있다. 실제 PASS 왕복 및 사업자별 브라우저 콜백 화면은 계약 후 어댑터와 함께 연결해야 한다.
- CI/DI 원문·생년월일은 추가 저장하지 않는다. 계약된 서비스 범위 식별값을 별도 비밀키의 HMAC-SHA256으로 변환한다. 해시도 개인정보이며 처리 근거·보유 기간·탈퇴 후 부정 지급 방지 범위를 확정해야 한다. 키·식별 scope 변경은 중복 지급 방지를 깨뜨릴 수 있어 보존·이관 없이 교체하면 안 된다.
- 약관 버전과 URL이 비어 있으면 신규 소셜 추가정보 저장은 503이다. 기존 로그인은 유지한다. 실제 약관과 수집·이용 동의문을 임의로 작성해 승인된 문서처럼 배포하지 않았다. 기존 필수 주소·전화번호의 수집 필요성도 공개 전 재검토 대상이다.

### 크레딧 상태 전이

| 사건 | 요청 상태 | 가용 잔액 | 원장 |
|---|---|---|---|
| 본인확인 최초 성공 | 별도 최초 지급 | 기본 +10 | GRANT / INITIAL |
| 신규 추천 예약 | RESERVED | -1 | RESERVE |
| 결과 스냅샷 확정 | COMPLETED | 변화 없음 | COMMIT / 0 |
| 확정적 실패 | FAILED | +1 | REFUND |
| 처리 중단·예약 만료 | TIMED_OUT | +1 | REFUND |
| 같은 키 재조회·기록 재열람 | 상태 유지 | 변화 없음 | 추가 기록 없음 |

회원별 계정 잠금으로 잔액·요청 생성·원장을 직렬화한다. `(member_no,idempotency_key)` 및 원장 `(member_no,reference_id,kind)` 유일 제약도 적용한다. 같은 키의 다른 입력은 409, 처리 중은 409, 실패 키는 추가 차감 없이 409, 만료 결과는 410이다. 잔액 부족은 402이며 실제 PG 결제 완료를 뜻하지 않는다.

Gemini 호출은 예약 트랜잭션 종료 후 수행한다. 결과 JSON과 COMMIT 원장은 하나의 짧은 트랜잭션으로 저장한다. 응답 유실은 같은 키로 확정 결과를 반환한다. 실패 복구가 잠시 실패하면 예약이 남으며, 기본 10분 이후 1분 주기 복구가 계정·요청을 다시 잠그고 한 번만 환불한다. 복구 후 늦게 도착한 생성 결과는 확정하지 않는다. 공용 캐시에 이미 저장된 결과가 남더라도 사용자 크레딧은 실패 정책대로 복구한다.

공용 DB 캐시는 이전 서비스를 재사용한다. 캐시 적중도 새 요청이면 1크레딧이며, 당시 응답은 개인 JSON 스냅샷으로 따로 저장한다. 사케→음식은 당시 제품 상세도 함께 저장한다. 목록은 30개, 원장은 최근 50개씩 페이지 조회가 가능하다. 마이페이지 원장 화면은 최근 50개를 표시한다.

열람 만료는 완료 시점부터 기본 30일이며 즉시 410 및 목록 제외다. 정리는 매 분 100개씩 snapshot·입력만 삭제한다. 요청 키·상태·거래 원장은 30일과 함께 삭제하지 않는다. 원장과 식별 해시의 실제 보존·파기 정책 및 결제 증빙 구조는 유료 출시 전에 별도로 확정해야 한다.

### 인증·운영 트레이드오프

- Access Token은 계속 메모리에만 둔다. Refresh 쿠키는 `/api/auth`, HttpOnly, SameSite=Lax, Domain 미설정이다. HTTPS 구성에서 Secure를 강제한다. 세션 쿠키도 HttpOnly/Lax/Secure다.
- 이미 발급된 Access Token은 로그아웃 뒤 만료 전까지 유효하다. 기본 15분 및 JWT 시간 허용 오차가 있다. 비활성 계정은 추가 DB 조회로 즉시 차단한다. 역할 변경은 기존 토큰 만료까지 반영이 늦을 수 있다.
- Refresh 재사용은 정상 경합인지 탈취인지 서버에서 구분하지 않고 기존 계열을 폐기한다. HTTPS Chromium의 Web Locks가 탭 간 회전을 직렬화하며, 미지원 환경의 fallback은 한 탭만 보장한다.
- 인증 세션은 단일 백엔드 메모리에 있다. 여러 인스턴스는 sticky routing 또는 별도 세션 공유가 필요하다. 현재 Redis·메시지 큐는 도입하지 않았다.
- Nginx 제한은 로그인 5회/분+burst 10, 인증 60회/분+burst 20, 추천 6회/분+burst 5, 추천 전체 연결 8개다. NAT 공유 사용자에게도 합산된다. backend 포트를 외부 공개하면 이를 우회할 수 있다.
- 캐시 미스 생성은 프로세스당 동시 1개, 기본 UTC 하루 100시도다. 사케 추천 한 시도는 Gemini 호출 2회를 포함할 수 있다. 실패도 운영 한도에 포함하지만 사용자 예약은 복구한다. 재시작·다중 프로세스는 한도를 공유하지 않으므로 사업자 API 예산 제한도 함께 설정해야 한다.
- 프록시 헤더는 Nginx에서 덮어쓴다. Compose 내부 backend만 framework 헤더 처리를 켠다. 외부 로드밸런서/CDN을 추가하면 신뢰 프록시와 원래 클라이언트 IP 복원을 다시 설정해야 한다.
- 민감 원문을 숨기기 위해 일반 예외 로그는 클래스명으로 제한했다. 자세한 장애 분석은 민감값을 제거한 재현 테스트와 상태·원장으로 수행해야 한다.

### API 계약

| API | 입력·결과 | 권한·과금 |
|---|---|---|
| GET `/api/auth/signup-policy` | 현재 약관 버전·URL·가입 가능 여부 | 공개, no-store |
| POST `/api/auth/social/signup` | 기존 주소·전화번호 + termsVersion·privacyVersion | 기존 CSRF·일회성 OAuth 세션, 무료 크레딧 미지급 |
| GET `/api/user/account` | 본인확인 상태·잔액·정책·사업자 연결 여부 | 본인 Bearer |
| POST `/api/user/verification/start` | termsVersion·privacyVersion → requestId·redirectUrl | 본인 Bearer, 실제 기본 provider는 503 |
| POST `/api/user/verification/complete` | requestId·proof → 계정 상태 | 본인 Bearer, 서버 사업자 결과 검증 후 최초 지급 |
| POST `/api/recommend/sake` | food | Bearer + UUID Idempotency-Key, 본인확인·성인·1크레딧 |
| POST `/api/recommend/food` | sakeNo | 위와 동일, 기존 응답 필드 유지·당시 sake 상세 추가 |
| GET `/api/user/recommendations?page=0` | 최근 기록 30개·시간·만료 | 본인 Bearer, 무료 |
| GET `/api/user/recommendations/{id}` | 당시 JSON 스냅샷 | 본인 Bearer, 무료·소유권·만료 검사 |
| GET `/api/user/credits/ledger?page=0` | 원장 50개 | 본인 Bearer, 무료 |
| GET `/api/health/ready` | DB 접근 가능 시 UP, 실패 시 503 DOWN | 공개, 상세 정보 없음 |

추천 API의 인증 요구와 멱등성 헤더는 의도적인 계약 변경이다. 프론트와 백엔드를 함께 배포해야 하며 기존 익명 호출 클라이언트는 401을 받는다. 계정 API는 쿠키로 인증하지 않으므로 쓰기도 명시적 Bearer가 필요하다. CSRF 전용 세션 정책은 `/api/auth`에 유지한다.

## 의존성 확인

| 항목 | 시작 | 최종 | 확인한 실제 경로 |
|---|---|---|---|
| Next.js / eslint-config-next | 16.3.5 | 16.4.6 | App Router 사용. next/og의 공격자 SVG 경로는 없음. 이미지 컴포넌트는 주로 unoptimized지만 내장 경로까지 포함해 패치 |
| sharp | 0.35.4 계열 | 0.35.5 | SVG 최적화의 librsvg 권고, 사케 이미지는 unoptimized. 직접 안전 버전 명시 |
| source-map-js | 1.2.1 | 1.2.2 override | 외부 source map 업로드 경로 없음. 전이 의존성 자체도 패치 |
| follow-redirects | 1.16.0 | 1.16.2 이상 잠금 | 앱 Axios는 브라우저 전송. Node의 cross-realm sensitiveHeaders 경로는 확인되지 않으나 패치 |
| Spring Boot | 4.1.1 | 4.1.2 | 같은 4.1 계열의 실제 Maven Central 제공 패치 사용 |
| Jackson 3 / 2 | 3.1.5 / 2.21.5 | 3.1.7 / 2.21.7 | HTTP JSON과 AI 응답 처리에 사용. DataInput·JsonIdentityInfo·다형 타입·XML datatype·Path 입력은 현재 DTO 경로에 없음 |
| Tomcat | 11.0.24 | 11.0.27 | HTTP 서블릿 사용. 컨테이너 DIGEST/FORM/JNDIRealm/AJP 경로는 미사용. 인가 관련 권고 포함해 관리 BOM으로 패치 |
| Guava | 33.4.0-jre | 33.7.2-jre | Google SDK 전이 의존성. 외부 Java native 역직렬화 경로는 없음. 패치 제약 명시 |

공식 근거:

- [Next ImageResponse 권고](https://github.com/vercel/next.js/security/advisories/GHSA-vcvr-r3jv-pc5j), [이미지 최적화 권고](https://github.com/vercel/next.js/security/advisories/GHSA-2464-fhr4-w9xw), [11월 추가 권고](https://github.com/vercel/next.js/security/advisories/GHSA-pc58-hwjq-p385)
- [sharp](https://github.com/lovell/sharp/security/advisories/GHSA-wq5f-xc86-pv6w), [source-map-js 패치](https://github.com/7rulnik/source-map-js/releases/tag/v1.2.2), [follow-redirects](https://github.com/follow-redirects/follow-redirects/security/advisories/GHSA-mrwp-f3q8-3g9p)
- [Jackson core](https://github.com/FasterXML/jackson-core/security/advisories/GHSA-7hhh-6rmp-j9qf), [Jackson databind](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-cxp5-3px4-pw24), [Tomcat 11](https://tomcat.apache.org/security-11.html), [Guava](https://github.com/google/guava/security/advisories/GHSA-xxph-c9ww-hj94)

`npm audit` 전체 의존성에서 보고된 취약점 0건, 실제 해석된 Maven runtime 146개에 대한 OSV querybatch에서 일치 권고 0건이었다. 이는 검사 시점과 데이터베이스 범위의 결과이며 취약점 부재의 보증이나 컨테이너 OS 이미지 검사는 아니다. `securityDependencies` Gradle 작업으로 재검사용 좌표를 생성한다.

## 실제 실행 결과

| 명령·검증 | 결과 |
|---|---|
| Java 21 / Gradle `test` | 25개 통과, 실패·skip 0. Spring Boot 실제 기동 포함 |
| `authIntegrationTest` | 실제 MySQL 8.4.13에서 22개 통과, 실패·skip 0 |
| `npm run test:auth` | 인증 7개+크레딧 3개, 총 10개 통과 |
| `npx tsc --noEmit` | 통과 |
| `npm run lint` | 오류 0, 기존 경고 5개 |
| `npm run build` | Next.js 16.4.6 프로덕션 빌드 통과 |
| `npm run test:e2e` | 실제 Next 프록시·Spring·MySQL·Chromium에서 6개 통과 |
| `npm audit --json` | 전체 prod/dev 의존성 보고 0건 |
| Gradle `securityDependencies` + OSV API | runtime 146개, 일치 권고 0건 |
| Compose 5.8.0 `-f docker-compose.yaml -f compose.https.yaml config --format json` | 병합·환경 치환 통과, MySQL 127.0.0.1만 바인딩·backend 미공개·기존 볼륨 확인 |
| Nginx 1.31.9 native `-t` 및 HTTP 검사 | HTTP/HTTPS 문법, 429·413, 헤더 덮어쓰기, OAuth 쿼리 로그 제외 통과 |
| `git diff --check` / V1-V4 diff | 통과 / 변경 없음 |
| Docker 이미지 build 및 Linux Compose 실제 기동 | Docker daemon이 없어 미실행 |
| 실 Google·PASS·PG·실도메인 인증서·OCI 배포 | 미실행 |

검증 DB는 새로 만든 `127.0.0.1:3308/sakezuki_auth_it`이며 다른 DB·볼륨에 접속하지 않았다. Docker daemon이 없어 공식 MySQL ZIP을 임시 디렉터리에 풀고 별도 datadir·임의 암호 계정으로 실행했다. root 초기화는 이 새 datadir에만 수행했다. 실제 회원 데이터나 API 키는 테스트에 사용하지 않았다.

초기 일반 기동 테스트는 기본 DB 부재로 1개 실패했다가 분리 DB 구성 후 통과했다. 기존 테스트의 고정 전화번호·캐시 fixture가 재실행에 충돌하는 문제를 실행마다 분리했다. 추가한 HTTP 테스트는 기본 HTTP/2 업그레이드에서 간헐적인 408이 있어 기존 통합 테스트와 동일하게 HTTP/1.1 및 명시적 타임아웃을 사용한다. 최초 프론트 빌드는 제한된 셸의 Google Fonts DNS 문제로 실패했으나 네트워크 가능한 실행 경로에서 같은 소스로 통과했다. 새 UI 잔액 문자열과 프론트 in-flight 경합은 테스트에서 발견해 수정했다. 실패를 통과로 간주하지 않았다.

크레딧 검증에는 마지막 1크레딧 동시 8요청, 동일 키 동시 6요청, 완료/환불 경합, 원장+스냅샷 rollback, 재시작 후 고아 예약 복구·중복 복구·늦은 확정 차단, 중복 본인확인·미성년·만료·타인 확인, 캐시 변경 후 스냅샷 보존, 30일 만료 삭제, 타인 기록 HTTP 차단이 포함된다. Chromium에서는 완료 응답을 실제로 끊은 뒤 같은 키 재시도와 무료 재열람을 확인했다. Google 인증 시작·잘못된 state는 실제 HTTP이나 실계정 동의 완료는 아니다.

표준 재실행은 [기존 분리 테스트 실행법](auth-integration-validation.md#재실행)을 따른다. Java 21, Node, Docker 및 Chromium이 있으면 `python scripts/test_auth_integration.py --e2e`로 새 테스트도 함께 실행된다. 일반 기동 테스트에는 분리 DB용 MYSQL/JWT/Google/Gemini 테스트 설정을 제공해야 한다. 테스트 코드의 고정 JWT 키·합성 본인확인 fixture를 운영에 사용하면 안 된다.

## 운영 전 남은 항목

1. [OCI 절차](oci-public-deployment.md)에 따라 도메인·인증서·Google 승인 URI·세션 쿠키를 실제 HTTPS에서 확인한다. Linux Docker 이미지 빌드와 전체 프록시 왕복도 실제 호스트에서 확인한다.
2. 본인확인 사업자 계약, 서버 서명 검증·거래 조회 어댑터, 서비스 범위 DI 제공 여부, 프론트/사업자 콜백 및 재인증 정책을 확정한다. 현재 운영에서 인증을 통과할 수 없다.
3. 승인된 약관·개인정보 수집·이용 동의 전문·처리방침, 주소 수집 필요성, 해시/원장 보존·파기 및 탈퇴 정책을 확정한다. 빈 설정은 신규 가입을 막는다.
4. 실제 PG·충전·환불·결제 증빙·멱등 지급·법정 기록 보존은 미구현이다. 현재 무료 크레딧의 원장과 예약 흐름을 유료 결제 완료로 표현하지 않는다.
5. 사용량 증가 시 분산 세션과 생성 예산 공유를 검토한다. 현재 구조는 단일 백엔드·단일 Nginx를 기준으로 한다.
