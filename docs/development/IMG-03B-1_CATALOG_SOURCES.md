# IMG-03B-1 카탈로그 출처와 호환성 전환

2026-11-27 KST · 기준 main `1db9b4d1ece9710f42138b01e062634cfeb58226` · PR #12 병합 확인, 시작 시 열린 PR 0건. [UGC 설계](UGC_CATALOG_ARCHITECTURE.md)

## 기존 경로 전수 조사

| 경로 | 확인 및 처리 |
| --- | --- |
| Flyway와 seed | V1은 세 카탈로그의 레거시 ID에 NOT NULL·UNIQUE를 적용하며, 내부 PK와 FK를 별도로 사용한다. V2가 외부 ID, 이미지 URL과 프로필을 적재한다. V3–V5 인증·추천 기록은 내부 키를 사용한다. 세 테이블의 기존 UNIQUE, PK와 FK는 보존했다. [마이그레이션](../../backend/src/main/resources/db/migration/) |
| MyBatis·DTO | 검색·목록·상세 및 추천 DTO는 내부 번호·양조장/브랜드 FK를 사용한다. 공개 응답에 레거시 ID를 추가하지 않았다. 이미지 URL과 정렬을 유지했다. 브랜드가 없을 때 추천의 양조장 조인이 브랜드를 경유해 정보가 빠지는 문제와, 조건 생략 시 `CASE ELSE`가 생성되는 기존 SQL 문제는 IMG-03B-1 범위 밖이다. [사케 매퍼](../../backend/src/main/resources/mapper/sake/SakeMapper.xml) · [추천 매퍼](../../backend/src/main/resources/mapper/recommend/RecommendMapper.xml) |
| 수집·Repository | 목록/상세 수집기의 ID는 외부 값이다. Main은 세 Repository의 생성 키를 내부 FK로 사용하고 전체 저장을 트랜잭션으로 묶는다. 기존 `ON DUPLICATE KEY UPDATE`와 `LAST_INSERT_ID(no)`를 유지한다. 가나·한국어 등 이전 동작을 바꾸지 않았고 외부 식별자가 없는 입력의 Guide upsert는 거부한다. [수집 진입점](../../tools/src/main/java/com/sakezuki/tools/Main.java) · [Repository](../../tools/src/main/java/com/sakezuki/tools/repository/) |
| 이미지 조회·갱신 | `image_checked=N`에 출처 일치와 non-NULL을 추가했다. JDBC 숫자는 `getObject(..., Long.class)`로 읽어 SQL NULL의 0 변환을 피한다. 갱신 조건에도 동일 출처 경계를 적용한다. 예전 `null → image_checked=Y` 처리 모델은 그대로이며 IMG-03A의 상태 모델과 자동 변환하지 않는다. [갱신기](../../tools/src/main/java/com/sakezuki/tools/SakeImageUpdater.java) |
| 번역·데이터 보완 | 브랜드와 사케 이름, 주미·효모의 선택·건수·갱신·원문 정리 SQL은 모두 기존 Guide 연결과 일치하는 행만 사용한다. 구버전의 이름·주미·효모 단위 갱신은 사용자 데이터까지 변경할 수 있다. [번역 저장소](../../tools/src/main/java/com/sakezuki/tools/repository/TranslationRepository.java) |
| 프로필·추천 기록 | 프로필 생성기는 `SAKE.no`와 범위 수치만 사용한다. Guide 수집 경계에 넣지 않으며, 승인 시 사용자 상품도 별도로 프로필 한 행이 필요하다. 후보 SQL의 INNER JOIN은 유지했고 NULL 수치를 보충하지 않는다. 추천 캐시·유료 기록·크레딧·멱등성 구현은 변경하지 않았다. [프로필 생성기](../../tools/src/main/java/com/sakezuki/tools/recommend/SakeRecommendProfileGenerator.java) |
| 매핑·이미지 승인 계약 | IMG-02 dry-run의 `no`와 `sourceId`는 내부 번호와 기존 Guide ID로 구분된다. 로컬 fixture와 IMG-03A의 매핑·권리 검증은 호환 계약을 유지한다. [IMG-02](IMG-02_PRODUCT_MAPPING.md) · [IMG-03A](IMG-03A_IMAGE_STORAGE.md) |
| 환경·운영 스크립트·테스트 | Compose/OCI 빌드 및 격리 실행 스크립트는 스키마를 직접 수정하는 레거시 ID 명령을 갖고 있지 않다. 기존 seed와 인증 fixture는 테스트용 레거시 ID를 명시하므로 유지했다. 신규 fixture에는 NULL과 명시적 제보 출처를 사용했다. 격리 실행기에 Flyway 전환/복구 및 Tools 저장소 검증을 추가했다. [격리 실행기](../../scripts/test_auth_integration.py) |

