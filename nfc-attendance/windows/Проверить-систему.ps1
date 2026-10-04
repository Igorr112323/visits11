# КубГАУ · Проверка системы отметок «одним нажатием».
#
# Запускать после того, как сервер уже работает (Запустить-сервер.ps1).
# Что проверяет:
#   1. сервер отвечает;
#   2. студент регистрируется с паролем;
#   3. вход с верным паролем проходит;
#   4. вход с неверным паролем — отклоняется (401 wrong_password);
#   5. пара создаётся, отметка принимается;
#   6. список присутствующих и выгрузка CSV в папку этого файла.

param(
    [string]$Server = "http://127.0.0.1:8000",
    [string]$Login = "",
    [string]$Password = "проверка-1234",
    [string]$Name = "Проверочный Студент"
)

$ErrorActionPreference = "Stop"
$folder = Split-Path -Parent $MyInvocation.MyCommand.Path
$passed = 0
$failed = 0

function Check([string]$title, [scriptblock]$action) {
    Write-Host ("  … {0}" -f $title) -NoNewline
    try {
        & $action | Out-Null
        Write-Host "`r  OK  $title" -ForegroundColor Green
        $script:passed++
    } catch {
        Write-Host "`r  НЕТ $title — $($_.Exception.Message)" -ForegroundColor Red
        $script:failed++
    }
}

function Post-Json([string]$path, [hashtable]$body) {
    $json = $body | ConvertTo-Json
    return Invoke-RestMethod -Uri "$Server$path" -Method Post `
        -ContentType "application/json; charset=utf-8" `
        -Body ([System.Text.Encoding]::UTF8.GetBytes($json)) -TimeoutSec 10
}

Write-Host ""
Write-Host "=== Проверка системы «КубГАУ · Отметки» ($Server) ===" -ForegroundColor Green
Write-Host ""

# 1. сервер
Check "сервер отвечает (/api/health)" {
    $health = Invoke-RestMethod -Uri "$Server/api/health" -TimeoutSec 5
    if (-not $health.ok) { throw "сервер вернул ok=false" }
}

# 2. регистрация
if (-not $Login) { $Login = "TEST_" + (Get-Random -Maximum 99999) }
Check "студент регистрируется с паролем" {
    Post-Json "/api/students/register" @{ student_id = $Login; name = $Name; password = $Password }
}

# 3. вход с верным паролем
Check "вход с верным паролем проходит" {
    $result = Post-Json "/api/students/login" @{ student_id = $Login; password = $Password }
    if (-not $result.ok) { throw "сервер не подтвердил вход" }
}

# 4. вход с неверным паролем должен быть отклонён
Check "вход с неверным паролем отклоняется" {
    try {
        Post-Json "/api/students/login" @{ student_id = $Login; password = "явно-не-тот" }
        throw "сервер принял неверный пароль!"
    } catch {
        if ($_.Exception.Message -like "*принял неверный пароль*") { throw }
        # ожидаемая ошибка 401 — это и есть правильный результат
    }
}

# 5. пара и отметка
$sessionId = [guid]::NewGuid().ToString()
Check "пара создаётся" {
    Post-Json "/api/session" @{
        session_id = $sessionId; subject = "Проверка системы"
        teacher_id = "ПРОВЕРКА"; minutes = 120
    }
}

$deviceId = "PC-" + [guid]::NewGuid().ToString("N").Substring(0, 8)
Check "отметка принимается" {
    $mark = Post-Json "/api/attendance" @{
        session_id = $sessionId; student_id = $Login; device_id = $deviceId
        timestamp  = (Get-Date).ToString("yyyy-MM-ddTHH:mm:sszzz")
        subject    = "Проверка системы"; teacher_id = "ПРОВЕРКА"
        student_name = $Name; source = "ble"; rssi = -55
    }
    if (-not $mark.ok) { throw "сервер отклонил отметку: $($mark.detail)" }
}

# 6. список и CSV
Check "список присутствующих читается" {
    $present = Invoke-RestMethod -Uri "$Server/api/attendance/$sessionId" -TimeoutSec 5
    if ($present.present_count -lt 1) { throw "в списке никого нет" }
}

Check "выгрузка CSV сохраняется рядом со скриптом" {
    $csv = Invoke-WebRequest -Uri "$Server/api/attendance/$sessionId/export.csv" -TimeoutSec 10
    $file = Join-Path $folder "проверка-отметок.csv"
    [System.IO.File]::WriteAllBytes($file, $csv.Content)
    if (-not (Test-Path $file)) { throw "файл не сохранён" }
    Write-Host "`r  OK  выгрузка CSV: $file" -ForegroundColor Green
}

Write-Host ""
if ($failed -eq 0) {
    Write-Host "ВСЁ РАБОТАЕТ: $passed проверок пройдено." -ForegroundColor Green
} else {
    Write-Host "Проверок пройдено: $passed, не прошло: $failed" -ForegroundColor Yellow
    Write-Host "Если сервер не отвечает — запустите Запустить-сервер.ps1" -ForegroundColor Yellow
}
Write-Host ""
