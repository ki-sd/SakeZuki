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

로컬 Windows에서 `npm run test:auth` 15개 통과, `npx tsc --noEmit` 통과, ESLint 오류 0·기존 경고 4개를 확인했다. 로컬 `npm run build`는 Turbopack의 PostCSS 자식 프로세스 연결에 실패했고, `gradlew.bat test --offline --no-daemon`은 daemon loopback 연결 실패로 실행되지 않았다. 이를 코드 테스트 통과로 계산하지 않는다.

Linux GitHub Actions에 동일 코드의 프로덕션 빌드·일반 테스트·분리 MySQL 통합 테스트·Chromium·Docker HTTP/HTTPS 실기동을 추가했다. 실행 결과는 아래 최종 검증 항목에 기록한다.

```bash
cd frontend
npm ci
npx playwright install --with-deps chromium
npm run test:auth
npx tsc --noEmit
npm run lint
npm run build
cd ..
python3 scripts/test_auth_integration.py --e2e
python3 scripts/test_public_containers.py
```

인증 테스트 실행기는 `.env`를 가져오지 않고 별도 tmpfs MySQL·난수 자격증명·합성 캐시를 사용한다. Linux wrapper 실행 비트 문제와 E2E 합성 음식 환경변수 누락도 수정해 기존 크레딧 테스트가 skip되지 않게 했다. Docker 검증도 고유 프로젝트·고유 컨테이너·임시 포트·tmpfs DB만 사용한다. 셀프서명 인증서는 테스트 trust store에만 넣으며 운영 TLS 검증을 비활성화하지 않는다.

추가 테스트는 공개 GET의 비회원 접근, 개인 정보 필드 부재, Gemini·원장 무호출, 시드 실재 제품 일치, 기존 입력 hash 보존, 소유자/만료/상태/최신 순서별 중복 조회, 무료 상세와 명시적 새 요청, 응답 유실 재시도, 모바일 장애·빈 상태·키보드 이동을 다룬다. 테스트 실패를 삭제하거나 제외하지 않는다.

## 외부 설정과 남은 검증

실제 PASS 사업자·Google 실계정·PG·OCI 서버에는 접근하거나 배포하지 않았다. 공개 도메인·신뢰 인증서·Google 승인 redirect·약관·사업자 sandbox 검증·무료 지급 식별 및 거래 보존 정책은 사용자가 확정해야 한다. Linux CI는 OCI A1 arm64 호환성과 실제 서버의 DNS·방화벽·인증서 갱신을 대신하지 않는다. 수동 편집 예시를 실제 AI 생성 결과로 홍보해서는 안 된다.

세부 배포·백업·복구·점검은 [OCI 공개 배포 준비](oci-public-deployment.md)를 따른다. 기존 MySQL 프로젝트 이름과 영속 볼륨을 유지하며, 만료 스냅샷 정리와 별개로 크레딧 원장을 보존한다.
