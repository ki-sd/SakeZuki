# OCI 공개 배포 준비

실서비스에 배포하거나 main에 병합하지 않았다. 현재 변경은 검증과 외부 계약을 마친 뒤 적용할 준비 구성이다.

## 데이터와 프로젝트 이름

기존 Compose 프로젝트 이름·실제 MySQL 볼륨 이름을 먼저 `docker compose ls`, `docker volume ls`, `docker inspect sakezuki-mysql`로 확인한다. 같은 프로젝트 이름을 유지하고 기존 DB 백업 및 복원 검증 후 V5를 적용한다. `down -v`, volume 삭제, Flyway clean, 기존 migration 수정으로 초기화하지 않는다. 새 테이블이 추가되는 MySQL DDL이므로 적용 중 실패하면 데이터 삭제 대신 history와 실제 DDL 상태를 조사한다.

## 설정

1. 운영 환경 파일은 Jenkins의 기존 `ENV_FILE` 위치에 두고 권한 600을 유지한다. MYSQL·JWT·Google·Gemini 값을 저장소에 넣지 않는다. JWT 키는 독립적인 32바이트 이상 Base64 난수다.
2. DNS에 실제 공개 도메인을 연결하고 신뢰 가능한 인증서를 발급한다. `TLS_CERT_DIR`에 `fullchain.pem`, `privkey.pem`이 모두 읽히도록 준비한다. Let's Encrypt symlink를 쓴다면 대상 파일까지 컨테이너에서 보이게 해야 한다. 인증서 발급·갱신은 이 저장소가 자동 수행하지 않는다.
3. Linux OCI 환경 파일에 `PUBLIC_HOST`는 scheme·포트·경로 없는 실제 호스트명, `TLS_CERT_DIR`은 인증서 디렉터리 절대경로를 지정한다. 실제 값이 정해지지 않았으므로 예시 도메인을 운영값으로 하드코딩하지 않았다.
4. `COMPOSE_FILE=docker-compose.yaml:compose.https.yaml`을 설정한다. 모든 Jenkins compose 명령이 같은 파일 집합을 사용한다. 별도 실행도 `docker compose -f docker-compose.yaml -f compose.https.yaml config --quiet`로 먼저 검증한다. config 전체 출력에는 비밀값이 포함될 수 있으므로 CI에서는 quiet를 사용한다.
5. HTTPS override는 `AUTH_FRONTEND_URL=https://PUBLIC_HOST`, `GOOGLE_REDIRECT_URI=https://PUBLIC_HOST/login/oauth2/code/google`, `AUTH_SECURE_COOKIES=true`를 일괄 적용한다. Google Cloud 승인 redirect URI를 실제 문자열과 정확히 맞춘다. 호스트·scheme·슬래시·경로 차이도 다른 URI다.
6. OCI 네트워크·호스트 방화벽에서 필요한 80/443만 공개하고 8080/3000/3306/3307은 외부 개방하지 않는다. MySQL 3307은 기존 로컬 관리용 127.0.0.1 바인딩이다. 관리가 불필요하면 별도 설정으로 포트 바인딩을 없앨 수 있다.
7. Jenkins 자체 환경변수 `HEALTHCHECK_URL`을 실제 HTTPS `/api/health/ready`로 지정할 수 있다. 이 값은 Jenkins 셸 설정이며 `.env`를 셸 코드로 source하지 않는다. 기본 HTTP URL도 redirect를 따라가 실제 `status: UP` 본문을 확인한다. 도메인의 인증서를 검증하며 `-k`는 쓰지 않는다.
8. 사용자가 배포할 때 기존 build/up 단계를 실행한다. Nginx 템플릿은 공식 image entrypoint가 envsubst하며 공유 프록시 정책을 포함한다. 인증서 갱신 후 Nginx reload가 필요하다.

## 본인확인과 무료 크레딧 개방

`IdentityProvider` 구현은 사업자 API 자격증명·서명·서버 거래 조회와 아래 계약을 충족해야 한다.

