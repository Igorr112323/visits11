# Команды для PowerShell (преподаватель, Windows)

Всё, что нужно на ноутбуке. Команды можно копировать целиком и вставлять
в PowerShell (правый клик по «Пуск» → **Терминал (Windows PowerShell)** или
**Windows PowerShell**).

В репозитории есть три готовых файла-скрипта — их можно просто запускать
двойным щелчком (правый клик → «Выполнить с помощью PowerShell»):

| Файл | Что делает |
|---|---|
| `windows/Запустить-сервер.ps1` | правило брандмауэра, запуск сервера, печать адреса, открытие страницы проверки |
| `windows/Студенты.ps1` | массовая регистрация студентов (логин, ФИО, пароль) и проверка входа |
| `windows/Проверить-систему.ps1` | полная проверка: сервер, вход верный/неверный, пара, отметка, CSV |

Ниже — те же действия командами, если удобнее вводить вручную.

---

## 0. Если PowerShell не даёт запускать скрипты

Один раз на компьютере:

```powershell
Set-ExecutionPolicy -Scope CurrentUser RemoteSigned
```

На вопрос ответьте `Y` (это разрешает свои локальные скрипты, скачанные — нет).

---

## 1. Запустить сервер

```powershell
.\KubGAU-Attendance-Server.exe
```

Если хочется отдельным окном и сразу с проверкой:

```powershell
Start-Process -FilePath ".\KubGAU-Attendance-Server.exe" -WorkingDirectory (Get-Location)
Start-Sleep -Seconds 3
Invoke-RestMethod http://127.0.0.1:8000/api/health
```

Открыть страницу документации в браузере:

```powershell
Start-Process http://127.0.0.1:8000/docs
```

## 2. Правило брандмауэра (чтобы телефоны видели сервер)

Запускать **одним из двух способов**: либо в PowerShell от имени администратора,
либо так — тогда Windows сама спросит разрешение:

```powershell
New-NetFirewallRule -DisplayName "КубГАУ отметки (порт 8000)" -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8000 -Profile Private
```

Удалить правило (если больше не нужно):

```powershell
Remove-NetFirewallRule -DisplayName "КубГАУ отметки (порт 8000)"
```

## 3. Узнать адрес, который вводят телефоны

```powershell
Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.254.*" } | Select-Object IPAddress, InterfaceAlias
```

Быстрый вариант «как в приложении» (обычно самый нужный адрес):

```powershell
"http://" + (Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.254.*" } | Select-Object -First 1 -ExpandProperty IPAddress) + ":8000"
```

## 4. Завести студента с паролем (по одному)

Пароль должен быть не короче 4 символов. Меняйте логин, ФИО и пароль на свои:

```powershell
$body = @{ student_id = "ARHIPOV_II"; name = "Архипов Иван Иванович"; password = "kubgau-2026" } | ConvertTo-Json
Invoke-RestMethod -Uri http://127.0.0.1:8000/api/students/register -Method Post -ContentType "application/json; charset=utf-8" -Body ([System.Text.Encoding]::UTF8.GetBytes($body))
```

Для нескольких сразу список редактируется в файле `windows/Студенты.ps1`:

```powershell
.\windows\Студенты.ps1
```

## 5. Проверить, правильно ли студент вводит пароль

Верный пароль (должен ответить `ok: True`):

```powershell
$body = @{ student_id = "ARHIPOV_II"; password = "kubgau-2026" } | ConvertTo-Json
Invoke-RestMethod -Uri http://127.0.0.1:8000/api/students/login -Method Post -ContentType "application/json; charset=utf-8" -Body ([System.Text.Encoding]::UTF8.GetBytes($body))
```

Неверный пароль (сервер ответит ошибкой **401 wrong_password** — так и должно быть):

