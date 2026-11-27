# IMG-02 상품 식별자 매핑과 IMG-03 전달 계약

2026-11-27 KST. 기준 원격 main은 `a1cd5dd64ff92c0e2fb3e200d26e971fc7e58b76`이다. IMG-01 문서 보완이 포함되어 먼저 [PR #7](https://github.com/ki-sd/SakeZuki/pull/7) 브랜치를 기준으로 검토한다. 선행 병합 후 main 기준 diff를 다시 확인한다.

## 입력과 실행

Tools에서 다음을 실행한다. 입력은 합성 JSON 파일이며 URL은 링크 형식 검증에 사용된다. 실행기는 로컬 파일만 읽고 결과 JSON을 표준 출력으로 내보낸다.

```bash
cd tools
bash ./gradlew --no-daemon test
bash ./gradlew --no-daemon -q productMappingDryRun \
  --args='src/test/resources/mapping/synthetic-input.json'
```

- `legacy[]`는 내부 `no`, 기존 `sourceId`, `facts`를 받는다. `candidates[]`는 실제 관찰한 `url`, 상품 `facts`, 검수 가능한 `evidenceRef`를 받는다. 선택 필드 `detailHtml`로 로컬 상세 HTML의 canonical 후보를 대조한다. 검색 결과나 사람 검수로 확인한 사실을 입력에 보존한다.
- `facts`의 비교 항목은 일본어 상품명 `name`, 양조장 `brewery`, 브랜드 `brand`, 특정명칭 `designation`, 정미율 `polishingRatio`, 제품 변형 `variant`다. 변형에는 상품별 용량·생주/화입·라벨 버전 등을 같은 기준으로 기록한다. 정보가 없으면 null 또는 빈 값으로 둔다. NFKC, 공백, 대소문자와 정미율의 퍼센트 앞 공백을 정규화한다. 별칭이나 용량 단위 변환은 사람이 확인한다.
- 선택 배열 `listings[]`에는 `pageUrl`과 로컬 `html`을 입력한다. 파서는 링크에 실제로 나타난 `https://sake-guide.com/sake/<slug>-<숫자>` 형식에서 `pathKey`와 `numericId`를 추출한다. URL 생성에 브랜드명이나 기존 ID를 사용하지 않는다. 상대 링크는 입력 페이지 URL을 기준으로 해석하고 쿼리·프래그먼트를 제거한다. 호스트·경로가 허용 형식과 다른 상세 후보 또는 요청 경로와 일치하지 않는 canonical은 제외한다. 반복 링크는 증거 목록에 보존한다.
- 목록 결과는 링크 인벤토리다. 상품 속성이나 출처 근거를 추정해서 목록의 링크를 승인하지 않는다. 상세 HTML에서 식별자를 대조할 뿐이며, 실사이트의 HTML 필드 배치와 이미지 URL은 검증 대기다. [IMG-01의 공개 검색 자료와 직접 조회 기록](IMG-01_IMAGE_SOURCES.md)

합성 입력 실행에서 JUnit 26개와 JSON dry-run을 검증했다. 실사이트 HTML과 전체 데이터의 매핑은 확인 대기다.

## 결과 JSON v1

최상위 `mapping`에는 `schemaVersion=1`, `dryRun=true`, `rows[]`, `rejectedCandidates[]`가 있다. 행마다 내부 `no`, 기존 `sourceId`, `legacyFacts`, `status`, `reasons[]`, 관련 `candidates[]`를 담는다. 후보에는 `link.url`, `link.pathKey`, `link.numericId`, 입력 `facts`, `evidenceRef`, `missingFields[]`, `differences[]`를 담는다. 잘못된 후보는 입력 배열의 0부터 시작하는 `inputIndex`, 원래 URL, 이유와 함께 기록한다. 최상위 `listings[]`에는 목록 입력별 링크 결과 또는 거부 사유를 담는다.

| 상태 | 의미 |
| --- | --- |
| `ONE_TO_ONE_REVIEW` | 숫자와, 제공된 여섯 상품 특성이 모두 일치한 단일 검수 후보 |
| `ID_MISMATCH` | 상품 특성은 일치하고 숫자 ID가 다름 |
| `IDENTIFIER_COLLISION` | 같은 숫자 ID에 복수 경로 또는 상충하는 상품 정보가 있음 |
| `DUPLICATE` | 내부 행·기존 ID 또는 동일 후보 링크가 반복됨 |
| `AMBIGUOUS` | 상충이 없는 복수 후보 또는 동일 특성의 복수 기존 행이 있어 검수가 필요함 |
| `UNMATCHED` | 동일 ID 또는 동명 후보에서 호환 상품을 찾지 못함 |
| `INCOMPLETE` | 식별 정보, 상품 속성 또는 근거가 부족함 |

모든 결과는 검수용이며 DB에 적용하는 명령이 없다. 여기서 숫자 접미사는 비교 단서이고 `pathKey`는 관찰한 경로의 식별자다. 기존 `sourceId`와 내부 `no`는 유지한다. 먼저 같은 숫자 ID와 정규화된 동명 후보를 비교하므로 이름과 ID가 모두 바뀐 상품은 공식 대응표나 별도 수동 검수 대상이다.

## IMG-03 승인 및 자산 메타데이터

IMG-03은 위 결과의 `no`와 기존 `sourceId`를 검수 기록에 연결한다. 파일 작업 전에 사람이 확정한 `sourceSystem`, 선택한 `pathKey`, 상세 `url`, `evidenceRef`, `reviewedBy`, `reviewedAt`을 별도 승인 기록으로 받는다. 실제 자산은 다음 정보를 함께 보존한다.

- 출처와 권리 근거는 절대 HTTPS `sourceImageUrl`, ISO 8601 `observedAt`, `rightsStatus`(`UNKNOWN`, `DENIED`, `AUTHORIZED`), `rightsEvidenceRef`, 허용 사용 범위 및 출처 표기·만료·삭제 조건을 포함한다.
- 저장과 검증에는 원본 바이트의 16진수 `sha256`, `mimeType`, `byteLength`, 픽셀 단위 `width`·`height`, `transformVersion`, `objectKey`, `processingStatus`를 포함한다. 처리 전에는 아직 측정하지 않은 값을 비워 둔다.

`ONE_TO_ONE_REVIEW` 결과는 사람의 상품 매핑 승인이나 이미지 이용 허가를 대신하지 않는다. 실제 이미지는 두 승인이 모두 확인된 경우에만 후속 처리한다. 합성 자료로는 저장 계약과 재실행·실패·롤백을 검증할 수 있다.
