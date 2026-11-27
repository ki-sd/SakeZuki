# IMG-03A-IT 개발 버킷 R2 실제 연동 검증

기록 기준 main은 `192cf92f70b9c19b02163e9629779b4657b2cc1a`이며 스모크 실행기 PR #10이 병합됐다. 사용자가 제공한 Windows Native 실행 결과를 기록했다. 실행 당시 checkout SHA는 제공되지 않았다. [PR #10](https://github.com/ki-sd/SakeZuki/pull/10) · [IMG-03A 저장 계약](IMG-03A_IMAGE_STORAGE.md)

## 실제 검증 결과

**핵심 개발 버킷 연동 검증 완료.** Windows 11 amd64, Eclipse Temurin 21.0.13.1 LTS, Gradle Wrapper 9.3.0에서 Tools 기본 테스트는 `BUILD SUCCESSFUL`이었고 `r2DevSmoke`는 `R2 core checks passed; read-only probe skipped`로 종료됐다.

| 항목 | 사용자 제공 결과 |
| --- | --- |
| 개발 버킷 연결·인증 | `sakezuki-images-dev` 연결과 인증 성공 |
| 합성 이미지 PUT·GET | JPEG 13,889 bytes, PNG 64,791 bytes. SHA-256과 저장 메타데이터 검증 성공 |
| 미존재·중복 처리 | 미존재 객체 HTTP 404 처리 및 중복 업로드 방지 성공. 동일 키 조건부 PUT의 HTTP 412를 2회 확인 |
| SDK HTTP 요청 시도 | GET 9회, PUT 4회. Cloudflare 집계·청구 수치는 미확인 |
| 생성 응답 확인 | 객체 2개, 총 78,680 bytes |
| 읽기 전용 권한 | 선택적 검증 미실행 |
| 테스트 객체 정리 | 사용자 확인 대기 |
| 로깅 | SLF4J 구현체 부재 경고가 있었으며 핵심 기능 검증은 통과했다. IMG-03C에서 로깅 개선 후보로 검토한다. |

이번 문서 작업에서 실제 R2 실행은 반복하지 않았다. 기존 Codex Cloud 사전 검증에서는 Tools 기본 테스트 66개가 통과했고, 옵트인 없는 실행이 전송 전에 거부되는 것을 확인했다.

## Windows Native 실행

Java 21로 `tools`에서 실행한다. 쓰기 키는 **`sakezuki-images-dev` 단일 버킷에 제한된 Object Read & Write** 키를 사용한다. 선택적인 제한 권한 검증에는 동일 버킷에 대한 별도 Object Read only 키가 필요하다. 운영 키나 여러 버킷에 접근할 수 있는 키는 사용하지 않는다. 실행기는 버킷 이름을 고정하지만 키가 부여받은 전체 권한을 조회하지 않는다.

다음 PowerShell 예에서는 R2 S3 키 두 값을 마스킹된 입력으로 받아 현재 프로세스의 환경에만 전달한다. 도구나 터미널의 전사 기록, Gradle `--debug`, SDK wire logging은 사용하지 않는다. 세션 지속 환경 변수, 프로필 및 `.env`에 비밀을 기록할 필요가 없다.

```powershell
Set-Location tools
java -version
.\gradlew.bat --no-daemon --version

function Read-ProcessSecret([string]$Name) {
    $secret = Read-Host $Name -AsSecureString
    $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
    try {
        [Environment]::SetEnvironmentVariable(
            $Name, [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr), 'Process')
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
        $secret.Dispose()
    }
}

$env:R2_ENDPOINT = Read-Host '개발 계정의 R2 S3 HTTPS endpoint'
$env:R2_BUCKET = 'sakezuki-images-dev'
$env:R2_REGION = 'auto'
$env:R2_SMOKE_CONFIRM = 'sakezuki-images-dev'
try {
    Read-ProcessSecret 'R2_ACCESS_KEY_ID'
    Read-ProcessSecret 'R2_SECRET_ACCESS_KEY'
    if ((Read-Host '별도 읽기 전용 키 검증을 수행하려면 YES') -ceq 'YES') {
        Read-ProcessSecret 'R2_READONLY_ACCESS_KEY_ID'
        Read-ProcessSecret 'R2_READONLY_SECRET_ACCESS_KEY'
    }
    .\gradlew.bat --no-daemon -q r2DevSmoke
    if ($LASTEXITCODE -ne 0) { throw 'R2 개발 버킷 스모크 실패' }
} finally {
    foreach ($name in 'R2_ACCESS_KEY_ID', 'R2_SECRET_ACCESS_KEY',
                      'R2_READONLY_ACCESS_KEY_ID', 'R2_READONLY_SECRET_ACCESS_KEY',
                      'R2_SMOKE_CONFIRM', 'R2_ENDPOINT', 'R2_BUCKET', 'R2_REGION') {
        Remove-Item "Env:$name" -ErrorAction SilentlyContinue
    }
}
```

별도 실행인 `r2DevSmoke`는 `test`나 GitHub Actions에 연결돼 있지 않다. 두 버킷 확인 변수와 Java 21을 요구한다. 읽기 전용 키가 제공되지 않으면 403 권한 검증을 건너뛰고 그 사실을 출력한다.

## 결과와 정리

- 자체 생성 JPEG·PNG를 기존 변환기로 처리한다. 개발 버킷의 임의 테스트 키에서 미존재 GET을 확인한 뒤, 포맷별 조건부 PUT, 기존 파이프라인의 중복 방지, 실제 바이트의 SHA-256 및 길이, MIME·캐시·SHA 메타데이터, 동일 키 PUT의 HTTP 412를 확인한다. 해시가 맞지 않는 입력은 전송 전에 거부돼야 한다.
- 출력은 키·바이트 수·SHA-256, 생성 응답을 확인한 바이트 합계, SDK HTTP 시도 수, 관찰한 412 수, 로컬 매니페스트 경로만 기록한다. 정상 완료 시 예상 요청은 GET 9회, PUT 4회이며 읽기 전용 검증이 GET 1회와 PUT 1회를 추가한다. SDK 시도와 R2의 실제 집계·청구 수치는 별개이므로 실행 시각과 범위를 대조해 대시보드에서 확인한다.
- 일부 요청이 실패해도 매니페스트는 `tools/build/r2-smoke/<run>.tsv`에 남는다. 임의의 높은 상품 번호를 경로에 사용하고 매니페스트에 각 정확한 키를 저장한다. 자동 삭제는 없다. 사용자 승인 후 개발 대시보드에서 해당 실행의 `CREATED` 또는 `UNEXPECTED_CREATED` 키만 정리한다. `PLANNED` 또는 `PERMISSION_PROBE`만 남았으면 해당 실행의 요청·객체 근거를 먼저 대조하고 소유가 불명확하면 남겨 둔다. 접두사 일괄 삭제는 피한다. 정리 후 객체 수와 바이트를 확인해 기록한다.
- 인증 오류는 개발 계정 endpoint·버킷 범위·키 권한을 점검한다. 정상 쓰기 키의 미존재 GET에서 403이 나오면 실행을 중단하고 범위·권한을 확인한다. 의도한 읽기 전용 PUT만 403을 성공 조건으로 사용한다. 시간 초과·TLS 실패 또는 응답 유실은 기록된 키의 상태를 확인한 뒤 재실행한다. 후속 검증을 수행한 경우 위 결과에 실행 범위와 정리 상태를 기록한다.
