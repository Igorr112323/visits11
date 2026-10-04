# КубГАУ · Студенты: массовая регистрация логинов и паролей.
#
# Отредактируйте список ниже (логин, ФИО, пароль) и запустите файл.
# Сервер должен быть уже запущен (иначе впишите его адрес в -Server).
#
# Пароль должен быть не короче 4 символов. Если студент уже был — ФИО и пароль
# обновятся (логин остаётся прежним), поэтому этот же файл удобно использовать
# для смены забытого пароля.

param(
    [string]$Server = "http://127.0.0.1:8000"
)

$ErrorActionPreference = "Continue"

# ------------------------------------------------------------------ список
# Логин — как в журнале (латиницей без пробелов), пароль — не короче 4 символов.
$students = @(
    @{ login = "ARHIPOV_II"; name = "Архипов Иван Иванович";   password = "kubgau-2026" },
    @{ login = "IVANOVA_MA"; name = "Иванова Мария Андреевна"; password = "kubgau-2026" },
    @{ login = "PETROV_PP";  name = "Петров Пётр Петрович";    password = "kubgau-2026" }
)

Write-Host ""
Write-Host "=== Регистрация студентов на $Server ===" -ForegroundColor Green
Write-Host ""

$ok = 0
$fail = 0

foreach ($student in $students) {
    $body = @{
        student_id = $student.login
        name       = $student.name
        password   = $student.password
    } | ConvertTo-Json

    try {
        $response = Invoke-RestMethod -Uri "$Server/api/students/register" `
            -Method Post -ContentType "application/json; charset=utf-8" `
            -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 10

        # сразу проверяем, что вход с этим паролем работает
        $loginBody = @{ student_id = $student.login; password = $student.password } | ConvertTo-Json
        $check = Invoke-RestMethod -Uri "$Server/api/students/login" `
            -Method Post -ContentType "application/json; charset=utf-8" `
            -Body ([System.Text.Encoding]::UTF8.GetBytes($loginBody)) -TimeoutSec 10

        Write-Host ("  OK   {0,-14} {1,-28} пароль: {2}" -f $student.login, $student.name, $student.password) -ForegroundColor Green
        $ok++
    } catch {
        Write-Host ("  ОШИБКА {0,-12} {1}" -f $student.login, $_.Exception.Message) -ForegroundColor Red
        $fail++
    }
}

Write-Host ""
Write-Host "Готово: $ok студентов, ошибок: $fail" -ForegroundColor Cyan
Write-Host ""
Write-Host "Раздайте студентам логин и пароль. В приложении они вводят их один раз —" -ForegroundColor Cyan
Write-Host "дальше только прикладывают телефон." -ForegroundColor Cyan
Write-Host ""
Write-Host "Список всех студентов:  $Server/api/students" -ForegroundColor DarkGray
Write-Host ""