## 모델과 전환기 쓰기

`SAKE_SOURCE`, `BREWERY_SOURCE`, `BRAND_SOURCE`는 각 테이블에 실제 FK를 둔다. 제공자 코드는 SAKE_GUIDE, USER_SUBMISSION, ADMIN, BREWERY_OFFICIAL이다. 외부 키가 있는 연결은 테이블마다 `(source_system, external_key)` UNIQUE이고, 비교는 대소문자·악센트를 구분하는 `utf8mb4_0900_bin`을 사용한다. 레거시 VARCHAR upsert는 기존 비교 규칙을 유지한다. Tools는 호출 인수 대신 실제 저장된 레거시 값으로 출처를 기록한다. Guide의 숫자 ID는 10진 문자열로 옮긴다.

MySQL UNIQUE는 NULL을 여럿 허용한다. 외부 키가 없을 때 생성 컬럼 `keyless_slot=1`을 사용해 대상·출처 시스템당 키 없는 연결 한 개만 허용한다. 키가 있으면 슬롯은 NULL이며 여러 외부 키가 한 대상에 연결될 수 있다. Guide 연결에는 외부 키가 필수다. 출처에는 canonical URL, 증거 참조, 관찰 시각 및 검수 상태·시각을 담을 수 있다. backfill은 외부 확인을 의미하지 않으므로 `UNREVIEWED`를 사용하고 URL·시각은 비워 둔다. 제보 및 관리자 결정 이력은 후속 UGC 리비전 모델에 남긴다.

새 Tools는 레거시 upsert와 Guide 연결 확인을 같은 JDBC 트랜잭션에서 수행한다. 호출자가 트랜잭션을 열었으면 savepoint로 현재 저장만 되돌리고 호출자가 커밋한다. 다른 내부 번호가 기존 외부 키를 점유했다면 연결을 재지정하지 않고 저장을 실패시킨다. nullable 호환 컬럼은 Guide 미보유 행에 NULL로 둔다. 운영 쓰기 권한은 전환기 Tools 및 후속 승인 작업에만 부여하고, 레거시 ID·Guide 연결을 개별적으로 수동 수정하지 않는다. 새 스키마는 신규 Tools의 실행 전제다.

## 전환·복구 운영 조건

1. 사전 백업과 복구 준비를 확인하고 카탈로그에 쓰는 구버전 수집기·번역기·이미지 갱신기·배치 실행을 멈춘다. 외부 ID 없는 데이터의 활성화는 구버전 작업 스케줄·접근 권한을 제거하고 새 Tools로 전환한 후 별도 승인한다. 구버전 수집기는 신규 Guide 연결을 만들지 않고 구버전 번역·보완 작업은 NULL 행도 수정할 수 있다. 두 세대를 동시에 운영하지 않는다.
2. V6에서 테이블·인덱스·검사를 생성한다. V7은 기존 키를 backfill하고 출처 수·대상 수·정확히 대응한 수가 일치하는지 DB 제약으로 확인한다. 불일치하면 V8로 진행하지 않는다. V8은 레거시 ID를 nullable로 바꾸고 기존 인덱스·FK를 보존한다.
3. MySQL DDL은 암묵적 커밋을 일으킬 수 있다. 중간 실패 시 `flyway_schema_history`, `SHOW CREATE TABLE` 및 대상·출처 대응을 검토하고 장애 중 쓰기를 금지한다. V6 일부만 적용됐다면 백업 복구를 우선한다. V7 충돌은 원인을 검수해 틀린 연결만 정정한 후, 검증한 백업과 schema 전제 아래 `repair` 후 재시도한다. Flyway repair만으로 DDL/데이터가 복구되지는 않는다. V8 일부 적용 시 각 컬럼의 NULL·collation·UNIQUE와 FK를 대조한다. V8의 동일 nullability 변경은 재실행 가능하지만 상태 확인과 승인 없이 repair를 수행하지 않는다.
4. 재검증 후에만 새로운 쓰기 작업을 열고, 외부 ID 없는 공개 행은 후속 UGC 승인 경로에서 등록한다. NULL 행이 생긴 이후 역방향 NOT NULL 변경이나 구버전 Tools 재활성화에는 별도 데이터·배포 계획이 필요하다.

