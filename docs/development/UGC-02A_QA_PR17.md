# UGC-02A PR #17 독립 QA 후속 수정

기준 main은 `63a8d1de1bd82403953efb5fb88eada58f5cf303`이다. 사용자가 제공한 Antigravity의 **CONDITIONAL PASS**는 `3e90794bdbf63e3b249613de56b36045ef8d715b`에 해당한다. 공식 JVM 테스트 200/200, 독립 프로브 145/145, 실측 관찰 11건이었다. P0/P1은 0건, P2는 1건, P3는 4건이었다. 관리자의 실시간 역할 검증, 정상 잠금 순서에서 양방향 역할 철회 경쟁, 결정 멱등성과 감사 롤백, 사용자 동의, 미리보기 TOCTOU 10종, 공개 카탈로그 격리가 독립 검증을 통과했다. 이 판정은 후속 수정 HEAD에 승계되지 않는다. 후속 HEAD의 독립 QA는 대기 중이다. [이전 CI](https://github.com/ki-sd/SakeZuki/actions/runs/46127942548) · [현재 PR 검사](https://github.com/ki-sd/SakeZuki/pull/17/checks)

## P2-1 관리자 검색의 JSON null

기존 식에서 JSON null의 `JSON_UNQUOTE`는 문자열 `null`을 반환한다. 따라서 `null`, `nu`, `ul`, `ll`, `n`, `u`, `l` 검색이 빈 가나를 매칭했다. 새 식은 `JSON_TYPE(JSON_EXTRACT(...)) = 'STRING'`인 경우에만 `JSON_UNQUOTE`를 적용한다. JSON null, 누락 경로 및 문자열 외 값은 SQL NULL이 된다. 목록의 JA·KO 표시와 세 이름 검색에 동일한 식을 적용한다. 실제 문자열 `null`과 이름 전체 길이, `utf8mb4_bin`, 기존 LIKE 이스케이프와 바인딩, 상태·페이지·보조 ID 정렬을 보존한다.

격리 MySQL 8.4.13 실측에서 기본 `JSON_VALUE`는 `VARCHAR(512)`와 `utf8mb4_0900_bin`을 사용하며 1,000자 입력에 SQL NULL을 반환했다. `RETURNING CHAR(1000) CHARACTER SET utf8mb4`에 `COLLATE utf8mb4_bin`을 적용한 식과 채택한 CASE 식은 모두 500자·1,000자 문자열을 보존했다. CASE는 길이 상수를 반복하지 않고 기존 `JSON_UNQUOTE`의 collation을 유지한다. 대소문자와 히라가나·가타카나의 바이너리 부분 일치는 HTTP로 검증한다.

## P3-1과 P3-3 경쟁 후보와 비교 기준

후보 진입 조건은 기존 이름의 동등·접두 비교다. 후보가 된 행에 한해 이름 동등 일치 수, 양조장, 브랜드, 특정명칭, 내부 번호 순으로 정렬한다. 기존 양조장·브랜드는 내부 번호, 신규 제안은 대응하는 일본어 또는 한국어 열의 MySQL collation 동등 비교를 사용한다. 같은 SQL 판정으로 정렬과 `matchReasons` 및 `reviewDifferences`를 구성한다. 제안이 없는 분기에서는 관계 번호만으로 정렬하므로 양조장·브랜드 표시 조인을 상위 후보 이후에 수행할 수 있다.

`EXACT_*`는 해당 MySQL 열의 동등 비교로 판정한다. `utf8mb4_0900_ai_ci`에서 `でん`과 `テン`은 동등하고, 양조장 이름의 후행 공백은 동등하지 않았다. 접두 검색은 제출 이름의 양끝 공백을 `strip()`한 값에만 적용하며 두 코드포인트 이상을 요구한다. 원본과 카탈로그 이름은 보존된다. 브랜드 없는 상품은 제안 브랜드와 일치하지 않으며 미제출 특정명칭은 평가하지 않는다. 비교 결과는 검수 보조 신호다. 변형·공백의 최종 식별 규칙과 중복 승인 방지는 UGC-02B에서 검토한다.

### 시드 성능과 정확도

격리 MySQL 8.4.13의 시드 10,337건에서 `でん` 접두 193건과 정확 이름 조건을 합쳐 194건이 필터를 통과했다. 구·신 SQL을 단일 JDBC 연결에서 교대로 실행했다. 조건별·SQL별 준비 실행 10회와 측정 31회의 중앙값을 비교했다. 루트 종료는 별도 EXPLAIN ANALYZE의 대표 1회 값이다.

| 입력 | JDBC SELECT 중앙값 이전 → 수정 | EXPLAIN 루트 종료 이전 → 수정 | 표시용 조인당 횟수 이전 → 수정 |
| --- | ---: | ---: | ---: |
| 내부 번호 | 6.312 → 6.170 ms | 5.10 → 5.50 ms | 20 → 20 |
| 신규 양조장·브랜드 제안 | 6.684 → 6.501 ms | 4.79 → 6.44 ms | 20 → 194 |

두 경로의 카탈로그 스캔은 1회, 응답은 20건으로 제한됐으며 전체 카탈로그 구체화는 없었다. 신규 제안은 필터를 통과한 행에서만 관계 PK 조인을 수행한다. 내부 번호 입력의 상위 20건과 순서가 일치했다. 같은 양조장·브랜드를 이름으로 제안하면 종전 상위 20건에 없던 상품 `9394`가 새 상위 20건에 포함됐다. 독립 QA가 종전 HEAD에서 기록한 루트 12.3 ms와 JDBC 중앙값 25.39 ms는 별도 환경의 관측값이다. 운영 부하 측정은 미실행이다.

## P3-2 외부 출처 상한

후보 전체의 외부 키를 기존 순서인 상품 번호, 출처 시스템, 외부 키 순으로 한 번에 최대 201건 읽는다. 첫 200건의 `sourceIdentifiers` 항목 구조를 유지하고 201번째 행이 있으면 최상위 `sourcesTruncated`를 true로 반환한다. 절단된 응답의 빈 배열은 출처 부재를 확정하는 근거로 쓰지 않는다. 후보가 없으면 출처 SQL을 실행하지 않고 절단 여부는 false다.

## P3-4 운영 잠금 및 승인 인수인계

정상 순서는 `MEMBER → MEMBER_AUTHORITY → CATALOG_SUBMISSION`이다. 이전 독립 QA에서 정상 순서의 역할 철회 및 검수는 양방향으로 직렬화됐다. 운영 작업이 `MEMBER_AUTHORITY`를 먼저 삭제하고 `MEMBER`를 잠갔을 때에는 MySQL 1213과 HTTP 500이 재현됐다. 운영 권한 변경은 회원 행을 먼저 잠근다. UGC-02B/C는 V9 `GUIDE_CATALOG_WRITE_MUTEX`까지 포함한 공통 잠금 순서를 정의하고, 1213·1205에서 전체 롤백을 확인한 후 안전한 재시도 범위를 정한다. 커밋 결과가 불확실하면 영속 결과를 먼저 확인한다.

## 회귀 검증과 독립 QA 인계

실제 HTTP와 격리 MySQL로 관리자 검색의 JSON null·누락·실제 문자열·길이 경계·이스케이프·페이지, 제안 관계의 후보 순위와 비교 기준, 출처 0/199/200/201건 및 단일 후보 205건과 뒤 후보의 출처 절단을 추가 검증한다. 로컬 명령은 Tools의 `bash ./gradlew --no-daemon :image-core:cleanTest cleanTest test`, Backend의 `bash ./gradlew --no-daemon bootJar`, 저장소 루트의 `python3 scripts/test_auth_integration.py --e2e`다. JVM 203건은 image-core 11, Tools 기본 69·MySQL 17, Backend 기본 28·MySQL 78건이다. 기존 200건과 새 HTTP 통합 테스트 3건을 포함하며 실패·오류·스킵은 각각 0건이다. Chromium 11건과 bootJar도 통과했다. 수정 diff에서 V1–V12의 변경은 0건이다. CI의 Frontend와 Linux Docker 검증 결과는 최종 HEAD와 함께 PR에 기록한다.

최신 HEAD에서는 검색 정렬과 페이지, 대표 후보 계획과 실행 비용, 절단 응답 해석에 집중해 독립 QA를 진행한다. 운영 접근 및 실제 R2 호출은 검증에 사용하지 않았다. [PR 검사와 실행 SHA](https://github.com/ki-sd/SakeZuki/pull/17/checks)
