# 개발 로드맵

우선순위는 작업 순서 제안이다. PARTIALLY READY는 일부 환경 제약을 기록한 상태다. PLANNED는 착수 전, BLOCKED는 명시된 조건 대기, IN_REVIEW는 PR 검토 중이다. DONE과 운영 적용을 별도로 기록한다. 연결된 Issue/PR가 없는 작업은 미등록이다.

| ID / 우선순위 | 작업·상태 | 선행·완료 기준 | Issue/PR·운영 |
| --- | --- | --- | --- |
| DEV-01 / P0 | Cloud 개발 기준선 · PARTIALLY READY | Cloud 복원·검증 결과 및 Docker 빌드 제약을 [기준선 기록](CLOUD_SETUP.md#dev-01-cloud-기준선)에 보관. 컨테이너 HTTP/HTTPS는 GitHub Actions 결과 참조 | DEV-01 문서 변경 · 해당 없음 |
| IMG-01 / P1 | 외부 이미지 장애 및 출처·권리 · BLOCKED | 저장소 분석 완료. 승인된 접속 환경에서 원본 응답과 이용 조건 확인. 권리 불명확 자료는 운영 복제 보류 | [조사 기록](IMG-01_IMAGE_SOURCES.md) · 미적용 |
| IMG-02 / P1 | 신규 상품 식별자 매핑 · 구현 병합 | 합성 입력으로 dry-run 구현·검증. 실제 링크·상품 변형 및 이용 범위 확인 대기 | [IMG-02 계약](IMG-02_PRODUCT_MAPPING.md) · 미적용 |
| IMG-03A / P1 | 이미지 검증·변환·Object Storage · DONE | IMG-02 의존. 합성 이미지·모의 HTTP/S3 검증 | [이미지 저장 계약](IMG-03A_IMAGE_STORAGE.md) · 미적용 |
| IMG-03A-IT / P1 | Windows 개발 버킷 R2 스모크 · DONE | 핵심 검증 완료. 읽기 전용 권한 미실행, 테스트 객체 정리 미확인 | [검증 기록](IMG-03A_IT_R2_SMOKE.md) · 개발 버킷 |
| IMG-03B-1 / P1 | 카탈로그 출처·레거시 호환성 · DONE | 출처 FK, backfill, Tools 경계 | [전환 기록](IMG-03B-1_CATALOG_SOURCES.md) · 미적용 |
| IMG-03B-2 / P1 | Tools·Backend 공통 이미지 처리 모듈 · IN_REVIEW | IMG-03B-1 이후. Gradle·Docker 호환 검증 | [모듈 기록](IMG-03B-2_IMAGE_CORE.md) · 미적용 |
| IMG-03B-3 / P1 | 이미지 자산 메타데이터·Backend 이미지 제공 · PLANNED | IMG-03B-2 이후. 자산·권리·헤더 계약 | 미등록 · 미적용 |
| IMG-03C / P1 | 마이그레이션 실행기·멱등성·실패 복구·롤백 · PLANNED | IMG-03B-3 공통 이미지 자산 사용. 실제 데이터는 권리 및 승인 필요. SLF4J 개선 검토 | [설계](UGC_CATALOG_ARCHITECTURE.md) · 미적용 |
| UGC-01 / P1 | 사용자 제보 스키마 및 Backend API · PLANNED | IMG-03B-3. 제출, 리비전, 첨부, 소유자 API 및 제한 | [설계](UGC_CATALOG_ARCHITECTURE.md) · 미적용 |
| UGC-02 / P1 | 관리자 검토 및 승인 워크플로 · PLANNED | UGC-01. 역할, 중복, 권리 확인 및 공개 트랜잭션 | [설계](UGC_CATALOG_ARCHITECTURE.md) · 미적용 |
| UGC-03 / P1 | 사용자·관리자 Frontend 화면 · PLANNED | UGC-01/02. 등록·보완·검수 화면 E2E | [설계](UGC_CATALOG_ARCHITECTURE.md) · 미적용 |
| UI-01 / P1 | 검색 중심 홈·공통 이미지 처리·E2E/시각 QA · PLANNED | 테스트 이미지로 IMG-03과 독립 진행 가능. 검색/상세·404/대체 이미지·모바일·키보드 회귀 확인. 빌드 시 Google Fonts 의존성을 제거할 수 있도록 기존 라이선스와 디자인을 보존하는 `next/font/local` 전환 검토 | 미등록 · 미적용 |
| OPS-01 / P2 | OCI ARM64 재배포 · PLANNED | 적용 후보·백업/복원 확인·사용자 승인. 운영 SHA, health, 볼륨과 데이터 보존 기록 | 미등록 · 기존 내부 기동만 사용자 확인 |
| OPS-02 / P2 | 도메인·HTTPS·Google OAuth 운영 검증 · BLOCKED | 도메인·인증서·redirect URI 승인 후 실제 왕복과 쿠키·프록시 확인 | 미등록 · 미적용 |
| OPS-03 / P2 | Jenkins Agent·자동 배포 · PLANNED | 환경 연결·권한·실패 시 복구·배포 승인 조건 검증 | 미등록 · 미연결 |

운영 준비 범위와 제약은 [현재 상태](PROJECT_STATE.md)와 [OCI 절차](../oci-public-deployment.md)를 참조한다. 이번 문서 PR은 이미지·UI·인프라 신규 구현을 포함하지 않는다.