## 후속 경계

IMG-03B-2가 공유 이미지 처리 모듈, IMG-03B-3이 이미지 자산·Backend 제공 계약을 담당한다. UGC API와 Frontend, 운영 DB·R2 접근, 외부 사이트 크롤링 및 Gemini 호출은 범위 밖이다.

## 격리 검증 기록

검증 변경 기준 `e9164e7673abb34dd76f89995fe44584cf4524a3`. Codex Cloud Linux amd64, Temurin 21, MySQL 8.4.13의 격리 tmpfs 컨테이너를 사용했다.

| 실행 명령 | 테스트 | 결과 |
| --- | --- | --- |
| `cd tools && bash ./gradlew --no-daemon cleanTest test` | Tools 기본 66개 | 통과 |
| `python3 scripts/test_auth_integration.py` | Backend 기본 28개 | 통과 |
| 같은 격리 실행기의 `authIntegrationTest` | Backend·MySQL 28개 | 통과. 초기 설치, V5 업그레이드, V7 충돌 복구와 NULL 외부 ID의 검색·목록·상세·추천 후보 확인 |
| 같은 격리 실행기의 `catalogIntegrationTest` | Tools·MySQL 4개 | 통과. 출처별 고유성·FK·키 없는 제보 중복 방지, 재수집·트랜잭션, 동시 쓰기 가시성, 사용자 행의 이미지·번역·보완 격리 |
| `git diff --check`, 기존 V1–V5 diff 및 문서 링크 확인 | 변경 범위 | 통과 |

사케 10,337건, 양조장 905건, 브랜드 1,525건의 Guide 출처를 확인했다. V5 업그레이드 시 기존 카탈로그·프로필·추천 fixture 행을 비교했다. V7의 잘못된 소유자 충돌을 주입했을 때 검사가 실패하며 V7 DML이 롤백되고 V6 DDL이 남았다. 충돌 행만 격리에서 제거하고 `repair → migrate`로 V8까지 통과했다. 첫 실행에서 발견한 기존 테스트의 V5 고정 건수 단언은 V8에 맞게 갱신했고, 추천 조건을 생략할 때 발생하는 기존 SQL 문제는 후속 과제로 기록했다. 추천 후보 검증은 모든 검색 조건을 제공하되 상품 프로필에는 NULL 수치를 유지했다.

독립 리뷰 및 원격 CI 결과는 PR에서 확인해야 한다. 이 로컬 실행에는 브라우저 E2E와 공개 Docker 검증을 포함하지 않았다.

## PR #13 독립 QA 후속

