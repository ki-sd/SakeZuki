# IMG-03A-IT 개발 버킷 R2 실제 연동 검증

기준 main `fee86c88421fbae631560ff09eb1f3f05fab532e`에 PR #9의 IMG-02와 IMG-03A 소스가 반영됐다. [PR #9](https://github.com/ki-sd/SakeZuki/pull/9) · [IMG-03A 저장 계약](IMG-03A_IMAGE_STORAGE.md)

| 항목 | 현재 증거 |
| --- | --- |
| Java·Gradle 및 기본 테스트 | Codex Cloud Linux amd64, Temurin 21·Gradle Wrapper 9.3.0에서 기본 테스트 66개 통과. 옵트인 없이 스모크 태스크가 요청 전에 중단되는 것을 확인 |
| 실제 R2 인증, PUT/GET, SHA-256·메타데이터, HTTP 412·403 | Windows Native 실행 대기. 이 작업의 Cloud 검증에서는 실제 자격증명을 받거나 저장 요청을 보내지 않았다. |
| 실제 요청량·저장량·정리 | Cloud에서 스모크 R2 요청 0건, 생성 객체 0개. Windows 실행 수치는 아직 없다. |

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
- 인증 오류는 개발 계정 endpoint·버킷 범위·키 권한을 점검한다. 정상 쓰기 키의 미존재 GET에서 403이 나오면 실행을 중단하고 범위·권한을 확인한다. 의도한 읽기 전용 PUT만 403을 성공 조건으로 사용한다. 시간 초과·TLS 실패 또는 응답 유실은 기록된 키의 상태를 확인한 뒤 재실행한다. 성공 여부, 요청·저장량, 정리 상태는 Windows 실행 후 채운다.
