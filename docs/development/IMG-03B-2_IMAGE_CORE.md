# IMG-03B-2 공통 이미지 처리 모듈

기준 main `ea1f42d27811808a26e9eaccf43f324dd841157f`. IMG-03B-1의 완료 및 독립 QA 증거는 별도 기록을 참조한다. [IMG-03B-1](IMG-03B-1_CATALOG_SOURCES.md#최종-독립-qa-및-병합)

## 책임과 의존 방향

```text
Backend ─┐
         ├── com.sakezuki:image-core ── Java 21 ImageIO, AWS SDK v2
Tools ───┘
```

- **image-core**는 JPEG·PNG 컨테이너와 입력 MIME 검증, 바이트·해상도·픽셀 제한, 디코딩, 리사이즈·재인코딩, 원본·변환 메타데이터와 SHA-256을 제공한다. 저장 인터페이스, 버전형 키, 조건부 PUT·GET R2 구현, 저장 후 재검증은 한 구현으로 유지한다. `ImageFailure`는 라이브러리의 실패 범주와 이유를 전달한다.
- **Tools**는 HTTP 전송·SSRF 방어·리디렉션·재시도·출처/권리 승인, 매핑과 스모크 진입점을 유지한다. 전송의 바이트 상한은 공통 상수를 사용하고, 파이프라인 결과에는 공통 실패를 기존 Tools 상태로 매핑한다. 기존 권리를 저장 전에 재검증한다.
- **Backend**는 라이브러리 의존성만 가져온다. Spring, 회원·권리 원장, 외부 다운로드 또는 특정 상품 DTO를 라이브러리의 의존성으로 넣지 않는다. Backend의 자산 API와 정책은 IMG-03B-3에서 구현한다.

기존 JPEG·PNG 검사·크기·품질·메타데이터 정책, 키 형식, SHA-256·MIME·길이 확인과 404·412 처리를 유지했다. `VerifiedImageStorage.store`는 기존 공유 경로와 같은 조건부 쓰기 및 실제 바이트 재검증을 수행한다. SDK interceptor는 스모크의 HTTP 관찰에 필요한 어댑터 경계에만 사용하며 SDK 버전은 유지했다. 스모크 명령과 명시적 옵션·개발 버킷 제약은 그대로다. [IMG-03A 정책](IMG-03A_IMAGE_STORAGE.md) · [개발 스모크](IMG-03A_IT_R2_SMOKE.md)

## 빌드, Docker 및 테스트 배치

Backend와 Tools의 기존 Wrapper에서 독립 Java 라이브러리를 `includeBuild`로 참조한다. 외부 게시 없이 양쪽이 동일 소스를 해석한다. 루트 멀티프로젝트 전환은 Wrapper와 배포 경로 변경을 요구하고, 외부 아티팩트 발행은 레지스트리·권한·버전 운영이 필요하므로 현재 구조를 선택했다. Docker 컨텍스트는 `./backend`다. Dockerfile은 모듈의 Gradle 파일과 main 소스만 복사하고, 모듈 캐시·산출물은 제외한다. Jenkins 및 OCI의 Compose 호출 방식은 유지한다. [Dockerfile](../../backend/Dockerfile) · [Compose](../../docker-compose.yaml) · [Jenkins](../../Jenkinsfile)

| 기존 검증 | 추출 후 |
| --- | --- |
| `R2ImageStorageTest` 3개 | 공통 모듈로 이동. 모의 S3 조건부 PUT·412·404와 GET 메타데이터·해시 검증 유지 |
| `ImagePipelineTest` | Tools에 유지. 모의 HTTP, 출처·권리, 릴레이·SSRF, 변환과 저장을 연결하는 경로 검증 |
| `R2DevSmokeTest`, 매핑·카탈로그 테스트 | Tools에 유지. 실제 스모크 실행은 기본 테스트와 분리 |
| `ImageCoreContractTest` 3개 | 이미지 알파·축소와 양쪽 해시, 공통 실패 계약, 검증된 객체 재사용과 무결성 직접 검사 |

Tools의 기본 `test`가 공통 모듈의 `verifyTestReports`를 실행한다. 공통 XML이 없거나 0건·실패·오류·스킵이면 실패한다. CI는 공통 보고서를 지운 뒤 실행하며 실패 시 기존 보고서와 함께 보관한다.

```bash
(cd tools && bash ./gradlew --no-daemon :image-core:cleanTest cleanTest test)
(cd backend && bash ./gradlew --no-daemon :image-core:clean clean bootJar)
(cd backend && bash ./gradlew --no-daemon dependencies --configuration runtimeClasspath)
python3 scripts/test_auth_integration.py --e2e
```

IMG-03B-3는 공통 변환·저장 API 위에 자산 메타데이터, 접속 권한과 활성 자산 게이트웨이를 구현한다. 운영 기능에는 기존 추천 SQL 수정, 구버전 Tools 쓰기 차단 및 V9 잠금 계약 준수가 여전히 필요하다. 운영 DB·R2, Gemini, 외부 사이트 크롤링, OCI 배포는 실행하지 않는다. ARM64/Windows는 이 변경에서 직접 실행하지 않았다.
