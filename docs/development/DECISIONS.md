# 결정 기록

기존 구현은 main과 연결된 근거를 기준으로 기록했다. 확인 날짜는 2026-11-27 KST이며 과거 결정일이 확인된 경우 관련 PR에서 확인한다. Accepted는 구현 또는 사용자 확정, Proposed는 검토 중이다. 대체 시 원래 기록을 Superseded로 남기고 후속 결정 ID를 연결한다.

| ID | 상태 | 배경·결정·대안과 이유 | 영향·근거·재검토 조건 |
| --- | --- | --- | --- |
| DEC-001 | Accepted | Java 21/Spring Boot, MyBatis/MySQL, Flyway를 현행 기준으로 사용한다. 이관 단계에서 다른 ORM·DB를 도입할 필요가 없다. | backend·DB. [PR #2](https://github.com/ki-sd/SakeZuki/pull/2). 스키마 추가는 새 Flyway 버전으로 검토 |
| DEC-002 | Accepted | DB 후보 → LLM 재랭킹 및 공용 캐시를 유지한다. 개인 기록은 조회 시점마다 다시 생성하는 대신 당시 결과를 스냅샷으로 보관한다. | 추천·기록. [공개 UI 검증](../public-ui-validation-2026-11-19.md). 추천 품질이나 캐시 계약 변경 시 검토 |
| DEC-003 | Accepted | 본인·성인확인 후 최초 10크레딧. 새 추천은 캐시 여부와 무관하게 1크레딧, 같은 멱등성 키 재시도 및 30일 기록 재열람은 추가 차감 없음. 실패 예약은 복구. | 인증·원장. [PR #2](https://github.com/ki-sd/SakeZuki/pull/2). 정책 변경은 사용자 결정과 별도 검증 필요 |
| DEC-004 | Accepted | 기존 OCI ARM64를 운영 대상으로 유지하고 공개 도메인·HTTPS·OAuth는 별도 단계로 진행한다. | 인프라. [운영 상태](PROJECT_STATE.md). 공개 전 ARM64/운영 확인 |
| DEC-005 | Proposed | 원본 외부 URL 장애 해소를 위해 R2와 안정적인 상품 매핑을 검토한다. 원본 URL 교체만 하는 대안과 권리·비용·누락/재실행 대응을 비교한다. | 저장·마이그레이션. [IMG 계획](ROADMAP.md). 출처·재배포 권한과 업로드 승인 후 실제 데이터 처리 |
| DEC-006 | Proposed | 탐색 중심 홈으로 개편하는 방향. 현재 소개 랜딩·공개 예시 유지와 검색 우선 배치의 사용자 흐름을 비교한다. | frontend. [UI 계획](ROADMAP.md). 기능 범위 및 시각 QA 기준 확정 후 구현 |
| DEC-007 | Accepted | GitHub를 공통 개발 기록의 기준으로 두고 Codex Cloud Linux를 기본 개발환경으로 준비한다. Windows와 다른 에이전트는 같은 규칙과 Git 이력을 읽는다. | [설정](CLOUD_SETUP.md) · [인수인계](HANDOFF.md). Cloud 기준선 실패 시 도구/권한을 재검토 |
| DEC-008 | Accepted | 작업별 주 개발자, 별도 브랜치·작업 공간, 증거 SHA를 사용한다. 동시 push와 상시 문서 잠금 방식은 피하고 GitHub PR/Issue로 충돌을 조율한다. | [작업 범위](WORKSTREAMS.md) · [QA](../qa/README.md). 공유 자원 변경이 겹치면 작업 순서 재조정 |
| DEC-009 | Proposed | 제보 리비전·승인 이력을 공개 카탈로그에서 분리하고, 내부 PK를 유지하면서 외부 ID를 출처별 연결로 옮긴다. 공개 전 승인 리비전과 상품 식별값의 중복을 검증한다. | [카탈로그 설계](UGC_CATALOG_ARCHITECTURE.md) · 마이그레이션과 도구 호환 검증 후 확정 |
| DEC-010 | Proposed | IMG-03C와 UGC가 이미지 자산·권리·검증된 객체 계약을 공유한다. 객체 저장과 DB 공개 전환은 개별 단계로 기록하고 활성 자산만 전달한다. | [카탈로그 설계](UGC_CATALOG_ARCHITECTURE.md) · [V10 구현 및 초기 제공 정책](IMG-03B-3_IMAGE_ASSETS.md) |
| DEC-011 | Proposed | 내부 PK를 유지하고 대상별 실제 FK 출처 테이블로 전환한다. 백필 검증 이후 레거시 ID에 NULL을 허용하며 신규 Guide 쓰기는 같은 트랜잭션에서 두 표현을 기록한다. | [IMG-03B-1 전환](IMG-03B-1_CATALOG_SOURCES.md) · 구버전 쓰기 작업을 중지한 뒤 전환 |
| DEC-012 | Accepted | `backend/image-core`를 독립 Java 21 라이브러리로 두고 Backend·Tools의 Gradle composite build로 공유한다. 외부 다운로드·권리 정책은 Tools에서 유지한다. | [공통 모듈](IMG-03B-2_IMAGE_CORE.md) · 기존 Docker 컨텍스트 및 Wrapper 보존 |
