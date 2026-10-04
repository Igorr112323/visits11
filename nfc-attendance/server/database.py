"""
SQLite-хранилище и вся античит-логика сервера «КубГАУ · Учёт посещаемости».

Файл базы: attendance.db рядом с main.py (можно переопределить переменной
окружения ATTENDANCE_DB).

Таблицы (см. docs/DB_SCHEMA.md):
  students        — студенты и привязка телефона (device_id)
  sessions        — пары (создаются телефоном преподавателя)
  attendance      — отметки студентов (уникальность: сессия + студент)
  terminal_taps   — «касания», которые видел телефон преподавателя
  rejected_marks  — журнал отклонённых попыток (доказательная база для античита)
"""

from __future__ import annotations

import hashlib
import hmac
import os
import secrets
import sqlite3
import uuid
from datetime import datetime, timedelta, timezone
from typing import Any, Optional

# --------------------------------------------------------------------- настройки

DB_PATH = os.environ.get(
    "ATTENDANCE_DB",
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "attendance.db"),
)

# Насколько отметка должна совпасть по времени с касанием терминала, чтобы
# считаться подтверждённой преподавателем (± секунд).
TAP_WINDOW_SECONDS = int(os.environ.get("TAP_WINDOW_SECONDS", "20"))

# Ниже этого уровня сигнала (дБм) отметка считается неподтверждённой, даже если
# пришло касание: значит, студент был далеко (коридор, соседняя аудитория).
WEAK_RSSI_FLOOR = int(os.environ.get("WEAK_RSSI_FLOOR", "-80"))

# Сколько живёт сессия по умолчанию (минут) и максимум.
DEFAULT_SESSION_MINUTES = int(os.environ.get("SESSION_MINUTES", "120"))

STATUS_ACTIVE = "active"
STATUS_CLOSED = "closed"

# ------------------------------------------------------------------------ схема

SCHEMA = """
CREATE TABLE IF NOT EXISTS students (
    id          TEXT PRIMARY KEY,           -- логин/идентификатор студента
    name        TEXT NOT NULL,              -- ФИО
    device_id   TEXT UNIQUE,                -- привязанный телефон (генерируется 1 раз)
    group_name  TEXT,
    password_hash TEXT,                     -- PBKDF2-SHA256 (пароль студента), NULL — вход без пароля
    created_at  TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS sessions (
    id          TEXT PRIMARY KEY,           -- UUID, генерируется случайно
    subject     TEXT NOT NULL,
    teacher_id  TEXT NOT NULL,
    group_name  TEXT,
    start_time  TEXT NOT NULL,
    end_time    TEXT,
    status      TEXT NOT NULL DEFAULT 'active',
    created_at  TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS attendance (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    student_id  TEXT NOT NULL,
    session_id  TEXT NOT NULL,
    timestamp   TEXT NOT NULL,
    status      TEXT NOT NULL DEFAULT 'present',
    device_id   TEXT,
    subject     TEXT,
    teacher_id  TEXT,
    source      TEXT,
    verified    INTEGER NOT NULL DEFAULT 0,
    rssi        INTEGER,                    -- уровень сигнала BLE, дБм (для разбора спорных отметок)
    created_at  TEXT NOT NULL,
    UNIQUE (session_id, student_id),
    FOREIGN KEY (student_id) REFERENCES students (id) ON DELETE CASCADE,
    FOREIGN KEY (session_id) REFERENCES sessions (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS terminal_taps (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id  TEXT NOT NULL,
    tap_time    TEXT NOT NULL,
    result      TEXT,                       -- read (NFC-касание) | ble (Bluetooth-касание)
    source      TEXT,
    device_id   TEXT,                       -- телефон, который коснулся (для сверки отметки)
    rssi        INTEGER,                    -- уровень сигнала, дБм — терминал не измеряет, приходит от клиента
    created_at  TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS rejected_marks (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    student_id  TEXT,
    session_id  TEXT,
    device_id   TEXT,
    reason      TEXT NOT NULL,
    payload     TEXT,
    created_at  TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_attendance_session ON attendance (session_id);
CREATE INDEX IF NOT EXISTS idx_attendance_student ON attendance (student_id);
CREATE INDEX IF NOT EXISTS idx_taps_session ON terminal_taps (session_id);
-- Уникальность касаний: одна и та же пара+время+устройство. Устройство в индексе,
-- потому что два студента могут коснуться в одну и ту же секунду.
CREATE UNIQUE INDEX IF NOT EXISTS idx_taps_unique
    ON terminal_taps (session_id, tap_time, COALESCE(device_id, ''));
"""

