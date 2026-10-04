# КубГАУ · Отметки посещаемости — запуск сервера на ноутбуке преподавателя.
#
# Что делает файл (запускать правым кликом → «Выполнить с помощью PowerShell»):
#   1. создаёт правило брандмауэра для порта 8000 (частные сети);
#   2. запускает сервер (KubGAU-Attendance-Server.exe рядом с файлом;
#      если exe нет — пробует python launcher.py);
#   3. открывает в браузере страницу проверки;
#   4. печатает адрес, который нужно ввести в приложениях.

param(
    [int]$Port = 8000
)

$ErrorActionPreference = "Stop"
$folder = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $folder

Write-Host ""
Write-Host "=== КубГАУ · запуск сервера отметок ===" -ForegroundColor Green

# --- 1. правило брандмауэра -------------------------------------------------
$ruleName = "КубГАУ отметки (порт $Port)"
$existing = Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue
if (-not $existing) {
    Write-Host "Добавляю правило брандмауэра для порта $Port (нужны права администратора)…"
    try {
        New-NetFirewallRule -DisplayName $ruleName -Direction Inbound -Action Allow `
            -Protocol TCP -LocalPort $Port -Profile Private | Out-Null
        Write-Host "Готово: телефоны в этой сети увидят сервер."
    } catch {
        Write-Host "Не удалось добавить правило (запустите PowerShell от имени администратора)." -ForegroundColor Yellow
        Write-Host "Если при запуске появится окно брандмауэра — выберите «Частные сети» и «Разрешить доступ»."
    }
} else {
    Write-Host "Правило брандмауэра уже есть."
}

# --- 2. адрес для телефонов --------------------------------------------------
$addresses = Get-NetIPAddress -AddressFamily IPv4 |
    Where-Object { $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.254.*" } |
    Select-Object -ExpandProperty IPAddress

Write-Host ""
Write-Host "Адрес для приложений (введите его в Android и iPhone):" -ForegroundColor Cyan
if ($addresses) {
    foreach ($ip in $addresses) { Write-Host "    http://${ip}:$Port" -ForegroundColor White }
} else {
    Write-Host "    не определился — проверьте подключение к Wi-Fi" -ForegroundColor Yellow
}
Write-Host ""

# --- 3. запуск сервера -------------------------------------------------------
$exe = Join-Path $folder "KubGAU-Attendance-Server.exe"
if (Test-Path $exe) {
    Write-Host "Запускаю KubGAU-Attendance-Server.exe…"
    Start-Process -FilePath $exe -ArgumentList "$Port" -WorkingDirectory $folder
} else {
    Write-Host "exe не найден, пробую python launcher.py…" -ForegroundColor Yellow
    Start-Process -FilePath "python" -ArgumentList "launcher.py", "$Port" -WorkingDirectory (Join-Path $folder "..\nfc-attendance\server")
}

Start-Sleep -Seconds 3

# --- 4. проверка и браузер ---------------------------------------------------
try {
    $health = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/health" -TimeoutSec 5
    Write-Host "Сервер отвечает. Студентов в базе: $($health.stats.students), отметок: $($health.stats.attendance)" -ForegroundColor Green
    Start-Process "http://127.0.0.1:$Port/docs"
    Write-Host "Открыл страницу проверки в браузере."
} catch {
    Write-Host "Сервер пока не отвечает — подождите пару секунд и обновите браузер." -ForegroundColor Yellow
}

Write-Host ""
Write-Host "Окно сервера закрывать нельзя: пока оно открыто — система работает." -ForegroundColor Cyan
Write-Host ""
