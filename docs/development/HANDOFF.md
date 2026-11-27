# 새 세션 인수인계

저장소는 `ki-sd/SakeZuki`, 기준은 원격 `main`이다. [공통 규칙](../../AGENTS.md)의 시작 순서에 따라 필요한 문서를 읽고, 실제 브랜치·HEAD·작업 트리·원격 PR을 확인한다.

- 기록 기준 main은 `6134148bdb4b7b9d85a012313c63da01d50a64ac`. PR #4는 병합 완료. [직전 코드 검증](https://github.com/ki-sd/SakeZuki/actions/runs/44987630641)의 대상은 `70710bb5964f4e42ae56be02f22ff82a1377907b`. 문서 PR과 기준선의 HEAD·검증 상태는 시작 시 다시 조회한다. 열린 기능 PR은 기록 시점에 없었다. 이전 인증·공개 UI 브랜치는 병합 이력 확인용으로 남아 있다.
- 이관 준비 작업 브랜치는 `chore/cloud-agent-development-handoff`. [관련 PR 조회](https://github.com/ki-sd/SakeZuki/pulls?q=head%3Achore%2Fcloud-agent-development-handoff). 현재 파일 변경은 개발 문서와 PR 템플릿이다.
- 2026-11-27 로컬 `feat/public-examples-history-ux@70710bb`는 깨끗했고 원격 main과 트리가 같았다. 해당 작업 공간에는 미커밋·untracked 이전 항목이 없었다. Git 무시 파일·본인 PC의 별도 복사본·운영 자격증명은 이 검사 범위 밖이다.
- 다음은 [DEV-01 Cloud 기준선](ROADMAP.md)이다. [환경 설정](CLOUD_SETUP.md)에 따라 새 Cloud 세션에서 설치와 테스트를 실행한다. [IMG-01](ROADMAP.md) 조사와 합성 데이터 UI 검증은 이후 별도 작업으로 나눈다.
- 운영 장애, 본인확인/PG·이미지 권리, 운영 SHA는 [현재 상태](PROJECT_STATE.md)에서 확인한다. 실키, 외부 API 호출, R2 실제 업로드, 배포 등은 [승인 범위](../../AGENTS.md)를 따른다.

## 중단 시 체크포인트

안전한 변경을 커밋·push하고 필요한 경우 Draft PR을 만든다. 사용자 로컬 파일이나 민감 데이터가 섞이면 분리 전에는 커밋하지 않는다. 작업별 Issue/PR 또는 해당 문서에 아래 최소 정보만 적는다.

- 작업 ID·브랜치·기준 SHA·현재 head·PR
- 완료·미완료 범위, 수정 파일, 중요한 결정과 승인 대기 항목
- 마지막 검증 SHA·명령·관찰 결과·실패 원인·다음 명령

## 새 에이전트에 전달할 시작 문장

> ki-sd/SakeZuki의 최신 main을 기준으로 AGENTS.md와 인수인계 문서를 읽고 DEV-01을 수행해 줘. 현재 브랜치와 PR을 확인한 뒤 제공된 합성/격리 환경에서 기준선만 검증하고, 검증 SHA와 실행 결과를 기록해 줘.
