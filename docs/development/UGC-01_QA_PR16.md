# PR #16 UGC-01 독립 QA 후속

사용자 제공 Antigravity 판정은 PR #16의 ceaad6fd9930e4f55b98d1b39601eb541b824c79에 대한 CONDITIONAL PASS다. 공식 JVM 176/176, 독립 프로브 147/147 및 추가 실측 9건이다. P0·P1 0건, P2 2건, P3 4건을 보고했다. 이전 CI 성공도 해당 HEAD의 증거다. 수정 HEAD의 독립 QA는 대기 중이다. [PR #16](https://github.com/ki-sd/SakeZuki/pull/16) · [이전 CI](https://github.com/ki-sd/SakeZuki/actions/runs/46040547436)

## 수정 항목

- **P2-1 · P7.5a–P7.5d** — 웹의 두 번째 업로드가 이미지 처리의 단일 슬롯에 도달하기 전에 저장 의도와 12MiB를 예약했다. JVM의 HTTP 업로드 슬롯을 이미지 처리와 같은 1건으로 맞췄다. 경합한 요청은 인증 및 요청 헤더 검증 후, multipart 파싱·저장 의도·쿼터 예약 전에 503이다. private, no-store와 nosniff를 유지한다. 슬롯 종료가 저장소와 요청 진행 속도에 따르므로 Retry-After 값은 지정하지 않는다.
- **P2-2 · P6.9a** — 중복 속성을 마지막 값으로 해석했다. Jackson 3.1.7의 STRICT_DUPLICATE_DETECTION을 UGC 요청 전용 JsonMapper에 적용했다. 제보 생성·보완·취소 모두 최상위와 중첩 객체에서 중복을 422로 거부한다. 미등록 속성, 후행 토큰, 실수의 정수 변환도 계속 거부한다.
- **P3-2** — 전용 CoercionConfig로 정수·소수·Boolean에 문자열 강제 변환을 막았다. 정수에는 JSON 정수 토큰, 수치 min/max에는 JSON 숫자, 동의에는 true/false를 사용한다. UUID 문자열, 선택 NULL, BigDecimal 정밀도는 유지한다. 저장·응답·기존 스냅샷의 공유 ObjectMapper에는 새 설정을 적용하지 않는다.
- **P3-4** — notes와 imageRights의 CRLF를 DTO 경계에서 LF로 정규화한다. 두 필드는 LF를 허용하고 탭·단독 CR·NUL 등은 기존처럼 거부한다. 빈 설명과 짝 없는 서러게이트 제한도 유지한다. 정규화 후 저장·해시 계산을 하므로 LF와 CRLF만 다른 재시도는 같은 영수증을 반환한다. 기존 리비전은 다시 쓰지 않는다. HTML은 문자열이며 이미지 동의로 권리 이벤트를 만들지 않는다.

## 요청 크기 대조

| 계층 | 적용값 | 확인 |
| --- | --- | --- |
| Nginx 기본 | 16k | 기존 Docker 검사에서 일반 제보 경로의 17,000바이트는 413 |
| Nginx 업로드 POST 예외 | 11m | 정확한 업로드 경로. 동일 크기의 익명 요청은 401 |
| Spring multipart | 파일 10MB, 전체 11MB | 지연 파싱, 리졸버가 파트와 전체를 제한 |
| Backend | 파일 10MiB, 전체 11MiB, JSON 16KiB | 길이 헤더 선검사, JSON·파일은 제한된 바이트만 읽음 |

Nginx와 Spring의 전체 multipart 상한은 모두 11MiB다. 독립 보고서의 12m 표기는 현재 구성과 다르다. Nginx의 일반 본문 제한, 업로드 전용 예외, Spring 파일별 제한은 각각 해당 계층에서 동작한다. 관련 상한 설정과 V1–V11은 그대로 유지했다.

## 남은 기술 부채와 독립 QA

- **P3-1 · P9.B2.** V11 임대 만료 후 이전 작업자가 늦게 V10 stage를 시작할 수 있다. V10 객체 attempt 경합으로 새 작업이 503을 받더라도 V11 세대 검증은 오래된 완료를 차단하며 같은 키로 동일 ID의 READY를 복구할 수 있다. 웹 슬롯은 한 JVM의 HTTP 업로드를 제한한다. UGC-02 승인 Worker와 공통 ImageAssetService의 자원 경쟁, 업로드·승격의 세대 계약은 후속 설계에서 검토한다.
- **P3-3.** 현재 파일 파트 MIME은 대소문자 및 파라미터까지 정확히 일치해야 한다. IMAGE/PNG 또는 image/png; charset=utf-8의 415를 유지한다. 향후 MIME 정규화 검토에 실제 JPEG·PNG 바이트 검증을 포함한다.
- **재검증 범위.** 같은·다른 회원의 두 번째 HTTP 요청에 대한 저장소 호출·예약·일일 쿼터, 종료와 예외 뒤 슬롯 반환, JSON 중복·타입 검증의 무변경성, 설명 LF/CRLF와 기존 영수증 재생, 길이 헤더와 chunked 본문의 경계를 확인한다.

## 검증

로컬 검증 코드 7ff3633cda05fc2bebe26d42585523552a6f8ef0. 격리 MySQL·Fake Storage·모의 S3를 사용했다.

| 명령 | 실제 결과 |
| --- | --- |
| Tools Wrapper의 :image-core:cleanTest cleanTest test | image-core 11개, Tools 기본 69개 통과 |
| python3 scripts/test_auth_integration.py --e2e의 JVM 단계 | Backend 기본 28개, MySQL 59개, Tools MySQL 17개 통과 |
| Backend Wrapper bootJar | 성공 |

JVM 184개가 실패·오류·스킵 없이 통과했다. 추가 HTTP 8건은 같은·다른 회원 경합의 무예약·무저장소 호출, 파싱·입력·저장 실패 후 슬롯 재사용, 생성·보완·취소의 중복 키, 숫자·동의 타입, 설명 저장·응답·영수증, Content-Length·chunked 크기 경계를 검증한다. 기존 PUT 후 DB 실패 복구, 비공개 미리보기 재검사, 동시 보완·취소, V1–V11 초기화 및 V10 업그레이드도 통과했다. PR #13 카탈로그 경합 45건의 첫 시도와 실제 MySQL 1205 재시도를 확인했다. 최종 Chromium 및 원격 CI는 PR 검사 기록을 참조한다.

## 최종 독립 QA와 병합

사용자가 제공한 재검증 대상은 acdf394c79ecc4fb9a62b91c1890b749c2d64034이며 판정은 PASS였다. JVM 184/184, 실패·오류·스킵 0건이었다. P2-1, P2-2, P3-2, P3-4의 독립 프로브는 각각 36/36, 31/31, 38/38, 35/35였으며 새 P0·P1·P2는 0건이었다. 특정 chunked 요청의 multipart 전체 11MiB+1 경계에서 Tomcat이 마지막 개행을 읽지 않아 추가 파트 검증의 422가 반환된 사례도 기록됐다. 저장이나 자원 제한 우회는 관찰되지 않았다. 초과 요청이 항상 413을 반환하는 것으로 해석하지 않는다.

별도 원격 Actions run 46111430387은 같은 HEAD에서 프런트엔드 단위 16개, Chromium 11개 및 Linux Docker HTTP/HTTPS를 포함한 전체 검증을 통과했다. PR #16은 63a8d1de1bd82403953efb5fb88eada58f5cf303에서 main에 병합됐다. [PR #16](https://github.com/ki-sd/SakeZuki/pull/16) · [원격 CI](https://github.com/ki-sd/SakeZuki/actions/runs/46111430387)
