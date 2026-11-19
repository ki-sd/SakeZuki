# 공개 UI·무료 예시·중복 추천 안내 개발 기록

## 시작 상태

- main `0d9cd987`을 확인했다. PR #2는 미병합이며 head는 `6458477d874212c1ba7018ee190ceffb1d9d80c0`이었다.
- 깨끗한 PR #2 브랜치에서 `feat/public-examples-history-ux` 후속 브랜치를 만들었다. 병합 순서는 PR #2 → 후속 PR이며, 두 번째 PR의 base는 PR #2 브랜치다. PR #2 병합 후 main을 대상으로 재지정하고 재검증한다.
- 기존 JWT·Refresh·본인확인·예약/확정/환불·스냅샷과 V1-V5는 유지했다. 회원·사케·운영 DB 및 볼륨은 초기화하지 않았다.
- 기본 Next 시작 페이지와 별도 결과 표시 로직이 있었고, 중복 조건 안내와 공개 예시 API는 없었다. 마이페이지는 기록의 입력·일자·만료·상세 이동을 보강할 필요가 있었다.
- 기존 개발 문서와 Notion 당일 기록을 읽었다. 과거 통과 결과는 이번 실행 결과로 계산하지 않는다.

## 구현과 보안 경계

랜딩은 기존 stone 색상·공통 클래스를 사용한다. 소개·주요 진입·양방향 페어링·현재 이용 범위·무료 예시를 제공하며 API가 중단돼도 소개와 검색 링크는 렌더링된다. 메타데이터·본문 건너뛰기·제목 계층과 모바일 줄바꿈을 적용했다. 개인 화면은 검색 수집을 막는다.

공개 예시 자료는 `backend/src/main/resources/examples/recommendations.json`이다. 저장소에는 실제 공용 캐시 응답이 없으므로 임의의 AI 결과를 만들어 내지 않고, 수동 작성한 편집 예시임을 UI와 API에 명시했다. 제품 번호·일본어·한국어 명칭은 V2 시드와 자동 대조한다. 음식 두 사례와 사케 두 사례를 제공한다. 실제 AI 품질 평가를 대신하지 않는다. 운영 캐시를 공개하려면 별도 개인정보 검토 후 공개 전용 DTO에 옮겨야 한다.

| API | 접근·동작 |
| --- | --- |
| `GET /api/examples/recommendations` | 비회원 고정 예시 목록. Gemini·과금·개인 기록 의존성 없음 |
| `GET /api/examples/recommendations/{slug}` | 명시된 slug만 조회. 임의 회원 기록 ID는 404 |
| `POST /api/user/recommendations/match` | Bearer 인증. `{kind,food}` 또는 `{kind,sakeNo}`. 본인 소유의 최근 완료·미만료·스냅샷 보유 기록만 반환. 차감 없음 |
| `GET /api/user/recommendations/{id}` | 기존 소유권·만료 검사 및 JSON 유지. 완료일·만료일·유형 헤더 추가. 차감 없음 |
| `POST /api/recommend/sake`, `POST /api/recommend/food` | 기존 본인확인·크레딧·멱등성 검사와 트랜잭션 유지 |

DB 구조 변경은 없다. 기존 과금의 SHA-256(kind + 줄바꿈 + 정규화 입력)을 공통 `RecommendationCondition`에 모았다. 음식은 기존 Java trim만 적용하며 내부 공백·대소문자·유의어를 합치지 않는다. 더 공격적인 정규화는 서로 다른 요청의 멱등성 충돌과 기존 기록 누락을 만들 수 있으므로 적용하지 않았다. 사케는 양의 sakeNo로 비교한다. 동일 초에 완료된 요청은 COMMIT 원장 순서로 최신을 고른다.

프론트는 중복 확인이 실패하면 과금하지 않는다. 일치 기록은 무료 상세 링크와 명시적 새 요청 버튼을 제공하며 캐시 결과가 같을 수 있음을 알린다. 응답이 불확실한 기존 요청 키가 있으면 중복 안내보다 같은 키의 재확인을 우선한다. 이를 통해 잔액이 0이어도 이미 확정된 요청을 재확인할 수 있다. 더블클릭은 동기 ref와 기존 in-flight Promise로 합치며 최종 정합성은 서버가 보장한다.

공통 `PairingResult`는 표시만 담당한다. 공개 예시·개인 스냅샷·실제 추천이 같은 표시 컴포넌트를 사용하지만 API 호출과 과금 경계는 공유하지 않는다. 개인 결과 query key에는 회원 번호를 넣고 상세 결과의 화면 이탈 캐시를 제거한다. 본인확인 미연동은 준비 중 안내와 무료 예시 링크로 처리하며 운영용 우회나 PG 충전 버튼을 만들지 않았다.

## 검증 실행

로컬 Windows에서 `npm run test:auth` 최종 16개 통과, `npx tsc --noEmit` 통과, ESLint 오류 0·기존 경고 4개를 확인했다. 로컬 `npm run build`는 Turbopack의 PostCSS 자식 프로세스 연결에 실패했고, `gradlew.bat test --offline --no-daemon`은 daemon loopback 연결 실패로 실행되지 않았다. 이를 코드 테스트 통과로 계산하지 않는다.