# ------------------------------------------------------------------ утилиты времени


def now() -> datetime:
    """Текущее локальное время с таймзоной."""
    return datetime.now(timezone.utc).astimezone()


def now_iso() -> str:
    """Время в ISO-8601 (секунды) — так его хранит база."""
    return now().isoformat(timespec="seconds")


def parse_iso(value: Optional[str]) -> Optional[datetime]:
    """Разбор ISO-8601 из приложений (поддерживает 'Z' и '+03:00')."""
    if not value:
        return None
    text = value.strip().replace("Z", "+00:00")
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=now().tzinfo)
    return parsed


# -------------------------------------------------------------------- подключение


def connect() -> sqlite3.Connection:
    connection = sqlite3.connect(DB_PATH, timeout=15)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA foreign_keys=ON")
    connection.execute("PRAGMA journal_mode=WAL")
    return connection


def init() -> None:
    """Создаёт схему и дотягивает старые базы до текущей версии."""
    with connect() as connection:
        connection.executescript(SCHEMA)
    _migrate()


def _columns(connection: sqlite3.Connection, table: str) -> set[str]:
    return {row["name"] for row in connection.execute(f"PRAGMA table_info({table})")}


def _migrate() -> None:
    """
    Приводит базу к текущей схеме (SQLite не умеет ADD COLUMN IF NOT EXISTS).

    Что делает:
      1. добавляет колонки, появившиеся в новых версиях (RSSI и device_id);
      2. пересобирает terminal_taps, если в ней осталась старая уникальность
         (session_id, tap_time): она мешала двум студентам коснуться в одну секунду.
    """
    with connect() as connection:
        for table, columns in {
            "students": [("password_hash", "TEXT")],
            "attendance": [("rssi", "INTEGER")],
            "terminal_taps": [("device_id", "TEXT"), ("rssi", "INTEGER")],
        }.items():
            existing = _columns(connection, table)
            for name, type_name in columns:
                if name not in existing:
                    connection.execute(f"ALTER TABLE {table} ADD COLUMN {name} {type_name}")

        row = connection.execute(
            "SELECT sql FROM sqlite_master WHERE type='table' AND name='terminal_taps'",
        ).fetchone()
        table_sql = (row["sql"] or "") if row else ""
        if "UNIQUE" in table_sql.upper():
            connection.executescript("""
                ALTER TABLE terminal_taps RENAME TO terminal_taps_legacy;
                CREATE TABLE terminal_taps (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id  TEXT NOT NULL,
                    tap_time    TEXT NOT NULL,
                    result      TEXT,
                    source      TEXT,
                    device_id   TEXT,
                    rssi        INTEGER,
                    created_at  TEXT NOT NULL
                );
                INSERT INTO terminal_taps (id, session_id, tap_time, result, source, created_at)
                    SELECT id, session_id, tap_time, result, source, created_at
                      FROM terminal_taps_legacy;
                DROP TABLE terminal_taps_legacy;
                CREATE INDEX IF NOT EXISTS idx_taps_session ON terminal_taps (session_id);
                CREATE UNIQUE INDEX IF NOT EXISTS idx_taps_unique
                    ON terminal_taps (session_id, tap_time, COALESCE(device_id, ''));
            """)


def public_student(row: Optional[sqlite3.Row | dict[str, Any]]) -> Optional[dict[str, Any]]:
    """Строка студента без служебных полей (хэш пароля наружу не отдаём)."""
    if row is None:
        return None
    data = dict(row)
    data.pop("password_hash", None)
    return data


def hash_password(password: str, iterations: int = 120_000) -> str:
    """
    Пароль студента → строка для базы: pbkdf2_sha256$итерации$соль$хэш.

    Сам пароль не хранится и не передаётся никуда, кроме запроса входа.
    """
    salt = secrets.token_hex(16)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt.encode("utf-8"), iterations)
    return f"pbkdf2_sha256${iterations}${salt}${digest.hex()}"


