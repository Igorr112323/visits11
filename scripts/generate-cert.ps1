# Самоподписанный HTTPS-сертификат для «Журнала КубГАУ» (вызывается из generate-cert.bat).
# Результат: certs\cert.pem и certs\key.pem, срок действия 10 лет.
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Extra = @())

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$out  = Join-Path $root 'certs'
$days = 3650

# --- openssl: в PATH или из Git for Windows / OpenSSL for Windows
$candidates = @()
$cmd = Get-Command openssl -ErrorAction SilentlyContinue
if ($cmd) { $candidates += $cmd.Source }
$candidates += @(
    (Join-Path $env:ProgramFiles 'Git\usr\bin\openssl.exe'),
    (Join-Path $env:ProgramFiles 'Git\mingw64\bin\openssl.exe'),
    (Join-Path $env:ProgramFiles 'OpenSSL-Win64\bin\openssl.exe'),
    'C:\OpenSSL-Win64\bin\openssl.exe'
)
if (${env:ProgramFiles(x86)}) {
    $candidates += (Join-Path ${env:ProgramFiles(x86)} 'Git\usr\bin\openssl.exe')
}
$openssl = $candidates | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
if (-not $openssl) {
    Write-Host ''
    Write-Host 'Не найден openssl.' -ForegroundColor Red
    Write-Host 'Установите Git for Windows (https://git-scm.com/download/win) — openssl идёт в комплекте —'
    Write-Host 'и запустите скрипт ещё раз.'
    exit 1
}

# --- адреса для сертификата
$dns = New-Object System.Collections.Generic.List[string]
$ips = New-Object System.Collections.Generic.List[string]
function Add-Dns([string]$name) { if ($name -and -not $dns.Contains($name)) { $dns.Add($name) } }
function Add-Ip([string]$ip)    { if ($ip -and -not $ips.Contains($ip))    { $ips.Add($ip) } }

Add-Dns 'visits11.local'
Add-Dns 'localhost'
Add-Ip  '127.0.0.1'
Add-Ip  '192.168.137.1'     # адрес ПК при раздаче Wi-Fi из Windows («Мобильный хот-спот»)

if ($env:COMPUTERNAME -match '^[A-Za-z0-9-]+$') {
    Add-Dns ($env:COMPUTERNAME.ToLower())
    Add-Dns ($env:COMPUTERNAME.ToLower() + '.local')
}

try {
    Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop |
        Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' } |
        ForEach-Object { Add-Ip $_.IPAddress }
} catch {
    Write-Host 'Не удалось определить IP автоматически — добавьте адрес ПК аргументом.' -ForegroundColor Yellow
}

foreach ($item in $Extra) {
    if ($item -match '^(\d{1,3}\.){3}\d{1,3}$') { Add-Ip $item } else { Add-Dns $item }
}

# --- конфиг openssl
New-Item -ItemType Directory -Force -Path $out | Out-Null
$conf = Join-Path ([System.IO.Path]::GetTempPath()) ('visits11-cert-' + [guid]::NewGuid().ToString('N') + '.cnf')

$lines = @(
    '[req]',
    'distinguished_name = dn',
    'x509_extensions    = ext',
    'prompt             = no',
    '[dn]',
    'CN = visits11.local',
    'O  = KubGAU Visits11',
    '[ext]',
    'basicConstraints = critical, CA:TRUE',
    'keyUsage         = critical, digitalSignature, keyEncipherment, keyCertSign',
    'extendedKeyUsage = serverAuth',
    'subjectAltName   = @san',
    '[san]'
)
for ($i = 0; $i -lt $dns.Count; $i++) { $lines += ('DNS.{0} = {1}' -f ($i + 1), $dns[$i]) }
for ($i = 0; $i -lt $ips.Count; $i++) { $lines += ('IP.{0} = {1}'  -f ($i + 1), $ips[$i]) }
# без BOM — openssl его не любит
[System.IO.File]::WriteAllLines($conf, $lines, (New-Object System.Text.UTF8Encoding($false)))

$key  = Join-Path $out 'key.pem'
$cert = Join-Path $out 'cert.pem'
try {
    & $openssl req -x509 -newkey rsa:2048 -sha256 -nodes -days $days -keyout $key -out $cert -config $conf 2>$null
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $cert) -or -not (Test-Path $key)) {
        throw 'openssl завершился с ошибкой'
    }
} finally {
    Remove-Item -Force -ErrorAction SilentlyContinue $conf
}

Write-Host ''
Write-Host "Готово: сертификат на $days дней (10 лет)." -ForegroundColor Green
Write-Host "  $cert"
Write-Host "  $key"
Write-Host ''
Write-Host 'Адреса в сертификате:'
foreach ($n in $dns) { Write-Host "  имя  $n" }
foreach ($a in $ips) { Write-Host "  IP   $a" }
Write-Host ''
Write-Host 'Дальше: положите папку certs рядом с KubGAU-Journal.exe и перезапустите программу.'
Write-Host 'Для iPhone: откройте  http://<адрес ПК>:8090/setup  — там пошаговая инструкция.'
