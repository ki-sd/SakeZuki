# 현재 프로젝트 상태

2026-11-27 KST 기록. 저장소 기준 `main@e57ba23480af690656d8cad9b96e841af041c870`. 세션 시작 시 다시 확인한다.

| 영역 | main 구현 및 상태 |
| --- | --- |
| backend | Java 21, Spring Boot 4.1.2, Spring AI 2.0.1, Spring Security, MyBatis. 사케 검색·상세, 양방향 추천, 본인확인·크레딧·기록 API |
| frontend | Next.js App Router, React 19, TypeScript, TanStack Query. 공개 랜딩, 검색·상세·추천·마이페이지·무료 편집 예시 4건 |
| DB | MySQL 8.4, Flyway V1-V5. 초기 스키마/시드, 회원, refresh, 본인확인·크레딧·기록 마이그레이션. 운영 영속 볼륨 |
| 추천 | DB 후보를 Gemini가 재랭킹하고 공용 캐시를 활용. 유료 추천은 개인 스냅샷과 분리. 같은 조건의 유효한 개인 기록을 먼저 제시하고 무료 재열람 또는 새 추천 선택 |
| 권한·크레딧 | Google OAuth2·JWT, refresh 회전과 재사용 탐지. 본인·성인확인 완료 시 최초 10크레딧, 캐시 여부와 무관하게 새 추천 1크레딧. 예약·확정·실패 복구와 멱등성 유지. 개인 기록 30일 무료 재열람 |
| 외부 연동 | 본인확인 실제 사업자·PG 미연동. 공개 예시는 수동 편집 데이터이며 생성/차감 없음 |
| 이미지 및 수집 | 이미지 URL과 실패 시 대체 UI 사용. tools의 수집·파싱·번역은 별도 Gradle 모듈. 기존 외부 URL 로딩 장애 보고. Tools의 R2 저장 파이프라인이 main에 반영됐으며 Windows 개발 버킷 핵심 검증을 통과했다. IMG-03B 공통 이미지·저장소·출처와 UGC 제보 기능은 설계 검토 단계다. [IMG-03A-IT](IMG-03A_IT_R2_SMOKE.md) · [카탈로그 설계](UGC_CATALOG_ARCHITECTURE.md) |

PR #2 인증·크레딧은 main에 병합됐다. PR #3은 선행 브랜치에 병합됐고, PR #4가 그 결과를 main에 반영했다. 아래 CI는 PR #4 head `70710bb5964f4e42ae56be02f22ff82a1377907b` 대상이다. main 병합 커밋과 SHA는 다르며 Git tree는 동일하다. backend 28 + MySQL 24, frontend 16, Chromium 9 및 Linux Docker HTTP/HTTPS 통과가 기록돼 있다. [PR #2](https://github.com/ki-sd/SakeZuki/pull/2) · [PR #3](https://github.com/ki-sd/SakeZuki/pull/3) · [PR #4](https://github.com/ki-sd/SakeZuki/pull/4) · [CI](https://github.com/ki-sd/SakeZuki/actions/runs/44987630641)

DEV-01에서 게시된 Cloud 환경 복원과 개발 기준선을 확인했다. Cloud의 공개 컨테이너 빌드 제약과 동일 Git tree에 대한 CI 결과는 [DEV-01 검증 기록](CLOUD_SETUP.md#dev-01-cloud-기준선)을 참조한다.

## 사용자 제공 운영 기록

OCI AlwaysFree-A1-2, Ubuntu 24.04 LTS ARM64, 1 OCPU / RAM 6GB / swap 2GB. Docker와 Compose, MySQL·Spring Boot·Next.js·Nginx가 기동했고 Flyway V1-V5 및 MySQL 영속 볼륨이 확인됐다. Backend HTTP 200/UP, frontend HTTP 200, SSH 터널로 내부 접근 가능하다고 사용자가 확인했다. 사케 10,337건 중 이미지 URL 보유 4,037건, 미보유 6,300건이다.

운영 배포 SHA는 미확인이다. 이미지 URL 보유 수는 실제 표시 성공 수가 아니다. IMG-01에서 Flyway 시드의 URL 보유 수를 재현했다. 원본 응답은 Cloud 프록시에서 차단돼 개편·권리 여부는 미확인이다. [IMG-01 조사](IMG-01_IMAGE_SOURCES.md) 공개 도메인·HTTPS·운영 OAuth·Jenkins 연결은 미완료다. 운영 서버를 이번 개발 인수인계 작업에서 다시 검사하지 않았다. [OCI 배포 절차](../oci-public-deployment.md) · [후속 계획](ROADMAP.md)
