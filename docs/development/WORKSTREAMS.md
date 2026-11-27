# 병렬 작업 범위

2026-11-27 KST 기록. GitHub의 열린 Issue/PR 및 실제 브랜치가 상태의 우선 근거다. 이 표는 조율용 요약이며 실시간 잠금 기능을 제공하지 않는다. 담당은 실제 배정 시에만 바꾼다.

| ID / 작업 | 상태 / 담당 | 기준 / 브랜치 / Issue·PR | 수정 영역 / 선행 / 충돌 |
| --- | --- | --- | --- |
| DEV-DOC / 개발 이관 문서 | DONE / 병합 완료 | main / [PR #5](https://github.com/ki-sd/SakeZuki/pull/5) | 공통 문서와 PR 템플릿 / 없음 / 다른 작업의 공통 문서 편집과 조율 |
| DEV-01 / Cloud 기준선 | PARTIALLY READY | main / chore/dev-01-cloud-baseline | [환경 확인·검증](CLOUD_SETUP.md#dev-01-cloud-기준선) / CI 변경이 필요하면 별도 조율 |
| IMG-01 / 장애·출처 분석 | BLOCKED / 원본 조회 대기 | main / docs/img-01-image-source-analysis | [자료 조사·표본](IMG-01_IMAGE_SOURCES.md) / 승인된 접속 환경 / 운영 데이터 및 소스 수정 없음 |
| IMG-02 / 상품 매핑 | 구현 병합 / 실데이터 확인 대기 | main | Tools·개발 문서 / [PR #8](https://github.com/ki-sd/SakeZuki/pull/8) / [dry-run 계약](IMG-02_PRODUCT_MAPPING.md) |
| IMG-03A / 검증·변환·저장 | DONE / PR #9 병합 | main | Tools·CI / [저장 계약](IMG-03A_IMAGE_STORAGE.md) / 권리 및 운영 적용 별도 |
| IMG-03A-IT / R2 실제 검증 | DONE / Windows 핵심 검증 통과 | main / PR #10 | Tools / [검증 기록](IMG-03A_IT_R2_SMOKE.md) / 읽기 전용 미실행·정리 미확인 |
| IMG-03B-1 / 카탈로그 출처·호환성 | IN_REVIEW | main / feat/img-03b-1-catalog-sources | Backend·Tools·Flyway / [전환 기록](IMG-03B-1_CATALOG_SOURCES.md) |
| IMG-03B-2 / 공통 이미지 처리 모듈 | PLANNED | IMG-03B-1 | Backend·Tools·Gradle·Docker |
| IMG-03B-3 / 이미지 자산·Backend 이미지 제공 | PLANNED | IMG-03B-2 | Backend·Flyway / 자산과 공개 제공 |
| IMG-03C / 실행기·복구·롤백 | PLANNED | IMG-03B-3 | Tools / UGC와 공통 이미지 자산 / 별도 운영 승인 및 SLF4J 개선 검토 |
| UGC-01 / 제보 스키마·Backend API | PLANNED | IMG-03B-3 | Backend·Flyway / 제보·리비전·첨부와 소유권 |
| UGC-02 / 관리자 검토·승인 | PLANNED | UGC-01 | Backend·카탈로그·인증 / 공개 트랜잭션·권리·동시성 |
| UGC-03 / 사용자·관리자 화면 | PLANNED | UGC-01/02 | Frontend / 역할, API DTO, 검토·보완 흐름 |
| UI-01 / 탐색 UI | PLANNED / 미배정 | main / 미생성 / 미등록 | frontend / 합성 이미지 사용 / 이미지 DTO·공통 컴포넌트·lockfile 조율 |

IMG-03B 및 UGC 작업은 [공통 설계](UGC_CATALOG_ARCHITECTURE.md)를 기준으로 조율한다.

작업 시작 시 직접 수정 영역과 공용 자원(AGENTS·Flyway 버전·DTO·인증/크레딧·환경변수·lockfile·CI·Compose·Jenkins)을 Issue/PR에서 조율한다. 겹치면 인터페이스를 합의하거나 순서를 정한다. 브랜치 하나에 주 개발자 한 명만 쓰며, 빈번한 진행 보고를 위해 이 문서를 매번 수정할 필요는 없다.