def verify_password(password: str, stored: Optional[str]) -> bool:
    """Проверка пароля по сохранённому хэшу (устойчиво к подбору по времени)."""
    if not stored:
        return False
    try:
        algorithm, iterations, salt, expected = stored.split("$")
        if algorithm != "pbkdf2_sha256":
            return False
        digest = hashlib.pbkdf2_hmac(
            "sha256", password.encode("utf-8"), salt.encode("utf-8"), int(iterations)
        )
        return hmac.compare_digest(digest.hex(), expected)
    except (ValueError, TypeError):
        return False


def rows_to_dicts(rows: list[sqlite3.Row]) -> list[dict[str, Any]]:
    return [dict(row) for row in rows]


# ------------------------------------------------------------------------ студенты


def upsert_student(
    student_id: str,
    name: Optional[str] = None,
    group_name: Optional[str] = None,
    password: Optional[str] = None,
) -> dict[str, Any]:
    """
    Создаёт студента или обновляет ФИО/группу/пароль (device_id не трогает).

    Если передан пароль — сохраняем его хэш: по нему приложение проверяет вход.
    """
    student_id = student_id.strip()
    password_hash = hash_password(password) if password else None
    with connect() as connection:
        row = connection.execute(
            "SELECT * FROM students WHERE id = ?", (student_id,)
        ).fetchone()
        if row is None:
            connection.execute(
                """INSERT INTO students (id, name, device_id, group_name, password_hash, created_at)
                   VALUES (?, ?, NULL, ?, ?, ?)""",
                (student_id, name or student_id, group_name, password_hash, now_iso()),
            )
        else:
            connection.execute(
                """UPDATE students
                      SET name = COALESCE(?, name),
                          group_name = COALESCE(?, group_name),
                          password_hash = COALESCE(?, password_hash)
                    WHERE id = ?""",
                (name, group_name, password_hash, student_id),
            )
        return public_student(connection.execute(
            "SELECT * FROM students WHERE id = ?", (student_id,)
        ).fetchone())


def set_student_password(student_id: str, password: str) -> Optional[dict[str, Any]]:
    """Сменить пароль студента (например, преподаватель сбрасывает забытый)."""
    with connect() as connection:
        connection.execute(
            "UPDATE students SET password_hash = ? WHERE id = ?",
            (hash_password(password), student_id.strip()),
        )
        return public_student(connection.execute(
            "SELECT * FROM students WHERE id = ?", (student_id.strip(),)
        ).fetchone())


def has_password(student_id: str) -> bool:
    """Задан ли у студента пароль (нужно, чтобы отличить «нет пароля» от «неверный»)."""
    with connect() as connection:
        row = connection.execute(
            "SELECT password_hash FROM students WHERE id = ?", (student_id.strip(),)
        ).fetchone()
    return bool(row and row["password_hash"])


def check_student_password(student_id: str, password: str) -> Optional[bool]:
    """
    Проверка логина и пароля:
      • None  — такого логина нет;
      • False — пароль неверный (или у студента он ещё не задан);
      • True  — пароль верный.
    """
    with connect() as connection:
        row = connection.execute(
            "SELECT password_hash FROM students WHERE id = ?", (student_id.strip(),)
        ).fetchone()
    if row is None:
        return None
    return verify_password(password, row["password_hash"])


def get_student(student_id: str) -> Optional[dict[str, Any]]:
    with connect() as connection:
        row = connection.execute(
            "SELECT * FROM students WHERE id = ?", (student_id,)
        ).fetchone()
    return public_student(row)


def list_students() -> list[dict[str, Any]]:
    with connect() as connection:
        rows = connection.execute(
            "SELECT * FROM students ORDER BY name COLLATE NOCASE"
        ).fetchall()
    return [public_student(row) for row in rows]  # type: ignore[misc]


def find_student_by_device(device_id: str) -> Optional[dict[str, Any]]:
    """Кто владелец телефона (device_id) — нужно для античита."""
    with connect() as connection:
        row = connection.execute(
            "SELECT * FROM students WHERE device_id = ?", (device_id,)
        ).fetchone()
    return public_student(row)


