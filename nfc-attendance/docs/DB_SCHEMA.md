# Схема базы данных

**СУБД:** SQLite (файл `server/attendance.db`, создаётся автоматически при первом
запуске сервера; путь можно переопределить переменной окружения `ATTENDANCE_DB`).
**Режим:** `PRAGMA foreign_keys=ON`, `PRAGMA journal_mode=WAL` — включены при каждом
подключении. Полный DDL — в `server/database.py` (константа `SCHEMA`).

```
students ──1:N── attendance ──N:1── sessions
                     │
                     └── (сверка по времени) ── terminal_taps
rejected_marks — журнал отказов (внешних ключей нет: важны и «мусорные» записи)
```

---

## `students` — студенты и привязка телефонов

| Поле | Тип | Ограничения | Назначение |
|---|---|---|---|
| `id` | TEXT | **PK** | логин студента (например `ARHIPOV_II`) |
| `name` | TEXT | NOT NULL | ФИО для журнала |
| `device_id` | TEXT | UNIQUE, NULL | привязанный телефон (генерируется один раз на iPhone) |
| `group_name` | TEXT | NULL | группа (для списка отсутствующих) |
| `created_at` | TEXT | NOT NULL | когда завели (ISO-8601) |

Привязка происходит при **первой** отметке: если у студента `device_id IS NULL`,
он записывается. Если телефон уже принадлежит другому студенту — отметка
отклоняется с `device_mismatch` и пишется в `rejected_marks`.

## `sessions` — пары

| Поле | Тип | Ограничения | Назначение |
|---|---|---|---|
| `id` | TEXT | **PK** | **случайный UUID4** — защита от заготовленных меток |
| `subject` | TEXT | NOT NULL | предмет |
| `teacher_id` | TEXT | NOT NULL | идентификатор преподавателя |
| `group_name` | TEXT | NULL | группа |
| `start_time` | TEXT | NOT NULL | начало пары (ISO-8601 с таймзоной) |
| `end_time` | TEXT | NULL | конец пары; после него отметки не принимаются (`session_expired`) |
| `status` | TEXT | NOT NULL, default `active` | `active` или `closed` |
| `created_at` | TEXT | NOT NULL | момент создания записи |

## `attendance` — отметки студентов

| Поле | Тип | Ограничения | Назначение |
|---|---|---|---|
| `id` | INTEGER | **PK AUTOINCREMENT** | |
| `student_id` | TEXT | NOT NULL, FK → `students.id` ON DELETE CASCADE | кто |
| `session_id` | TEXT | NOT NULL, FK → `sessions.id` ON DELETE CASCADE | на какой паре |
| `timestamp` | TEXT | NOT NULL | момент чтения NFC/QR (ISO-8601) |
| `status` | TEXT | NOT NULL, default `present` | статус отметки |
| `device_id` | TEXT | NULL | с какого телефона отмечено |
| `subject` | TEXT | NULL | предмет из метки (для сверки) |
| `teacher_id` | TEXT | NULL | преподаватель из метки (для сверки) |
| `source` | TEXT | NULL | `ios` (NFC или QR) |
| `verified` | INTEGER | NOT NULL, default 0 | 1 — рядом по времени было касание терминала |
| `created_at` | TEXT | NOT NULL | момент записи на сервере |

**Уникальность:** `UNIQUE (session_id, student_id)` — повторная отметка в ту же
пару невозможна физически (сервер отвечает `result = duplicate`).

## `terminal_taps` — касания, которые видел телефон преподавателя

| Поле | Тип | Ограничения | Назначение |
|---|---|---|---|
| `id` | INTEGER | **PK AUTOINCREMENT** | |
| `session_id` | TEXT | NOT NULL | пара |
| `tap_time` | TEXT | NOT NULL | момент касания (ISO-8601) |
| `result` | TEXT | NULL | `read` (NFC) или `qr` (код показан на экране) |
| `source` | TEXT | NULL | источник (`android`) |
| `created_at` | TEXT | NOT NULL | |

**Уникальность:** `UNIQUE (session_id, tap_time)` — повторная синхронизация тех же
касаний ничего не ломает (идемпотентность `/api/sync`).

По этим записям сервер считает отметку подтверждённой: если отметка попала в окно
**±`TAP_WINDOW_SECONDS`** (по умолчанию 20 секунд) от касания, `verified = 1`.
Не подтверждённые отметки перепроверяются автоматически, когда телефон
преподавателя синхронизируется (`reverify_session`).

## `rejected_marks` — журнал отклонённых попыток

| Поле | Тип | Назначение |
|---|---|---|
| `id` | INTEGER | PK AUTOINCREMENT |
| `student_id` | TEXT | кто пытался |
| `session_id` | TEXT | на какой паре |
| `device_id` | TEXT | с какого телефона |
| `reason` | TEXT | `unknown_session`, `session_closed`, `session_expired`, `subject_mismatch`, `teacher_mismatch`, `device_mismatch`, `no_device_id` |
| `payload` | TEXT | полное тело запроса (доказательство) |
| `created_at` | TEXT | когда |

## Индексы

```sql
CREATE INDEX idx_attendance_session ON attendance (session_id);
CREATE INDEX idx_attendance_student ON attendance (student_id);
CREATE INDEX idx_taps_session       ON terminal_taps (session_id);
```

---

## Типовые запросы

```sql
-- список присутствующих на паре
SELECT a.timestamp, s.name, a.verified, a.source
  FROM attendance a JOIN students s ON s.id = a.student_id
 WHERE a.session_id = 'UUID пары'
 ORDER BY a.timestamp;

-- кто отсутствует (студенты группы без отметок)
SELECT s.id, s.name FROM students s
 WHERE s.group_name = 'ИС-21'
   AND s.id NOT IN (SELECT student_id FROM attendance WHERE session_id = 'UUID пары');

-- подозрительные попытки
SELECT created_at, student_id, device_id, reason FROM rejected_marks
 WHERE session_id = 'UUID пары' ORDER BY created_at DESC;

-- отметки без подтверждения касанием
SELECT * FROM attendance WHERE session_id = 'UUID пары' AND verified = 0;
```

Очистка базы (для тестов): `ATTENDANCE_DB=test.db python -c "import database; database.reset()"`.

## Настройки через переменные окружения

| Переменная | По умолчанию | Значение |
|---|---|---|
| `ATTENDANCE_DB` | `server/attendance.db` | путь к файлу базы |
| `TAP_WINDOW_SECONDS` | `20` | окно сверки отметки с касанием, секунд |
| `SESSION_MINUTES` | `120` | длительность пары по умолчанию, минут |
| `API_KEY` | пусто | если задан — все `/api/*` требуют заголовок `X-Api-Key` |
