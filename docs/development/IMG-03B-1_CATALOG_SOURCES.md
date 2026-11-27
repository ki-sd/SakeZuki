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
