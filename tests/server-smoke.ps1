param([Parameter(Mandatory=$true)][string]$Executable)
$ErrorActionPreference = "Stop"
$exe = (Resolve-Path $Executable).Path
$dataDir = Join-Path $env:TEMP ("kubgau-server-test-" + [Guid]::NewGuid().ToString("N"))
$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
$listener.Start()
$port = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
$listener.Stop()
New-Item -ItemType Directory -Path $dataDir -Force | Out-Null
$process = Start-Process -FilePath $exe -ArgumentList @("--data-dir=$dataDir", "--port=$port") -PassThru -WindowStyle Hidden
$base = "http://127.0.0.1:$port"
try {
    $health = $null
    for ($i = 0; $i -lt 45; $i++) {
        if ($process.HasExited) { throw "Server process exited with code $($process.ExitCode)" }
        try {
            $health = Invoke-RestMethod -Uri "$base/api/health" -TimeoutSec 2
            break
        } catch {
            Start-Sleep -Milliseconds 500
        }
    }
    if ($null -eq $health -or $health.app -ne "kubgau-attendance-server") { throw "Health endpoint did not return the expected service identity" }
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
    $accepted = Invoke-RestMethod -Method Post -Uri "$base/api/marks" -ContentType "application/json" -Body $mark
    if (-not $accepted.ok -or $accepted.duplicate) { throw "A new mark was not accepted" }
    $duplicate = Invoke-RestMethod -Method Post -Uri "$base/api/marks" -ContentType "application/json" -Body $mark
    if (-not $duplicate.ok -or -not $duplicate.duplicate) { throw "Duplicate mark was not idempotent" }
    $unauthorized = $false
    try {
        Invoke-RestMethod -Uri "$base/api/marks/pending" -Headers @{ "X-Teacher-Key" = "wrong-key" } | Out-Null
    } catch {
        $unauthorized = $true
    }
    if (-not $unauthorized) { throw "Pending marks endpoint accepted an invalid teacher key" }
    $headers = @{ "X-Teacher-Key" = $key }
    $pending = @(Invoke-RestMethod -Uri "$base/api/marks/pending" -Headers $headers)
    if ($pending.Count -ne 1 -or $pending[0].id -ne $markId -or $pending[0].studentKey -ne $studentKey) { throw "Pending mark payload did not match the submitted mark" }
    $ack = @{ id = $markId } | ConvertTo-Json -Compress
    $ackResult = Invoke-RestMethod -Method Post -Uri "$base/api/marks/ack" -Headers $headers -ContentType "application/json" -Body $ack
    if (-not $ackResult.ok) { throw "Teacher acknowledgement failed" }
    $afterAck = @(Invoke-RestMethod -Uri "$base/api/marks/pending" -Headers $headers)
    if ($afterAck.Count -ne 0) { throw "Acknowledged mark remained in the pending queue" }
    Write-Output "PASS server health, key persistence, mark acceptance, duplicate protection, teacher authorization, queue delivery and acknowledgement"
} finally {
    if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force }
    Remove-Item -LiteralPath $dataDir -Recurse -Force -ErrorAction SilentlyContinue
}