Antigravity는 `e11a25caaa34f7aa31fa022f8ab8d10cb77b2acd`에 CONDITIONAL PASS를 보고했다. P2 두 건과 P3 세 건을 확인했다. Antigravity는 MySQL 8.4.13에서 직접 검증했으며 Maven Central 429로 자체 JUnit 실행은 완료하지 못했다. 별도로 같은 이전 HEAD의 GitHub Actions `verify`는 성공했다. 아래 회귀 검증 대상은 수정 코드 `64889b2915875da9190bb4fdac3ae61558e728cf`다. [이전 CI](https://github.com/ki-sd/SakeZuki/actions/runs/45989589906)

| 발견 사항 | 해결 |
| --- | --- |
| P2 · V7 재진입 | CHECK 실패 시 TEMPORARY TABLE은 남을 수 있다. 생성 직전에 `DROP TEMPORARY TABLE IF EXISTS`를 추가했다. 같은 물리 연결에서 CONN ID를 대조하며 Flyway `repair → migrate`를 실행했다. V6 DDL은 유지되고 V7의 백필 DML은 실패 시 롤백됐으며 V8은 진행되지 않았다. 연결 교체를 복구 조건으로 삼지 않는다. |
| P2 · 외부 키 소유권, 병합 차단 | 기존 코드는 요청 키를 잃고 upsert 뒤의 저장 키만 검사했다. 새 코드는 레거시 행을 먼저 잠근 뒤 요청 키 또는 레거시 collation상 동등한 범위를 인덱스 `FOR UPDATE`로 현재 읽기한다. 다른 소유자가 하나라도 있으면 저장을 롤백한다. 기존 bin UNIQUE와 저장 키 소유권 검사도 유지한다. |
| P3 · 외부 키 입력 | Java·DB에서 빈 문자열, 경계 Unicode 공백, 제어 문자를 거부한다. Java는 SQL VARCHAR 한도와 잘못된 surrogate도 검사한다. 정상 키를 trim하거나 정규화하지 않는다. 전체 seed의 12,767개 키에는 대문자 0개, 비ASCII 6개, 새 규칙 위반 0개가 있었다. |
| P3 · 통합 결과 수집 | Tools 실행 전 `cleanCatalogIntegrationTest`를 실행하고 생성된 XML을 합산한다. 파일 누락, 0건, 실패·오류·스킵이면 성공할 수 없다. Backend도 같은 집계 함수를 사용한다. 기존 GitHub Actions의 격리 실행기가 이 경로를 호출한다. |
| P3 · 트랜잭션 정리 | 일반 예외와 Java `Error`에 대해 가능한 범위에서 원래 트랜잭션 또는 savepoint로 되돌린다. 정리 실패는 suppressed 예외로 보존하고 연결을 중단한다. 실패한 rollback 뒤 자동 커밋을 복구하지 않는다. |

BREWERY·BRAND에만 레거시 비교용 비고유 생성키를 추가했다. bin 키를 소문자로 합치거나 collation을 변경하거나 추정 별칭을 영구 등록하는 대안은 기존 식별자를 바꿀 수 있어 채택하지 않았다. 일치하는 내부 소유자가 같은 경우 대소문자 변형 재수집은 유지한다. SAKE는 수집기의 정규 10진 ID를 정확히 조회한다. InnoDB의 REPEATABLE READ 또는 SERIALIZABLE을 쓰기 전제로 삼아 미존재 키의 인덱스 범위도 잠근다. READ COMMITTED에서는 쓰기를 거부한다. 독립 원장 쓰기가 먼저 커밋되면 현재 읽기가 충돌을 확인하며, 동일 키에 대한 경쟁에는 bin UNIQUE가 최종 경계를 제공한다. 교착이나 잠금 타임아웃이 발생하면 트랜잭션을 재검토하고 호출자가 재시도해야 한다. 앱 밖의 직접 SQL이 커밋 후 새로운 동등 키 충돌을 만들 수 있어 원장 쓰기 권한은 별도로 제한한다.

### 회귀 증거와 재검증

- 수정 전 소스의 A·B 회귀 테스트에서 모두 거부 예외가 나오지 않아 2건 실패했다. 수정 전 V7의 같은 물리 세션 재실행에서 MySQL 오류 1050을 확인했다.
- 수정 후 `python3 scripts/test_auth_integration.py`는 격리 MySQL 8.4.13에서 Backend 기본 28, Backend·MySQL 28, Tools·MySQL 13개를 통과했다. `cd tools && bash ./gradlew --no-daemon cleanTest test`는 Tools 기본 66개를 통과했다. 실패·오류·스킵은 0이다. XML 검증은 누락·0건·실패·오류·스킵 fixture가 거부되는 것도 확인했다.
- Antigravity에는 PR #13의 새 HEAD에서 동일 세션 재시도, A·B, 악센트·탁점·Unicode 표현, 경쟁 쓰기, 코드의 rollback과 실제 결과를 다시 대조할 수 있도록 이 절차를 인계한다. 새 HEAD의 독립 검증 결과는 아직 없다. 기존 CI와 새 실행은 PR Checks에서 SHA별로 구분한다.
- 기존 추천 SQL의 브랜드 없는 상품 양조장 조인·조건 생략 문제 해소와 구버전 Tools 쓰기 권한 차단은 UGC 운영 활성화 선행 조건으로 남는다. 운영 DB 및 실제 외부 서비스 검증은 이 변경에 포함하지 않았다.