- start가 받은 서버 UUID를 사업자 거래와 결합하고, 완료 증표가 그 거래·서비스·회원에 해당하는지 확인한다. redirect URL은 서버가 지정한 HTTPS origin allowlist를 통과해야 한다.
- verify는 브라우저 성공 flag를 해석하지 않고 서버 통신으로 확정된 `reference, scope, subject, adult, verifiedAt`만 반환한다. `subject` 원문은 저장·로그에 쓰지 않는다.
- `scope`는 Google/Naver 등 로그인 종류와 무관한 서비스 공통 중복가입 범위여야 한다. 사업자가 해당 범위 DI를 제공하는지 계약으로 확인한다. CI를 대안으로 무조건 수집하지 않는다.
- 프론트 사업자 콜백을 연결한 뒤 로그인된 Bearer 요청으로 `/api/user/verification/complete`에 서버 UUID와 사업자 증표를 제출한다. 증표를 URL query·로그·localStorage에 보관하지 않는다. callback format은 사업자 미확정으로 추측 구현하지 않았다. 현재 UI는 시작 및 상태 안내, 서버는 시작·완료 API와 검증 경계를 제공한다.
- HMAC 키는 JWT 키와 별개로 `IDENTITY_HASH_KEY`에 32바이트 이상 Base64 난수를 설정한다. 백업·보존하며 승인된 이관 절차 없이 변경하지 않는다.
- 승인된 전문을 게시한 후 `IDENTITY_TERMS_VERSION`, `IDENTITY_PRIVACY_VERSION`, `IDENTITY_TERMS_URL`, `IDENTITY_PRIVACY_URL`, `IDENTITY_PROVIDER_ORIGIN`을 설정한다. 기본 unavailable provider는 이 값만 채워도 성공하지 않는다.
- 최초 확인·중복 가입·미성년·취소·만료·재전송·동시 완료를 계약된 sandbox와 실사용자 흐름에서 재검증한다. 테스트 소스의 합성 fixture는 운영에 이식하지 않는다.

기존 회원에게 본인확인 완료를 일괄 부여하거나 크레딧을 일괄 지급하지 않는다. 로그인 가능한 미확인 계정과 정식 이용 가능 상태를 구분한다.

## 정보 제공, 성인 정책과 법령 검토

현재 서비스의 성인 확인은 **AI 주류 추천에 적용하는 서비스 정책**이다. 사케 정보 제공만으로 모든 화면에 본인확인이 법적으로 필수라고 단정하지 않는다. 주류 판매·배포, 청소년유해매체물 제공 및 주류 광고에 해당하는지에 따라 적용 범위가 달라진다. 실제 판매·광고·사업 방식 확정 후 적용 기준을 확인해야 한다.

- [청소년 보호법 제28조](https://www.law.go.kr/법령/청소년보호법/제28조)는 주류 등 판매·배포 시 나이·본인 확인을 다룬다. 현재 SakeZuki는 실제 주류 판매를 구현하지 않는다.
- [청소년 보호법 시행령 제17조](https://www.law.go.kr/법령/청소년보호법시행령/제17조)는 청소년유해매체물 제공 시 확인 수단과 특정 재확인 조건을 다룬다. 정보 사이트 전체에 그대로 적용되는 근거로 확대하지 않는다.
- [개인정보 보호법·개인정보 포털 안내](https://m.privacy.go.kr/front/contents/cntntsView.do?contsNo=36)에 따라 최소 수집, 목적·항목·보유기간·거부권 고지, 안전조치와 파기 정책을 정해야 한다. HMAC 식별자도 익명 정보로 취급하지 않는다.
- 유료 추천 도입 시 [전자상거래법 시행령 제6조](https://www.law.go.kr/법령/전자상거래등에서의소비자보호에관한법률시행령/제6조) 등 실제 사업에 적용되는 거래 보존 의무를 확인한다. 개인 추천 스냅샷 30일과 거래 증빙 보존을 혼동하지 않는다. 현재 유료 결제와 법정 보존 정책의 자동 파기를 구현한 상태는 아니다.

본인확인 사업자의 성인 flag가 어떤 나이 기준을 뜻하는지, 재확인 주기, 미성년·탈퇴·동일인 재가입의 처리, 무료 지급 방지 식별정보를 언제까지 보유할지는 계약·정책에 명시해야 한다. 기본 서버 구현은 확정된 성인 결과가 없으면 추천을 허용하지 않는다.

## 공개 전 확인

- 실도메인에서 Google 시작→신규 동의·추가정보→기존 회원 복귀·새로고침·로그아웃을 확인한다.
- Set-Cookie의 Secure·HttpOnly·SameSite·Path와 CSRF 403, 미인증 401, 미확인 403, 잔액 부족 402를 확인한다.
- backend 직접 접근·위조 forwarded 헤더·타 출처 요청을 차단하고 edge 요청 제한이 적용되는지 확인한다.
- Flyway V5·기존 회원 및 사케 행 보존·원장 잔액과 예약 복구를 확인한다.
- Linux/OCI CPU 아키텍처에서 Docker 이미지 빌드·실기동·인증서 갱신·재시작 복구를 확인한다. 이 작업에서는 native Windows 도구로 구문과 HTTP 동작을 검증했으며 실제 Linux 컨테이너 검증을 대신하지 않는다.