Linux GitHub Actions에서 최종 애플리케이션 코드 `9d9ef64c1e86b979ea87209c1f7e46d31a4fc132`를 재검증했다. [전체 실행 44547987700](https://github.com/ki-sd/SakeZuki/actions/runs/44547987700)은 성공했다. 초기 [실행 44545936043](https://github.com/ki-sd/SakeZuki/actions/runs/44545936043)의 성공 결과와 구분하며 아래는 최종 실행의 실제 집계다. 이후 커밋은 검증 결과를 적는 문서만 변경한다.

| 검증 | 최종 결과 |
| --- | --- |
| Java/Spring 일반 테스트 | 28개 통과, 실패·오류·skip 0 |
| 분리 MySQL 8.4 통합 테스트 | 24개 통과, 실패·오류·skip 0 |
| 프론트 인증·크레딧·추천 흐름 단위 | 16개 통과, skip 0 |
| Chromium E2E | 9개 통과, skip 0 |
| TypeScript·프로덕션 빌드 | 통과 |
| ESLint | 오류 0, 기존 경고 4 |
| npm audit --audit-level=low | 보고된 취약점 0 |
| Linux Docker backend/frontend 이미지 빌드 | 통과 |
| 실제 컨테이너 HTTP·readiness·비회원 예시·401/403 | 통과 |
| 실제 컨테이너 HTTPS·Secure/HttpOnly 쿠키·OAuth 시작/실패·308 | 통과 |
| Flyway V1-V5 및 database/tools diff | 변경 없음 |

Docker HTTPS 검증은 테스트 전용 인증서를 명시적으로 신뢰해 수행했다. 실제 공인 도메인 인증서, Google 실계정 로그인 성공, 본인확인 사업자, 결제 및 OCI 배포 성공을 의미하지 않는다. 브라우저의 전화번호 입력·예시 장애/빈 결과 테스트에만 응답 stub을 사용하며, 크레딧과 중복 안내는 실제 분리 MySQL·HTTP 경로를 사용한다. Gemini 생성은 synthetic 캐시 또는 Mock으로 분리했다.

```bash
cd frontend
npm ci
npx playwright install --with-deps chromium
npm run test:auth
npx tsc --noEmit
npm run lint
npm audit --audit-level=low
npm run build
cd ..
python3 scripts/test_auth_integration.py --e2e
python3 scripts/test_public_containers.py
```

인증 테스트 실행기는 `.env`를 가져오지 않고 별도 tmpfs MySQL·난수 자격증명·합성 캐시를 사용한다. Linux wrapper 실행 비트 문제와 E2E 합성 음식 환경변수 누락도 수정해 기존 크레딧 테스트가 skip되지 않게 했다. Docker 검증도 고유 프로젝트·고유 컨테이너·임시 포트·tmpfs DB만 사용한다. 셀프서명 인증서는 테스트 trust store에만 넣으며 운영 TLS 검증을 비활성화하지 않는다.

추가 테스트는 공개 GET의 비회원 접근, 개인 정보 필드 부재, Gemini·원장 무호출, 시드 실재 제품 일치, 기존 입력 hash 보존, 소유자/만료/상태/최신 순서별 중복 조회, 무료 상세와 명시적 새 요청, 응답 유실 재시도, 모바일 장애·빈 상태·키보드 이동을 다룬다. 테스트 실패를 삭제하거나 제외하지 않는다. 추천 화면 공통화 뒤에도 기존 양조장·지역·정미보합·도수·니혼슈도와 선택 제품 미리보기를 보존했다. 개인 결과의 제품 수치는 현재 DB가 아니라 당시 스냅샷에서 표시한다.

중간 CI 실행은 후속 변경 push로 자동 취소된 것이며 성공 수에 포함하지 않는다. Backend 런타임 의존성은 PR #2와 같고 Maven OSV 재조회는 이번 후속 작업에서 수행하지 않았다. npm 보안 검사는 Linux CI에서 별도로 다시 실행했다.

## 외부 설정과 남은 검증

실제 PASS 사업자·Google 실계정·PG·OCI 서버에는 접근하거나 배포하지 않았다. 공개 도메인·신뢰 인증서·Google 승인 redirect·약관·사업자 sandbox 검증·무료 지급 식별 및 거래 보존 정책은 사용자가 확정해야 한다. Linux CI는 OCI A1 arm64 호환성과 실제 서버의 DNS·방화벽·인증서 갱신을 대신하지 않는다. 수동 편집 예시를 실제 AI 생성 결과로 홍보해서는 안 된다.

세부 배포·백업·복구·점검은 [OCI 공개 배포 준비](oci-public-deployment.md)를 따른다. 기존 MySQL 프로젝트 이름과 영속 볼륨을 유지하며, 만료 스냅샷 정리와 별개로 크레딧 원장을 보존한다.

## Git 작업

- [Notion 개발일지](https://app.notion.com/p/41df3a27c74e81dfb955f5fc51b51caf): 선행 기록을 보존하고 같은 개발 기록 아래 후속 날짜별 페이지를 추가했다.
- [PR #3](https://github.com/ki-sd/SakeZuki/pull/3): PR #2 브랜치를 대상으로 한 후속 PR. main 병합과 운영 배포는 수행하지 않았다.
- [258cf2c](https://github.com/ki-sd/SakeZuki/commit/258cf2c4bdceeda7f0f33b3cca149e5c0236e567): 공개 랜딩·편집 예시·개인 기록 중복 안내 구현.
- [0711b4c](https://github.com/ki-sd/SakeZuki/commit/0711b4c93967636f85dbc9d71c38ce87fce9a75e): 독립 E2E·배포 검증·운영 문서 보강.
- [cb01945](https://github.com/ki-sd/SakeZuki/commit/cb019458693ab8b2ebe9775c82e6b382c4319f18): 기존 제품 정보 표시·모바일 긴 입력 보존.
- [9d9ef64](https://github.com/ki-sd/SakeZuki/commit/9d9ef64c1e86b979ea87209c1f7e46d31a4fc132): 변경 파일의 줄바꿈만 정리. PR diff에서 실제 변경만 보이도록 정돈.
