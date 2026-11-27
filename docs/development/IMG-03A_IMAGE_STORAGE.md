# IMG-03A 이미지 검증·변환·Object Storage

기준 원격 main은 `91b24c2015aabf7798971c2d23812d0efb3375f4`다. PR #7은 main에 병합됐고, PR #8은 `docs/img-01-image-source-analysis`에 병합됐다. 해당 병합 커밋 `427cb7db2c2823bcd6d0de4211c1fe538aba33a1`과 동일한 IMG-02 소스를 개발 의존성으로 사용했다. IMG-03A의 검토 base는 PR #8의 병합 대상 브랜치다. main에 선행 변경이 반영되면 base와 diff를 다시 확인한다. [PR #7](https://github.com/ki-sd/SakeZuki/pull/7) · [PR #8](https://github.com/ki-sd/SakeZuki/pull/8)

## 호출 계약

Tools의 `ImagePipeline.process(Request)`가 상품 1건을 처리한다. IMG-02 JSON v1의 `no`, `sourceId`와 검수 단계에서 확정한 `sourceSystem`, `pathKey`, 상세 URL, `evidenceRef`, `reviewedBy`, `reviewedAt`을 `Mapping`으로 연결한다. `Request`는 이미지 URL과 권리 증거 참조도 담는다. `ONE_TO_ONE_REVIEW`에서 확정 승인으로의 승격은 사람의 검수에서 이루어진다. [IMG-02 계약](IMG-02_PRODUCT_MAPPING.md)

호출자는 입력과 독립된 신뢰 저장소의 `ImageApproval.Authority`를 제공한다. 저장소의 상품 매핑은 제출된 상품·출처·경로·검수 기록과 일치해야 한다. 권리 원장은 이미지 및 리디렉션의 정확한 허용 URL, 출처, 증거 참조, 복제·변환·저장·재배포 범위, 시작과 종료 시각, 철회 상태를 제공한다. 권리자는 용도·영역·출처 표기 등 사용 조건을 검토한 뒤 해당 사용 범위에 승인해야 한다. 다운로드 전과 저장 단계 진입 전에 조회하며 만료·철회·부족한 권한을 구분한다. 원장의 구현과 승인 UI는 후속 범위다.

기존 업데이터에서 조회 실패와 이미지 부재가 둘 다 `null`이 되고 `image_checked='Y'`가 된다. 새 반환값은 상태·사유·선택적 재시도 지연을 담으며, 검증 성공에만 자산 결과를 담는다.

| 상태 | 처리 의미 |
| --- | --- |
| `APPROVAL_PENDING` | 검수 값 불일치·누락 또는 검수 원장 조회 실패 |
| `RIGHTS_UNCONFIRMED`, `RIGHTS_DENIED` | 범위·근거·기간 미확인 또는 명시적 거절·철회 |
| `IMAGE_ABSENT` | 승인된 URL의 404·410 또는 승인된 플레이스홀더 해시 일치 |
| `INVALID_SOURCE`, `DOWNLOAD_FAILED` | URL·DNS·리디렉션 정책 거부 또는 HTTP·전송 오류 |
| `INVALID_IMAGE`, `TRANSFORM_FAILED` | 포맷·본문·한도·디코딩 검증 실패 또는 변환기 오류 |
| `STORAGE_FAILED`, `STORED_VERIFIED` | 저장·재검증 실패 또는 바이트와 메타데이터 재검증 성공 |

## 네트워크 및 파일 검증

- 정확한 HTTPS 허용 호스트와 정상 TLS 인증서가 필요하다. 사용자 정보, 프래그먼트, 비표준 포트, 리터럴 IP 및 HTTP 클라이언트의 정규화로 요청 경로·쿼리가 바뀌는 URL을 거부한다. 각 홉에서 승인된 정확한 URL과 DNS 결과 전체를 검사하고, 사설·로컬·링크 로컬·특수 목적 및 메타데이터 주소를 차단한다.
- OkHttp에 선택한 검증 IP 한 개만 반환하는 DNS를 제공한다. 요청의 원래 호스트에 대해 SNI와 인증서 검증을 유지하고, 요청마다 연결 풀을 격리한다. 자동 프록시와 리디렉션을 사용하지 않는 직접 전송이며, 허가된 직접 egress가 있는 환경이 필요하다. 프록시 전용 Cloud에서는 합성 자료·Mock Transport를 사용한다. 외부 호스트 허용이나 프록시 우회 설정은 추가하지 않았다.
- 리디렉션 최대 3회, 연결 3초, 유휴 읽기 5초, HTTP 호출 전체 12초다. 일시 오류는 최대 3회 시도한다. `Retry-After`의 초·HTTP 날짜를 해석하고, 5초를 넘으면 즉시 재시도하는 대신 상위 실행기에 지연을 반환한다. HTTP 본문의 별도 압축은 받지 않는다.
- 입력 12 MiB, 한 변 8,192px, 총 1,600만 픽셀을 상한으로 한다. 헤더 MIME·JPEG/PNG 컨테이너를 검사한 뒤 메모리에서 실제 디코딩한다. PNG의 청크 경계·CRC·종료 청크를 확인하고 불필요한 청크를 디코더에 전달하기 전에 제거한다. 애니메이션 PNG, HTML, SVG, 지원 외 포맷과 디코더 경고·손상은 거부한다. 공통 플레이스홀더는 출처별로 사전 확인한 원본 SHA-256 목록만 사용한다. URL 이름이나 작은 크기만으로 이미지 부재를 추정하지 않는다.

