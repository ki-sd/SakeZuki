# 병렬 작업 범위

2026-11-27 KST 기록. GitHub의 열린 Issue/PR 및 실제 브랜치가 상태의 우선 근거다. 이 표는 조율용 요약이며 실시간 잠금 기능을 제공하지 않는다. 담당은 실제 배정 시에만 바꾼다.

| ID / 작업 | 상태 / 담당 | 기준 / 브랜치 / Issue·PR | 수정 영역 / 선행 / 충돌 |
| --- | --- | --- | --- |
| DEV-DOC / 개발 이관 문서 | DONE / 병합 완료 | main / [PR #5](https://github.com/ki-sd/SakeZuki/pull/5) | 공통 문서와 PR 템플릿 / 없음 / 다른 작업의 공통 문서 편집과 조율 |
| DEV-01 / Cloud 기준선 | PARTIALLY READY | main / chore/dev-01-cloud-baseline | [환경 확인·검증](CLOUD_SETUP.md#dev-01-cloud-기준선) / CI 변경이 필요하면 별도 조율 |
| IMG-01 / 장애·출처 분석 | BLOCKED / 원본 조회 대기 | main / docs/img-01-image-source-analysis | [자료 조사·표본](IMG-01_IMAGE_SOURCES.md) / 승인된 접속 환경 / 운영 데이터 및 소스 수정 없음 |
| IMG-03 / 이미지 저장 | PLANNED / 미배정 | main / 미생성 / 미등록 | backend·tools·Flyway / 합성 개발 가능, 실데이터는 IMG-01/02 / DTO·환경변수·Flyway 예약 공유 |
| UI-01 / 탐색 UI | PLANNED / 미배정 | main / 미생성 / 미등록 | frontend / 합성 이미지 사용 / 이미지 DTO·공통 컴포넌트·lockfile 조율 |

작업 시작 시 직접 수정 영역과 공용 자원(AGENTS·Flyway 버전·DTO·인증/크레딧·환경변수·lockfile·CI·Compose·Jenkins)을 Issue/PR에서 조율한다. 겹치면 인터페이스를 합의하거나 순서를 정한다. 브랜치 하나에 주 개발자 한 명만 쓰며, 빈번한 진행 보고를 위해 이 문서를 매번 수정할 필요는 없다.