```powershell
$body = @{ student_id = "ARHIPOV_II"; password = "неверный" } | ConvertTo-Json
try {
    Invoke-RestMethod -Uri http://127.0.0.1:8000/api/students/login -Method Post -ContentType "application/json; charset=utf-8" -Body ([System.Text.Encoding]::UTF8.GetBytes($body))
    "ОШИБКА: сервер принял неверный пароль"
} catch {
    "Как и должно быть: $($_.Exception.Response.StatusCode.value__) — в доступе отказано"
}
```

Сменить забытый пароль студенту (просто зарегистрируйте его заново с новым паролем):

```powershell
$body = @{ student_id = "ARHIPOV_II"; name = "Архипов Иван Иванович"; password = "новый-пароль" } | ConvertTo-Json
Invoke-RestMethod -Uri http://127.0.0.1:8000/api/students/register -Method Post -ContentType "application/json; charset=utf-8" -Body ([System.Text.Encoding]::UTF8.GetBytes($body))
```

## 6. Список студентов (без паролей)

```powershell
(Invoke-RestMethod http://127.0.0.1:8000/api/students).students | Format-Table id, name, group_name, device_id
```

## 7. Создать пару и посмотреть, кто отметился

```powershell
$session = @{ subject = "Информатика"; teacher_id = "IVANOV_II"; group_name = "ИВТ-21"; minutes = 120 } | ConvertTo-Json
$created = Invoke-RestMethod -Uri http://127.0.0.1:8000/api/session -Method Post -ContentType "application/json; charset=utf-8" -Body ([System.Text.Encoding]::UTF8.GetBytes($session))
$created.session.id          # ← этот UUID показывает Android-приложение
```

Кто уже отметился в паре (подставьте UUID пары):

```powershell
$id = "ВСТАВЬТЕ-UUID-ПАРЫ"
(Invoke-RestMethod "http://127.0.0.1:8000/api/attendance/$id").present | Format-Table student_id, student_name, timestamp, verified, rssi
```

Список всех пар:

```powershell
(Invoke-RestMethod http://127.0.0.1:8000/api/sessions).sessions | Format-Table id, subject, group_name, start_time, status
```

## 8. Выгрузить ведомость в Excel (CSV)

```powershell
$id = "ВСТАВЬТЕ-UUID-ПАРЫ"
Invoke-WebRequest "http://127.0.0.1:8000/api/attendance/$id/export.csv" -OutFile "$HOME\Desktop\отметки-$id.csv"
& "$HOME\Desktop\отметки-$id.csv"
```

Файл в кодировке cp1251 с разделителем `;` — открывается в Excel двойным щелчком.

## 9. Резервная копия базы (все отметки)

```powershell
Copy-Item attendance.db "attendance-$(Get-Date -Format 'yyyy-MM-dd-HHmm').db"
Get-ChildItem *.db | Sort-Object LastWriteTime -Descending | Select-Object Name, Length, LastWriteTime
```

Где лежит база: рядом с `KubGAU-Attendance-Server.exe` (файл `attendance.db`).

## 10. Проверить всё сразу

```powershell
.\windows\Проверить-систему.ps1
```

Скрипт сам: зарегистрирует тестового студента, проверит вход с верным паролем,
убедится, что с неверным вход отклоняется, создаст пару, отправит отметку и
сохранит CSV рядом с собой.

---

## Частые вопросы

**Сервер запустился, но телефон не подключается.**
Проверьте: 1) телефоны в той же Wi-Fi сети (`ipconfig` покажет адрес ноутбука);
2) правило брандмауэра добавлено (раздел 2); 3) в приложении указан адрес
с портом, например `192.168.1.10:8000`.

**Как остановить сервер?** В его окне нажать `Ctrl + C` (или закрыть окно).

**Как сменить порт?** Запустить так: `.\KubGAU-Attendance-Server.exe 8080`
и в приложениях указывать адрес с новым портом.

**Как убрать все данные и начать заново?** Остановить сервер, удалить файл
`attendance.db` рядом с exe, запустить снова — база создастся пустой.
