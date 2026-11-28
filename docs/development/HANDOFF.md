# 새 세션 인수인계

저장소는 `ki-sd/SakeZuki`, 기준은 원격 `main`이다. [공통 규칙](../../AGENTS.md)의 시작 순서에 따라 필요한 문서를 읽고, 실제 브랜치·HEAD·작업 트리·원격 PR을 확인한다.

- 기록 기준 main은 `63a8d1de1bd82403953efb5fb88eada58f5cf303`이며 UGC-01 PR #16이 병합됐다. 세션 시작 시 원격 main과 열린 PR을 다시 확인한다. [PR #16](https://github.com/ki-sd/SakeZuki/pull/16)
- DEV-01은 게시된 Cloud 환경의 복원과 개발 기준선을 확인했다. 실제 명령·대상 SHA·Cloud Docker 빌드 제약 및 CI 결과는 [Cloud 기준선 기록](CLOUD_SETUP.md#dev-01-cloud-기준선)을 참조한다.
- [IMG-01](IMG-01_IMAGE_SOURCES.md)에서 기존 이미지 구조와 시드, 표본을 정리했다. 승인된 접속 환경에서 원본 응답과 공식 약관 확인을 이어간다. [IMG-02 dry-run](IMG-02_PRODUCT_MAPPING.md)은 합성 입력으로 검증했다. 실제 후보 검수와 이용 조건 확인이 남아 있다. IMG-02와 IMG-03A는 PR #9를 통해 main에 반영됐다.
- [IMG-03A](IMG-03A_IMAGE_STORAGE.md)는 Tools에서 합성 이미지를 검증·변환하고 모의 저장 응답의 무결성을 확인했다. IMG-03B는 [출처·호환성](IMG-03B-1_CATALOG_SOURCES.md), 공통 이미지 처리 모듈, 이미지 자산·Backend 제공으로 나뉜다. 실이미지 처리는 이용 허가와 상품 승인 확인이 필요하다.
- [IMG-03A-IT](IMG-03A_IT_R2_SMOKE.md)의 핵심 검증은 사용자의 Windows 실행에서 통과했다. 읽기 전용 권한 검증은 미실행이고 테스트 객체 정리 여부는 확인 대기다.
- [카탈로그 제보·IMG-03B 설계](UGC_CATALOG_ARCHITECTURE.md)에서 제보와 공개 카탈로그 분리, IMG-03C와 UGC의 공통 자산 계약, UGC-01/02/03의 구현 범위를 제안했다. [IMG-03B-2 공통 모듈](IMG-03B-2_IMAGE_CORE.md)은 병합됐다. [IMG-03B-3](IMG-03B-3_IMAGE_ASSETS.md)은 V10, 검증 저장, 공개 게이트웨이와 URL 호환을 구현했다. [독립 QA PASS](IMG-03B-3_QA_PR15.md#최종-독립-qa-및-병합)를 확인했다. [UGC-01 제보 API](UGC-01_SUBMISSIONS.md)는 병합됐다. [UGC-02A 관리자 검수](UGC-02A_ADMIN_REVIEW.md)는 독립 QA 대기 상태다.
- 운영 장애, 본인확인/PG·이미지 권리, 운영 SHA는 [현재 상태](PROJECT_STATE.md)에서 확인한다. 실키, 외부 API 호출, R2 실제 업로드, 배포 등은 [승인 범위](../../AGENTS.md)를 따른다.

## 중단 시 체크포인트

안전한 변경을 커밋·push하고 필요한 경우 Draft PR을 만든다. 사용자 로컬 파일이나 민감 데이터가 섞이면 분리 전에는 커밋하지 않는다. 작업별 Issue/PR 또는 해당 문서에 아래 최소 정보만 적는다.

- 작업 ID·브랜치·기준 SHA·현재 head·PR
- 완료·미완료 범위, 수정 파일, 중요한 결정과 승인 대기 항목
- 마지막 검증 SHA·명령·관찰 결과·실패 원인·다음 명령

## 새 에이전트에 전달할 시작 문장

> 최신 main과 열린 PR에서 UGC-02A의 병합·QA를 확인하고 검수 계약을 읽어 줘. UGC-02B에서 이미지 없는 공개 승인을 연결하고 UGC-02C에서 권리 판단과 영속 승인 작업을 이어 가 줘. UGC 운영 전에는 기존 추천 SQL과 구버전 Tools 쓰기 차단, V9 잠금 계약을 확인해 줘.