## 포맷 선택

| 방식 | 검토와 선택 |
| --- | --- |
| Java 21 ImageIO JPEG | JDK 내장 읽기·쓰기 사용(GPLv2 + Classpath Exception). 품질 0.86으로 재인코딩하며 기존 JPEG는 JPEG로 유지한다. |
| Java 21 ImageIO PNG | JDK 내장 읽기·쓰기 사용. 투명 픽셀을 보존하고, 용량이 커져도 손실 포맷 전환을 강제하지 않는다. |
| WebP | JDK 21 기본 플러그인 표에 WebP가 없다. TwelveMonkeys는 BSD-3-Clause WebP 리더를 제공하지만 쓰기를 지원하지 않는다. 초기 파이프라인은 WebP를 거부하며 네이티브 인코더를 추가하지 않는다. |

긴 변 최대 1,200px, 종횡비 유지, 확대 제한을 적용한다. 100–200 KB는 실이미지 평가 목표다. 합성 자료로 평균 용량이나 병 라벨 가독성을 추정하지 않는다. 출력은 새 래스터에서 작성해 원본 EXIF·텍스트·색상 프로파일을 복사하지 않는다. EXIF 방향 보정과 색상 충실도는 실제 승인 표본에서 별도로 검증해야 한다. 원본과 변환본 각각 SHA-256·MIME·바이트 수·해상도를 기록한다. amd64·ARM64 및 Windows에서 별도 JNI 배포를 요구하지 않는 JDK 플러그인을 선택했으며, 실제 실행은 Codex Cloud Linux amd64 Temurin 21에서 확인했다. [OpenJDK 21 ImageIO 플러그인](https://github.com/openjdk/jdk21u/blob/master/src/java.desktop/share/classes/javax/imageio/package-info.java) · [OpenJDK 라이선스](https://github.com/openjdk/jdk21u/blob/master/LICENSE) · [TwelveMonkeys 지원 표](https://github.com/haraldk/TwelveMonkeys#supported-formats) · [TwelveMonkeys 라이선스](https://github.com/haraldk/TwelveMonkeys/blob/master/LICENSE.txt)

## 저장 계약

AWS SDK for Java v2와 Cloudflare R2의 S3 호환 API를 사용한다. 구성은 `R2_ENDPOINT`(계정 HTTPS S3 엔드포인트), `R2_BUCKET`, `R2_REGION`(기본 `auto`), `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`다. 전용 R2 클라이언트는 URLConnection 전송과 path-style 주소를 쓴다. 테스트에서는 구성·실키가 필요 없다. 공식 사이트가 Cloud 프록시에서 거부되어 Cloudflare 공식 문서 저장소의 동일 문서 원본으로 `auto`와 조건부 쓰기를 확인했다. [R2 S3 호환 표](https://github.com/cloudflare/cloudflare-docs/blob/production/src/content/docs/r2/api/s3/api.mdx) · [R2 S3 시작 안내](https://github.com/cloudflare/cloudflare-docs/blob/production/src/content/docs/r2/get-started/s3.mdx) · [AWS SDK 라이선스](https://github.com/aws/aws-sdk-java-v2/blob/master/LICENSE.txt) · [OkHttp 라이선스](https://github.com/square/okhttp/blob/master/LICENSE.txt)

키는 `sake/{no}/image-v1/{변환본 SHA-256}.{jpg|png}`다. 검증된 기존 객체가 있으면 그대로 사용하고, 없으면 `If-None-Match: *`로 생성한다. 동시 생성으로 412를 받으면 이미 생긴 객체를 검증한다. MIME, `private, max-age=300, immutable`, SHA-256 사용자 메타데이터를 지정한다. 쓰기 뒤 크기·MIME·캐시 정책·메타데이터와 제한된 GET으로 계산한 해시를 비교한다. ETag는 검증에 쓰지 않는다. 실패·충돌 시 기존 객체는 유지한다. 공개 배포 정책과 철회·비노출 처리는 후속 단계에 속한다.

## 실행 및 후속 단계

```bash
cd tools
bash ./gradlew --no-daemon test
```

합성 이미지, HTTP 모의 전송, Fake Storage, 모의 S3 SDK 응답으로 신규 38개와 IMG-02 기존 26개를 통과했다. 매핑 JSON v1 합성 dry-run도 확인했다. 실제 R2, 격리 S3 서버, 외부 이미지, 운영 DB 및 OCI는 사용하지 않았다. GitHub Actions의 기존 서비스 검사에 Java 21 Tools 테스트와 실패 보고서 보관을 추가했다.

- **IMG-03B**는 승인·권리 원장 또는 신뢰 연계, 내부 `no`·기존 `sourceId`·출처와 검수 참조, 최종 원본 URL, 상태, 두 해시·메타데이터·변환 버전 및 검증된 키의 저장 스키마를 설계한다. 활성·철회 상태를 확인하는 백엔드 API와 새 Flyway 버전을 적용한다.
- **IMG-03C**는 가용 권한을 다시 확인하는 실행기, dry-run, 지연·재실행·부분 실패 및 격리 롤백을 설계한다. PUT 이후 응답 유실이나 쓰기 성공 후 GET 검증 실패 시에도 객체 삭제 없이 재검증한다. 취소·철회와 처리 간의 경합 및 잔여 객체는 실행기 정책에서 다룬다.
