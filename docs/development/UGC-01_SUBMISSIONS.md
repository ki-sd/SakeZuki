# UGC-01 사케 제보와 비공개 첨부

기준 main은 cb28a967b0e2a839940e91c9c83833893269312e다. IMG-03B 기반이 병합된 이후의 Backend PR이다. 사용자 제보는 별도 원장으로 저장한다. 새 공개 상품과 추천 프로필, 관리자 결정 API, 실제 저장소 설정은 후속 단계다. [설계](UGC_CATALOG_ARCHITECTURE.md) · [PR #15 재검증](IMG-03B-3_QA_PR15.md)

## API와 입력

모든 경로는 활성 회원의 JWT가 필요하다. 일반 회원과 관리자 모두 사용자 경로에서는 서명된 subject의 소유권만 사용한다. 목록과 검색의 페이지는 0부터 시작하며 기본 20개, 최대 50개다. 검색 결과는 이름과 내부 번호 기준으로 정렬한다. 시각은 UTC로 기록한다. 응답에 저장소 위치, 작업 키, 원장 내부 식별자는 포함하지 않는다.

| 메서드와 경로 | 계약 |
| --- | --- |
| GET /api/user/catalog/breweries | q, page, size. 일본어·한국어·가나 이름으로 검색 |
| GET /api/user/catalog/brands | breweryNo는 양조장 내부 번호. q, page, size 지원 |
| POST /api/user/sake-submissions | 제보 데이터 JSON, UUID Idempotency-Key, 201 생성 영수증 |
| GET /api/user/sake-submissions | 내 제보 요약, page/size 및 hasMore |
| GET /api/user/sake-submissions/{id} | 현재 요약, 전체 불변 리비전, 상태 변경 이벤트 |
| POST /api/user/sake-submissions/{id}/revisions | JSON의 expectedVersion과 data, 201 |
| POST /api/user/sake-submissions/{id}/cancel | JSON의 expectedVersion과 선택 reason, 200 |
| POST /api/user/sake-submissions/uploads | UUID Idempotency-Key, multipart의 file 한 개, 201 및 첨부 ID·상태·만료·미리보기 경로 |
| GET /api/user/sake-submissions/uploads/{assetId}/preview | 유효한 미연결 첨부의 소유자 미리보기 |
| GET /api/user/sake-submissions/{id}/attachments/{assetId} | 열린 제보의 불변 리비전에 포함된 첨부의 소유자 미리보기 |

필수 이름은 sake의 ja·ko다. sake.kana는 선택이다. brewery는 기존 내부 번호 existingNo 또는 proposed의 ja·ko·선택 kana 중 하나다. brand는 생략하거나 같은 방식으로 제안하며, 기존 브랜드를 선택하면 기존 양조장에 속하는지 확인한다. 사케 이름과 양조장·브랜드 이름은 원문을 사용한다. 일본어 및 가나 500자, 한국어 1,000자까지 허용한다. designation 100자, riceJa·riceKo·yeastJa·yeastKo·variant 500자, notes 2,000자, sourceUrl과 imageRights 1,000자다. notes와 imageRights는 LF와 CRLF를 허용하며 저장과 멱등성 해시 계산 전에 CRLF를 LF로 통일한다. 다른 필드는 한 줄이다. 공백과 개행으로만 된 값, 탭·단독 CR·NUL 등 나머지 제어문자, 짝이 없는 서러게이트는 거부한다. HTML처럼 보이는 설명은 JSON 문자열로만 저장·반환한다. sourceUrl은 호스트가 있는 HTTP(S) 증거 참조이며 서버가 요청하지 않는다.

sakeMeter·acidity·polishingPercent·alcoholPercent는 최소값 min과 선택 최대값 max로 단일값과 범위를 표현한다. 최대 소수 3자리이며 니혼슈도는 -1,000~1,000, 비율과 산도는 0~100이다. 니혼슈도는 기존 지표 단위, 산도는 일본주 산도 지표, 정미율·알코올은 퍼센트, capacityMl은 1~100,000mL 정수다. 범위에서 max가 min보다 작으면 거부한다. 정수·수치 및 동의의 Boolean은 JSON 고유 타입을 사용한다. 숫자·Boolean 문자열, 실수 토큰의 정수 변환, 중복 키, 미등록 속성, 후행 JSON 값은 422다. UUID는 문자열이며 미확인 선택 수치는 NULL로 기록한다. 기존 문자 시드의 범위·단위·미기재 표식을 이 계약으로 덮어쓰지 않는다. attachmentId가 있으면 imageRights와 명시적인 imageConsent가 필요하다. 이 동의는 검토 자료이며 GRANTED 권리 이벤트를 만들지 않는다.

401은 인증/활성 계정, 403은 권한, 404는 없거나 접근할 수 없는 대상, 409는 상태·버전·키 충돌, 413은 본문·파일 크기, 415는 지원 형식, 422는 입력·이미지 검증, 429는 쿼터, 503은 일시적 저장·처리 불가를 의미한다. 내부 SQL이나 저장소 키는 오류 응답에 포함하지 않는다.

## 데이터, 상태, 멱등성

V11은 CATALOG_SUBMISSION, SUBMISSION_REVISION, SUBMISSION_EVENT, SUBMISSION_ATTACHMENT와 검증된 STAGING 조회를 추가한다. V1–V10과 카탈로그 테이블 정의는 유지한다. 현재 리비전과 부모 리비전 FK는 같은 제보를 참조한다. 첨부의 실제 IMAGE_ASSET FK는 저장 검증 이후 설정하며 그 전에도 서버 UUID로 저장 의도를 추적한다. 한 첨부는 최대 한 제보에 귀속된다. 같은 제보의 보완에서 재사용 가능하며 이전 리비전의 참조는 보존한다. 상태·리비전·버전·감사 이벤트를 한 트랜잭션에서 업데이트한다.

최초 제출은 PENDING, 보완은 NEEDS_REVISION에서 새 PENDING 리비전, 취소는 PENDING 또는 NEEDS_REVISION에서 CANCELLED다. REJECTED·APPROVED를 포함한 종료 상태는 사용자 변경을 허용하지 않는다. 사용자는 상태나 역할을 요청으로 지정할 수 없다. 검토 시작 및 공개 승인은 UGC-02의 별도 트랜잭션에서 처리한다.

제보와 업로드 키는 회원별 및 작업별로 독립된다. 같은 키·입력은 같은 생성 영수증, 다른 입력은 409다. 현재 상태는 GET에서 조회한다. 보완과 취소는 expectedVersion이 일치해야 하며 경합한 오래된 요청은 409다. DB의 MEMBER 행을 먼저 잠근 뒤 한도와 요청 키를 검사하므로 인스턴스가 달라도 신규 요청이 한도를 넘지 않는다. 잠금은 회원→제보→첨부 순으로 잡는다. 추천 크레딧은 사용하지 않는다.

## 업로드와 비공개 제공

Spring multipart는 지연 파싱을 사용한다. 파일은 10MiB, multipart 전체는 11MiB다. Nginx는 정확히 업로드 경로의 POST에 11m(11MiB)를 허용하며 기본 본문 제한은 16k(16KiB)다. 업로드 전체 상한은 Spring과 같고 Spring은 추가로 파일 단위 제한을 적용한다. Backend에서도 제보 JSON을 16KiB로 제한하며 인증 이후, multipart 읽기 전에 크기와 요청 키를 확인한다. JVM마다 사용자 HTTP 업로드 1건과 UGC-02A에서 관리자와 공유하는 비공개 미리보기 2건을 동시에 허용한다. 다른 업로드가 진행 중이면 multipart 파싱·저장 의도·쿼터 예약 전에 503을 반환한다. Nginx 업로드 IP 제한은 분당 12회와 순간 허용 4회다. 파일 파트 MIME은 정확히 image/jpeg 또는 image/png를 받는다. IMAGE/PNG 및 image/png; charset=utf-8은 현재 415다. 기존 이미지 코어가 JPEG·PNG 검증, 픽셀·해상도 제한, 재인코딩·메타데이터 제거, SHA-256 및 버전형 키를 제공한다.

업로드 의도와 쿼터를 먼저 커밋한다. 그 뒤 트랜잭션 없이 동일 UUID로 ImageAssetService.stage를 호출한다. 조건부 PUT과 실제 GET 후 V10 VERIFIED, V11 READY를 순서대로 기록한다. 5분 의도 임대 중 중복 작업은 409다. 타임아웃 뒤 다른 세대가 실행될 수 있고, 이전 세대의 늦은 완료는 READY를 덮어쓰지 않는다. PUT 뒤 DB 반영 실패는 같은 회원·키·입력으로 재시도하며 기존 객체를 검증한다. 확정 손상은 V10의 명시적 복구 절차를 따른다. 만료·취소는 물리 삭제로 이어지지 않는다.

미연결 첨부는 만료 전까지, 연결된 첨부는 PENDING·NEEDS_REVISION 제보의 실제 불변 리비전에 포함된 동안만 제공한다. 다른 회원, 비활성 회원, 닫힌 제보, DENIED·REVOKED 권리, 실패·손상 객체는 차단한다. 현재 검증된 STAGING만 읽고 실제 GET 바이트를 확인한 뒤 소유권·계정 상태·연결·권리·무결성을 다시 검사한다. 비공개 응답에는 private, no-store 및 nosniff가 적용된다. DB 실패 갱신으로 원래 저장 오류가 가려지지 않도록 공개·비공개 검증 실패 기록은 예외 클래스만 로그에 남긴다.

| 설정 | 초기값과 기준 |
| --- | --- |
| ugc.uploads-per-day | 10건, 최근 24시간의 새 저장 의도. 바이트 검증 실패도 건수에는 포함 |
| ugc.submissions-per-day | 5건, 최근 24시간의 새 제보. 취소하더라도 집계 |
| ugc.revisions-per-day | 10건, 최근 24시간의 사용자 보완 |
| ugc.open-submissions | 10건, PENDING과 NEEDS_REVISION |
| ugc.member-reserved-bytes | 256MiB. 작업마다 최대 변환 크기를 선예약하고 READY에서 실제 저장 바이트로 정산. 결과가 불확실하면 예약 유지 |
| ugc.unlinked-hours | 24시간. 만료·취소 이후에도 물리적으로 남을 수 있는 바이트는 추적 |

지원하지 않는 미디어나 본문 한도 초과는 저장 의도 생성 전에 거부한다. 저장소에 도달하지 않은 확정 입력 오류는 예약 바이트를 해제한다. 만료·취소 후 보관량은 검증된 물리 삭제가 있을 때만 줄일 수 있다. 같은 키의 재시도는 추가 바이트를 예약하거나 첨부를 만들지 않는다. 동일 회원의 같은 MIME과 해시가 유효한 미연결 첨부나 기존 제보에 연결된 첨부로 남아 있으면 새 키는 409다. 만료된 미연결 첨부는 새 키로 다시 제출할 수 있다. 회원을 가로질러서는 중복을 조회하지 않는다.

## UGC-02 인수인계

승인 시 관리자 계정·현재 리비전·기대 버전·상품 중복·양조장 및 브랜드 관계·유효 권리를 재확인한다. 사용자 동의에서 공개 권리로 자동 승격하지 않는다. V10의 DELIVERY 준비는 DB 승인 트랜잭션 밖에서 진행하고 승인 트랜잭션에서 공개 연결과 프로필을 갱신한다. 공개 쓰기에는 V9 잠금 계약, 구버전 Tools 쓰기 차단, 기존 추천 SQL의 양조장 조인 및 빈 조건 결함 해결이 필요하다. 관리자 임대·결정·승격, Frontend, 실제 R2 및 삭제는 별도 작업이다.

## 검증 증거

격리 MySQL의 SAKE 10,337건을 조회해 단일 숫자로 해석 가능한 시드 범위를 확인했다. 니혼슈도 -154~66, 산도 0.5~80, 정미율 1~100, 알코올 5~46이었다. 알코올 6,086건과 니혼슈도 304건은 단순 숫자 정규식에 일치하지 않았다. 제출의 범위·NULL 계약을 유지하고 시드의 텍스트는 이전하지 않았다.

로컬 검증 코드 f68a51b584dcbd349b10b4c13c85df67026e2db2에서 JVM 176개를 실행했다. 공통 11, Tools 기본 69·MySQL 17, Backend 기본 28·MySQL 51에서 실패·오류·스킵은 0개였다. Tools Wrapper의 :image-core:cleanTest cleanTest test, python3 scripts/test_auth_integration.py --e2e의 서버 단계, Backend Wrapper bootJar를 실행했다. V1–V11 초기화, V10 업그레이드, MySQL 1205 재시도, 소유권·상태·입력·동시 쿼터, JPEG/PNG·만료·권리 철회·저장 손상, PUT 뒤 DB 반영 실패 및 동일 첨부 재시도를 확인했다. 최종 Chromium 및 Docker 결과는 PR의 검사와 증거를 참조한다.

초기 독립 QA의 결과와 후속 수정 증거는 [PR #16 QA 후속](UGC-01_QA_PR16.md)을 참조한다. 실제 R2 및 운영 환경은 미검증이다.