def bind_device(student_id: str, device_id: str) -> bool:
    """
    Первая привязка телефона к студенту.
    False — телефон уже привязан к другому студенту (гонка/подмена).
    """
    try:
        with connect() as connection:
            connection.execute(
                "UPDATE students SET device_id = ? WHERE id = ? AND device_id IS NULL",
                (device_id, student_id),
            )
    except sqlite3.IntegrityError:
        return False
    return True


# ------------------------------------------------------------------------ сессии


def create_session(
    subject: str,
    teacher_id: str,
    group_name: Optional[str] = None,
    minutes: int = DEFAULT_SESSION_MINUTES,
    session_id: Optional[str] = None,
) -> dict[str, Any]:
    """Создаёт пару. session_id — UUID4 (случайный, неповторяющийся)."""
    session_id = session_id or str(uuid.uuid4())
    start = now()
    end = start + timedelta(minutes=minutes)
    with connect() as connection:
        connection.execute(
            """INSERT INTO sessions
                   (id, subject, teacher_id, group_name, start_time, end_time, status, created_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
            (
                session_id,
                subject.strip(),
                teacher_id.strip(),
                (group_name or "").strip() or None,
                start.isoformat(timespec="seconds"),
                end.isoformat(timespec="seconds"),
                STATUS_ACTIVE,
                now_iso(),
            ),
        )
    return get_session(session_id)  # type: ignore[return-value]


def get_session(session_id: str) -> Optional[dict[str, Any]]:
    with connect() as connection:
        row = connection.execute(
            "SELECT * FROM sessions WHERE id = ?", (session_id,)
        ).fetchone()
    return dict(row) if row else None


def list_sessions(
    active_only: bool = False, limit: int = 50
) -> list[dict[str, Any]]:
    query = "SELECT * FROM sessions"
    parameters: list[Any] = []
    if active_only:
        query += " WHERE status = ?"
        parameters.append(STATUS_ACTIVE)
    query += " ORDER BY start_time DESC LIMIT ?"
    parameters.append(limit)
    with connect() as connection:
        rows = connection.execute(query, parameters).fetchall()
    return rows_to_dicts(rows)


def close_session(session_id: str) -> Optional[dict[str, Any]]:
    with connect() as connection:
        connection.execute(
            "UPDATE sessions SET status = ?, end_time = ? WHERE id = ?",
            (STATUS_CLOSED, now_iso(), session_id),
        )
    return get_session(session_id)


def session_is_open(session: dict[str, Any]) -> tuple[bool, str]:
    """Открыта ли сессия (не закрыта и не истекла)."""
    if session["status"] != STATUS_ACTIVE:
        return False, "session_closed"
    end = parse_iso(session["end_time"])
    if end is not None and now() > end:
        return False, "session_expired"
    return True, ""


# ------------------------------------------------------------------ отметки (античит)


def _log_rejection(
    connection: sqlite3.Connection,
    reason: str,
    student_id: Optional[str],
    session_id: Optional[str],
    device_id: Optional[str],
    payload: str,
) -> None:
    connection.execute(
        """INSERT INTO rejected_marks
               (student_id, session_id, device_id, reason, payload, created_at)
           VALUES (?, ?, ?, ?, ?, ?)""",
        (student_id, session_id, device_id, reason, payload, now_iso()),
    )


def is_verified_by_terminal(
    session_id: str,
    timestamp: str,
    device_id: Optional[str] = None,
) -> bool:
    """
    Было ли касание на телефоне преподавателя рядом по времени с отметкой.

    Если у касания записан device_id (BLE-касание), он должен совпасть с телефоном
    студента: иначе отметка с другого телефона не подтверждается.
    """
    moment = parse_iso(timestamp)
    if moment is None:
        return False
    window = timedelta(seconds=TAP_WINDOW_SECONDS)
    with connect() as connection:
        rows = connection.execute(
            "SELECT tap_time, device_id FROM terminal_taps WHERE session_id = ?",
            (session_id,),
        ).fetchall()
    for row in rows:
        tap = parse_iso(row["tap_time"])
        if tap is None or abs(tap - moment) > window:
            continue
        tap_device = row["device_id"]
        if device_id and tap_device and tap_device != device_id:
            continue  # касался другой телефон
        return True
    return False


def add_attendance(
    session_id: str,
    student_id: str,
    device_id: str,
    timestamp: Optional[str] = None,
    subject: Optional[str] = None,
    teacher_id: Optional[str] = None,
    student_name: Optional[str] = None,
    source: str = "ios",
    rssi: Optional[int] = None,
) -> tuple[int, dict[str, Any]]:
    """
    Принимает отметку студента. Возвращает (HTTP-код, тело ответа).

    Проверки по порядку:
      1. сессия существует             → иначе 404 unknown_session
      2. сессия открыта                → иначе 409 session_closed / session_expired
      3. предмет и преподаватель из
         NDEF-метки совпадают с парой  → иначе 409 subject_mismatch / teacher_mismatch
      4. повторная отметка             → 200 result=duplicate (блокируется)
      5. телефон привязан к этому
         студенту                      → иначе 409 device_mismatch
      6. сверка с касанием терминала   → verified=0/1 (пометка «не подтверждено»)
    """
    payload_text = str(
        {
            "session_id": session_id,
            "student_id": student_id,
            "device_id": device_id,
            "timestamp": timestamp,
            "subject": subject,
            "teacher_id": teacher_id,
            "source": source,
            "rssi": rssi,
        }
    )

    session = get_session(session_id)
    if session is None:
        with connect() as connection:
            _log_rejection(connection, "unknown_session", student_id, session_id,
                           device_id, payload_text)
        return 404, {"ok": False, "reason": "unknown_session",
                     "detail": "Такой пары нет на сервере — возможно, телефон преподавателя не синхронизирован."}

    open_ok, reason = session_is_open(session)
    if not open_ok:
        with connect() as connection:
            _log_rejection(connection, reason, student_id, session_id, device_id, payload_text)
        detail = ("Пара уже закрыта преподавателем." if reason == "session_closed"
                  else "Время пары истекло.")
        return 409, {"ok": False, "reason": reason, "detail": detail}

    # 3. сверка данных из NDEF-метки с данными пары (защита от подделки метки)
    if subject and subject.strip() and subject.strip() != session["subject"]:
        with connect() as connection:
            _log_rejection(connection, "subject_mismatch", student_id, session_id,
                           device_id, payload_text)
        return 409, {"ok": False, "reason": "subject_mismatch",
                     "detail": f"В метке предмет «{subject}», а пара — «{session['subject']}»."}
    if teacher_id and teacher_id.strip() and teacher_id.strip() != session["teacher_id"]:
        with connect() as connection:
            _log_rejection(connection, "teacher_mismatch", student_id, session_id,
                           device_id, payload_text)
        return 409, {"ok": False, "reason": "teacher_mismatch",
                     "detail": "В метке другой преподаватель — отметка не принята."}

    student = upsert_student(student_id, name=student_name)
    mark_time = timestamp or now_iso()

    # 4. повторная отметка в ту же пару
    with connect() as connection:
        existing = connection.execute(
            "SELECT * FROM attendance WHERE session_id = ? AND student_id = ?",
            (session_id, student_id),
        ).fetchone()
    if existing is not None:
        return 200, {
            "ok": True,
            "result": "duplicate",
            "detail": "Отметка уже была — повторная не создана.",
            "student": {"id": student["id"], "name": student["name"]},
            "session": {"id": session["id"], "subject": session["subject"]},
            "mark": dict(existing),
            "present_count": present_count(session_id),
        }

    # 5. телефон должен принадлежать этому студенту
    if not device_id:
        with connect() as connection:
            _log_rejection(connection, "no_device_id", student_id, session_id,
                           device_id, payload_text)
        return 409, {"ok": False, "reason": "no_device_id",
                     "detail": "Приложение не передало device_id."}

    owner = find_student_by_device(device_id)
    if owner is None:
        # телефон ещё ничей — привязываем к этому студенту
        if not bind_device(student_id, device_id):
            owner = find_student_by_device(device_id)
        student = get_student(student_id) or student

    if owner is not None and owner["id"] != student_id:
        with connect() as connection:
            _log_rejection(connection, "device_mismatch", student_id, session_id,
                           device_id, payload_text)
        return 409, {"ok": False, "reason": "device_mismatch",
                     "detail": "Этот телефон привязан к другому студенту. Отметка заблокирована.",
                     "device_owner": {"id": owner["id"], "name": owner["name"]}}

    # 6. сверили с касанием на телефоне преподавателя (и с сигналом, если он известен)
    verified = 1 if is_verified_by_terminal(session_id, mark_time, device_id) else 0
    weak_signal = rssi is not None and rssi < WEAK_RSSI_FLOOR
    if weak_signal:
        verified = 0  # сигнал слишком слабый: студент был далеко от терминала

    with connect() as connection:
        cursor = connection.execute(
            """INSERT INTO attendance
                   (student_id, session_id, timestamp, status, device_id,
                    subject, teacher_id, source, verified, rssi, created_at)
               VALUES (?, ?, ?, 'present', ?, ?, ?, ?, ?, ?, ?)""",
            (student_id, session_id, mark_time, device_id, subject,
             teacher_id, source, verified, rssi, now_iso()),
        )
        mark_id = cursor.lastrowid
        row = connection.execute(
            "SELECT * FROM attendance WHERE id = ?", (mark_id,)
        ).fetchone()

    return 200, {
        "ok": True,
        "result": "accepted" if verified else "accepted_unverified",
        "detail": (
            "Отметка принята и подтверждена касанием терминала."
            if verified else
            ("Отметка принята, но сигнал слишком слабый: похоже, телефон был далеко "
             "от терминала. Помечена как неподтверждённая." if weak_signal else
             "Отметка принята, но касания терминала рядом по времени не найдено — "
             "помечена как неподтверждённая.")
        ),
        "student": {"id": student["id"], "name": student["name"]},
        "session": {"id": session["id"], "subject": session["subject"],
                    "teacher_id": session["teacher_id"]},
        "mark": dict(row) if row else None,
        "verified": bool(verified),
        "weak_signal": weak_signal,
        "rssi": rssi,
        "present_count": present_count(session_id),
    }


def present_count(session_id: str) -> int:
    with connect() as connection:
        row = connection.execute(
            "SELECT COUNT(*) AS n FROM attendance WHERE session_id = ?",
            (session_id,),
        ).fetchone()
    return int(row["n"])


def list_attendance(session_id: str) -> list[dict[str, Any]]:
    """Список присутствующих (для GET /api/attendance/{session_id})."""
    with connect() as connection:
        rows = connection.execute(
            """SELECT a.id, a.student_id, COALESCE(s.name, a.student_id) AS student_name,
                      a.timestamp, a.status, a.verified, a.source, a.device_id, a.rssi
                 FROM attendance a
                 LEFT JOIN students s ON s.id = a.student_id
                WHERE a.session_id = ?
                ORDER BY a.timestamp""",
            (session_id,),
        ).fetchall()
    return rows_to_dicts(rows)


def absent_students(session_id: str, group_name: Optional[str]) -> list[dict[str, Any]]:
    """Кто из группы не отмечен (если у студентов задана группа)."""
    with connect() as connection:
        rows = connection.execute(
            """SELECT s.id, s.name
                 FROM students s
                WHERE (? IS NULL OR s.group_name = ?)
                  AND s.id NOT IN (SELECT student_id FROM attendance WHERE session_id = ?)
                ORDER BY s.name COLLATE NOCASE""",
            (group_name, group_name, session_id),
        ).fetchall()
    return rows_to_dicts(rows)


def list_rejected(session_id: str) -> list[dict[str, Any]]:
    with connect() as connection:
        rows = connection.execute(
            """SELECT student_id, device_id, reason, created_at
                 FROM rejected_marks WHERE session_id = ?
                ORDER BY created_at DESC""",
            (session_id,),
        ).fetchall()
    return rows_to_dicts(rows)


# ------------------------------------------------------- синхронизация с терминалом


def add_tap(session_id: str, tap_time: str, result: Optional[str] = None,
            source: Optional[str] = None, device_id: Optional[str] = None,
            rssi: Optional[int] = None) -> bool:
    """
    Касание, которое видел телефон преподавателя (дубли игнорируются).

    device_id заполняется для BLE-касаний: тогда сервер сверит, что отметку
    прислал именно тот телефон, который коснулся терминала.
    """
    with connect() as connection:
        try:
            connection.execute(
                """INSERT INTO terminal_taps
                       (session_id, tap_time, result, source, device_id, rssi, created_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?)""",
                (session_id, tap_time, result, source, device_id, rssi, now_iso()),
            )
        except sqlite3.IntegrityError:
            return False
    return True


def reverify_session(session_id: str) -> int:
    """После прихода касаний перепроверяет неподтверждённые отметки."""
    updated = 0
    for mark in list_attendance(session_id):
        if mark["verified"]:
            continue
        if is_verified_by_terminal(session_id, mark["timestamp"], mark.get("device_id")):
            with connect() as connection:
                connection.execute(
                    "UPDATE attendance SET verified = 1 WHERE id = ?", (mark["id"],)
                )
            updated += 1
    return updated


def sync_teacher(
    teacher_id: str,
    sessions: list[dict[str, Any]],
    taps: list[dict[str, Any]],
) -> dict[str, Any]:
    """
    Приём данных с телефона преподавателя:
      • сессии создаются, если их ещё нет (idempotent);
      • касания терминала сохраняются — по ним подтверждаются отметки студентов.
    """
    created_sessions = 0
    for item in sessions:
        session_id = str(item.get("id") or item.get("session_id") or "").strip()
        if not session_id:
            continue
        if get_session(session_id) is not None:
            continue
        start_time = item.get("start_time") or now_iso()
        minutes = int(item.get("minutes") or DEFAULT_SESSION_MINUTES)
        start_dt = parse_iso(start_time) or now()
        end_time = item.get("end_time") or (
            start_dt + timedelta(minutes=minutes)
        ).isoformat(timespec="seconds")
        with connect() as connection:
            connection.execute(
                """INSERT OR IGNORE INTO sessions
                       (id, subject, teacher_id, group_name, start_time, end_time, status, created_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                (
                    session_id,
                    str(item.get("subject") or "Без названия"),
                    str(item.get("teacher_id") or teacher_id or "unknown"),
                    item.get("group_name"),
                    start_time,
                    end_time,
                    str(item.get("status") or STATUS_ACTIVE),
                    now_iso(),
                ),
            )
        created_sessions += 1

    added_taps = 0
    touched_sessions: set[str] = set()
    for tap in taps:
        session_id = str(tap.get("session_id") or "").strip()
        tap_time = str(tap.get("tap_time") or tap.get("timestamp") or "").strip()
        if not session_id or not tap_time:
            continue
        rssi = tap.get("rssi")
        if add_tap(
            session_id,
            tap_time,
            tap.get("result"),
            tap.get("source"),
            tap.get("device_id"),
            int(rssi) if rssi is not None else None,
        ):
            added_taps += 1
            touched_sessions.add(session_id)

    verified = sum(reverify_session(session_id) for session_id in touched_sessions)

    return {
        "ok": True,
        "sessions_created": created_sessions,
        "sessions_total": len(sessions),
        "taps_added": added_taps,
        "taps_total": len(taps),
        "marks_verified": verified,
    }


# ------------------------------------------------------------------- обслуживание


def reset() -> None:
    """Полная очистка базы (для тестов: ATTENDANCE_DB=test.db python -c 'import database; database.reset()')."""
    with connect() as connection:
        connection.executescript(
            """
            DROP TABLE IF EXISTS attendance;
            DROP TABLE IF EXISTS terminal_taps;
            DROP TABLE IF EXISTS rejected_marks;
            DROP TABLE IF EXISTS sessions;
            DROP TABLE IF EXISTS students;
            """
        )
    init()


def stats() -> dict[str, int]:
    with connect() as connection:
        result = {}
        for table in ("students", "sessions", "attendance", "terminal_taps",
                      "rejected_marks"):
            row = connection.execute(f"SELECT COUNT(*) AS n FROM {table}").fetchone()
            result[table] = int(row["n"])
    return result
