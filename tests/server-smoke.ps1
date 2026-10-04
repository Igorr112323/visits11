param([Parameter(Mandatory=$true)][string]$Executable)
$ErrorActionPreference = "Stop"
$exe = (Resolve-Path $Executable).Path
$dataDir = Join-Path $env:TEMP ("kubgau-server-test-" + [Guid]::NewGuid().ToString("N"))
$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
$listener.Start()
$port = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
$listener.Stop()
New-Item -ItemType Directory -Path $dataDir -Force | Out-Null
$process = $null
$base = "http://127.0.0.1:$port"
try {
    Write-Output "SMOKE_STAGE: start-server"
    $process = Start-Process -FilePath $exe -ArgumentList @("--data-dir=$dataDir", "--port=$port") -PassThru -WindowStyle Hidden
    Write-Output "SMOKE_STAGE: wait-health"
    $health = $null
    for ($i = 0; $i -lt 45; $i++) {
        if ($process.HasExited) {
            $crashPath = Join-Path $dataDir "server-error.log"
            $crash = if (Test-Path $crashPath) { Get-Content -Raw $crashPath } else { "No managed exception log was written" }
            throw "Server process exited with code $($process.ExitCode): $crash"
        }
        try {
            $health = Invoke-RestMethod -Uri "$base/api/health" -TimeoutSec 2
            break
        } catch {
            Start-Sleep -Milliseconds 500
        }
    }
    if ($null -eq $health -or $health.app -ne "kubgau-attendance-server") { throw "Health endpoint did not return the expected service identity" }
    Write-Output "SMOKE_STAGE: health-ok"
    $keyPath = Join-Path $dataDir "server.key"
    $key = (Get-Content -Raw $keyPath).Trim()
    if ($key.Length -lt 32) { throw "Server sync key was not persisted" }
    $markId = [Guid]::NewGuid().ToString()
    $studentKey = [Guid]::NewGuid().ToString("N")
    $mark = @{
        id = $markId
        studentKey = $studentKey
        deviceId = "smoke-test-device"
        occurredAt = [DateTimeOffset]::UtcNow.ToString("o")
    } | ConvertTo-Json -Compress
    Write-Output "SMOKE_STAGE: post-mark"
    $accepted = Invoke-RestMethod -Method Post -Uri "$base/api/marks" -ContentType "application/json" -Body $mark
    if (-not $accepted.ok -or $accepted.duplicate) { throw "A new mark was not accepted" }
    Write-Output "SMOKE_STAGE: duplicate-mark"
    $duplicate = Invoke-RestMethod -Method Post -Uri "$base/api/marks" -ContentType "application/json" -Body $mark
    if (-not $duplicate.ok -or -not $duplicate.duplicate) { throw "Duplicate mark was not idempotent" }
    Write-Output "SMOKE_STAGE: unauthorized-key"
    $unauthorized = $false
    try {
        Invoke-RestMethod -Uri "$base/api/marks/pending" -Headers @{ "X-Teacher-Key" = "wrong-key" } | Out-Null
    } catch {
        $unauthorized = $true
    }
    if (-not $unauthorized) { throw "Pending marks endpoint accepted an invalid teacher key" }
    Write-Output "SMOKE_STAGE: authorized-queue"
    $headers = @{ "X-Teacher-Key" = $key }
    $pending = @(Invoke-RestMethod -Uri "$base/api/marks/pending" -Headers $headers)
    if ($pending.Count -ne 1 -or $pending[0].id -ne $markId -or $pending[0].studentKey -ne $studentKey) { throw "Pending mark payload did not match the submitted mark" }
    Write-Output "SMOKE_STAGE: acknowledge"
    $ack = @{ id = $markId } | ConvertTo-Json -Compress
    $ackResult = Invoke-RestMethod -Method Post -Uri "$base/api/marks/ack" -Headers $headers -ContentType "application/json" -Body $ack
    if (-not $ackResult.ok) { throw "Teacher acknowledgement failed" }
    $afterAck = @(Invoke-RestMethod -Uri "$base/api/marks/pending" -Headers $headers)
    if ($afterAck.Count -ne 0) { throw "Acknowledged mark remained in the pending queue" }
    Write-Output "PASS server health, key persistence, mark acceptance, duplicate protection, teacher authorization, queue delivery and acknowledgement"
} catch {
    $message = $_.Exception.Message -replace "[\r\n]+", " | "
    Write-Output "::error::Server API smoke test failed: $message"
    throw
} finally {
    if ($null -ne $process -and -not $process.HasExited) { Stop-Process -Id $process.Id -Force }
    Remove-Item -LiteralPath $dataDir -Recurse -Force -ErrorAction SilentlyContinue
}
