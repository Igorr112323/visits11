"""
КубГАУ · Учёт посещаемости — локальный сервер.

Запуск:
    pip install -r requirements.txt
    uvicorn main:app --host 0.0.0.0 --port 8000

Или просто:  python main.py

Сервер работает только в локальной сети: к нему обращаются
  • телефон преподавателя (Android, HCE) — создаёт пары и присылает касания;
  • телефоны студентов (iOS, CoreNFC) — присылают отметки.

Swagger-документация: http://<IP ноутбука>:8000/docs
"""

from __future__ import annotations

import csv
import io
import os
import uuid
from contextlib import asynccontextmanager
from datetime import datetime
from typing import Any, Optional

from fastapi import FastAPI, Header, HTTPException, Query, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse, JSONResponse, PlainTextResponse, Response
from pydantic import BaseModel, Field, field_validator

import database as db

# ------------------------------------------------------------------- приложение

API_KEY = os.environ.get("API_KEY", "").strip()  # пусто → без аутентификации


@asynccontextmanager
async def lifespan(_: FastAPI):
    """На старте создаём схему SQLite."""
    db.init()
    yield


app = FastAPI(
    title="КубГАУ · Учёт посещаемости",
    description="Локальный сервер отметок посещаемости (FastAPI + SQLite).",
    version="1.0.0",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


def check_key(api_key: Optional[str]) -> None:
    """Простая защита: если API_KEY задан, все /api/* требуют его в X-Api-Key."""
    if API_KEY and api_key != API_KEY:
        raise HTTPException(status_code=401, detail="Неверный или отсутствующий X-Api-Key")


# ----------------------------------------------------------------------- модели


class SessionCreate(BaseModel):
    subject: str = Field(min_length=1, max_length=200)
    teacher_id: str = Field(min_length=1, max_length=120)
    group_name: Optional[str] = Field(default=None, max_length=120)
    minutes: int = Field(default=db.DEFAULT_SESSION_MINUTES, ge=1, le=24 * 60)
    session_id: Optional[str] = Field(
        default=None,
        description="UUID, сгенерированный телефоном преподавателя; если не передан — сгенерирует сервер",
    )

    @field_validator("session_id")
    @classmethod
    def validate_session_id(cls, value: Optional[str]) -> Optional[str]:
        if value is None:
            return None
        value = value.strip()
        try:
            uuid.UUID(value)
        except ValueError as error:
            raise ValueError("session_id должен быть UUID (он защищает от заранее заготовленных меток)") from error
        return value


class AttendanceCreate(BaseModel):
    session_id: str = Field(min_length=8, max_length=64)
    student_id: str = Field(min_length=1, max_length=120)
    device_id: str = Field(min_length=4, max_length=200)
    timestamp: str = Field(description="ISO-8601, момент чтения NFC (например 2026-10-04T10:15:00+03:00)")
    subject: Optional[str] = Field(default=None, max_length=200)
    teacher_id: Optional[str] = Field(default=None, max_length=120)
    student_name: Optional[str] = Field(default=None, max_length=200)
    source: str = Field(default="ios", max_length=20)
    rssi: Optional[int] = Field(
        default=None,
        ge=-127,
        le=0,
        description="Уровень сигнала BLE в дБм (заполняют приложения, отметившиеся по Bluetooth)",
    )


class TapCreate(BaseModel):
    session_id: str = Field(min_length=8, max_length=64)
    tap_time: str
    result: Optional[str] = Field(default=None, max_length=40)
    source: Optional[str] = Field(default="android", max_length=20)
    device_id: Optional[str] = Field(
        default=None,
        max_length=200,
        description="Телефон, который коснулся терминала (для BLE-касаний)",
    )
    rssi: Optional[int] = Field(default=None, ge=-127, le=0)


class SessionSync(BaseModel):
    """Телефон преподавателя присылает пары и касания одним запросом."""

    teacher_id: Optional[str] = None
    sessions: list[dict[str, Any]] = Field(default_factory=list)
    taps: list[dict[str, Any]] = Field(default_factory=list)


class StudentCreate(BaseModel):
    student_id: str = Field(min_length=1, max_length=120)
    name: Optional[str] = Field(default=None, max_length=200)
    group_name: Optional[str] = Field(default=None, max_length=120)


# ------------------------------------------------------------------- служебные


@app.get("/", response_class=HTMLResponse, include_in_schema=False)
def index() -> str:
    """Страница-визитка: открывается с любого телефона в сети."""
    present = db.stats()
    rows = "".join(
        f"<tr><td>{name}</td><td>{value}</td></tr>" for name, value in present.items()
    )
    return f"""<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>КубГАУ · Сервер посещаемости</title>
<style>
  body {{ font-family: system-ui, sans-serif; background:#111; color:#eee; margin:0; padding:24px; }}
  h1 {{ font-size:20px; }} table {{ border-collapse:collapse; margin-top:12px; }}
  td {{ border-bottom:1px solid #333; padding:6px 16px 6px 0; }}
  code {{ background:#222; padding:2px 6px; border-radius:6px; }}
  ul {{ line-height:1.7; }}
</style>
</head>
<body>
  <h1>КубГАУ · Сервер посещаемости работает</h1>
  <p>Локальная сеть, без интернета. Всё в порядке — телефоны вас видят.</p>
  <table>{rows}</table>
  <ul>
    <li><a href="/docs">Swagger-документация (/docs)</a></li>
    <li><code>POST /api/session</code> — создать пару</li>
    <li><code>POST /api/attendance</code> — прислать отметку</li>
    <li><code>GET /api/attendance/{{session_id}}</code> — список присутствующих</li>
    <li><code>GET /api/sessions</code> — список пар</li>
  </ul>
</body>
</html>"""


@app.get("/api/health")
def health() -> dict[str, Any]:
    return {
        "app": "visits11-local-server",
        "ok": True,
        "time": db.now_iso(),
        "db": db.DB_PATH,
        "stats": db.stats(),
    }


# --------------------------------------------------------------------- студенты


@app.post("/api/students/register")
def register_student(
    payload: StudentCreate,
    x_api_key: Optional[str] = Header(default=None, alias="X-Api-Key"),
) -> dict[str, Any]:
    """Регистрирует студента (login → ФИО, группа). device_id привяжется при первой отметке."""
    check_key(x_api_key)
    student = db.upsert_student(payload.student_id, payload.name, payload.group_name)
    return {"ok": True, "student": student}


@app.get("/api/students")
def get_students(
    x_api_key: Optional[str] = Header(default=None, alias="X-Api-Key"),
) -> dict[str, Any]:
    check_key(x_api_key)
    return {"ok": True, "students": db.list_students()}


# ------------------------------------------------------------------------ пары


@app.post("/api/session")
def create_session(
    payload: SessionCreate,
    x_api_key: Optional[str] = Header(default=None, alias="X-Api-Key"),
) -> dict[str, Any]:
    """
    Создать пару. session_id — случайный UUID: метка, записанная студентом
    заранее, не подойдёт ни к одной другой паре.
    """
    check_key(x_api_key)
    session = db.create_session(
        subject=payload.subject,
        teacher_id=payload.teacher_id,
        group_name=payload.group_name,
        minutes=payload.minutes,
        session_id=payload.session_id,
    )
    return {"ok": True, "session": session}


@app.get("/api/sessions")
def get_sessions(
    active_only: bool = Query(default=False),
    limit: int = Query(default=50, ge=1, le=500),
    x_api_key: Optional[str] = Header(default=None, alias="X-Api-Key"),
) -> dict[str, Any]:
    """Список пар (свежие сверху)."""
    check_key(x_api_key)
    sessions = db.list_sessions(active_only=active_only, limit=limit)
    for session in sessions:
        session["present_count"] = db.present_count(session["id"])
        open_ok, _ = db.session_is_open(session)
        session["is_open"] = open_ok
    return {"ok": True, "sessions": sessions}


@app.get("/api/session/{session_id}")
def get_session(
    session_id: str,
    x_api_key: Optional[str] = Header(default=None, alias="X-Api-Key"),
) -> dict[str, Any]:
    check_key(x_api_key)
    session = db.get_session(session_id)
    if session is None:
        raise HTTPException(status_code=404, detail="Пара не найдена")
    open_ok, reason = db.session_is_open(session)
    session["is_open"] = open_ok
    session["closed_reason"] = reason
    session["present_count"] = db.present_count(session_id)
    return {"ok": True, "session": session}


@app.post("/api/session/{session_id}/close")
def close_session(
    session_id: str,
    x_api_key: Optional[str] = Header(default=None, alias="X-Api-Key"),
) -> dict[str, Any]:
    """Закрыть пару: после этого новые отметки не принимаются (преподаватель)."""
    check_key(x_api_key)
    session = db.get_session(session_id)
    if session is None:
        raise HTTPException(status_code=404, detail="Пара не найдена")
    session = db.close_session(session_id)
    return {"ok": True, "session": session}


# --------------------------------------------------------------------- отметки


@app.post("/api/attendance")
def create_attendance(
    payload: AttendanceCreate,
    x_api_key: Optional[str] = Header(default=None, alias="X-Api-Key"),
) -> JSONResponse:
    """
    Принять отметку студента (iPhone прочитал NDEF-метку и прислал данные).

    Возможные ответы:
      • 200 accepted            — принята и подтверждена касанием терминала;
      • 200 accepted_unverified — принята, но касания на телефоне преподавателя рядом по времени не было;
      • 200 duplicate           — повторная отметка в ту же пару (не создаётся);
      • 404 unknown_session     — пары нет на сервере;
      • 409 *_mismatch / session_closed / device_mismatch — отказ (см. reason).
    """
    check_key(x_api_key)
    code, body = db.add_attendance(
        session_id=payload.session_id,
        student_id=payload.student_id,
        device_id=payload.device_id,
        timestamp=payload.timestamp,
        subject=payload.subject,
        teacher_id=payload.teacher_id,
        student_name=payload.student_name,
        source=payload.source,
        rssi=payload.rssi,
    )
    return JSONResponse(status_code=code, content=body)


@app.get("/api/attendance/{session_id}")
def get_attendance(
    session_id: str,
    x_api_key: Optional[str] = Header(default=None, alias="X-Api-Key"),
) -> dict[str, Any]:
    """Список присутствующих на паре + журнал отклонённых попыток."""
    check_key(x_api_key)
    session = db.get_session(session_id)
    if session is None:
        raise HTTPException(status_code=404, detail="Пара не найдена")
    marks = db.list_attendance(session_id)
    return {
        "ok": True,
        "session": session,
        "present_count": len(marks),
        "present": marks,
        "absent": db.absent_students(session_id, session.get("group_name")),
        "rejected": db.list_rejected(session_id),
    }


@app.get("/api/attendance/{session_id}/export.csv", response_class=PlainTextResponse)
def export_attendance_csv(session_id: str) -> Response:
    """Выгрузка присутствующих в CSV (Excel понимает cp1251)."""
    session = db.get_session(session_id)
    if session is None:
        raise HTTPException(status_code=404, detail="Пара не найдена")
    buffer = io.StringIO()
    writer = csv.writer(buffer, delimiter=";")
    writer.writerow(["Пара", session["subject"], "Преподаватель", session["teacher_id"]])
    writer.writerow(["Начало", session["start_time"]])
    writer.writerow([])
    writer.writerow(["Студент", "ФИО", "Время отметки", "Подтверждено", "Источник"])
    for mark in db.list_attendance(session_id):
        writer.writerow([
            mark["student_id"],
            mark["student_name"],
            mark["timestamp"],
            "да" if mark["verified"] else "нет",
            mark["source"],
        ])
    csv_bytes = buffer.getvalue().encode("cp1251", errors="replace")
    headers = {"Content-Disposition": f'attachment; filename="attendance_{session_id}.csv"'}
    return Response(content=csv_bytes, media_type="text/csv; charset=cp1251", headers=headers)


# ------------------------------------------- синхронизация с телефоном преподавателя


@app.post("/api/tap")
def add_tap(
    payload: TapCreate,
    x_api_key: Optional[str] = Header(default=None, alias="X-Api-Key"),
) -> dict[str, Any]:
    """Касание, которое видел телефон преподавателя (можно присылать по одному)."""
    check_key(x_api_key)
    added = db.add_tap(
        payload.session_id,
        payload.tap_time,
        payload.result,
        payload.source,
        payload.device_id,
        payload.rssi,
    )
    verified = db.reverify_session(payload.session_id)
    return {"ok": True, "added": added, "marks_verified": verified}


@app.get("/api/taps/{session_id}")
def get_taps(
    session_id: str,
    x_api_key: Optional[str] = Header(default=None, alias="X-Api-Key"),
) -> dict[str, Any]:
    check_key(x_api_key)
    with db.connect() as connection:
        rows = connection.execute(
            """SELECT id, session_id, tap_time, result, source, device_id, rssi
                 FROM terminal_taps WHERE session_id = ? ORDER BY tap_time""",
            (session_id,),
        ).fetchall()
    return {"ok": True, "taps": db.rows_to_dicts(rows)}


@app.post("/api/sync")
def sync(
    payload: SessionSync,
    x_api_key: Optional[str] = Header(default=None, alias="X-Api-Key"),
) -> dict[str, Any]:
    """
    Полная синхронизация с телефоном преподавателя: пары + касания.
    Идемпотентно: повторная отправка тех же данных ничего не ломает.
    """
    check_key(x_api_key)
    result = db.sync_teacher(
        teacher_id=payload.teacher_id or "unknown",
        sessions=payload.sessions,
        taps=payload.taps,
    )
    return result


@app.get("/api/sync/state")
def sync_state(
    teacher_id: Optional[str] = Query(default=None),
    x_api_key: Optional[str] = Header(default=None, alias="X-Api-Key"),
) -> dict[str, Any]:
    """
    Что сервер уже знает — телефон преподавателя отправляет только недостающее.
    """
    check_key(x_api_key)
    sessions = db.list_sessions(limit=200)
    if teacher_id:
        sessions = [s for s in sessions if s["teacher_id"] == teacher_id]
    session_ids = [s["id"] for s in sessions]
    taps: list[str] = []
    if session_ids:
        placeholders = ",".join("?" for _ in session_ids)
        with db.connect() as connection:
            rows = connection.execute(
                f"""SELECT session_id || '|' || tap_time AS key
                      FROM terminal_taps WHERE session_id IN ({placeholders})""",
                session_ids,
            ).fetchall()
        taps = [row["key"] for row in rows]
    return {"ok": True, "session_ids": session_ids, "tap_keys": taps}


# ---------------------------------------------------------------- журнал запросов


@app.middleware("http")
async def log_requests(request: Request, call_next):  # noqa: ANN001
    """Короткий лог в консоль: видно, кто и что присылает (удобно на паре)."""
    response = await call_next(request)
    if request.url.path.startswith("/api/") and request.url.path != "/api/health":
        print(f"[{datetime.now().strftime('%H:%M:%S')}] "
              f"{request.client.host if request.client else '?'} "
              f"{request.method} {request.url.path} → {response.status_code}")
    return response


# ------------------------------------------------------------------- запуск вручную


def print_banner(host: str = "0.0.0.0", port: int = 8000) -> None:
    """Печатает адреса, по которым сервер видно из локальной сети."""
    import socket

    addresses: list[str] = []
    try:
        probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        probe.connect(("8.8.8.8", 80))  # интернет не нужен, нужен лишь маршрут
        addresses.append(probe.getsockname()[0])
        probe.close()
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            address = info[4][0]
            if not address.startswith("127.") and address not in addresses:
                addresses.append(address)
    except OSError:
        pass

    print("=" * 62)
    print("  КубГАУ · Сервер посещаемости запущен")
    print(f"  База: {db.DB_PATH}")
    print(f"  Локально:  http://127.0.0.1:{port}/docs")
    for address in addresses:
        print(f"  В сети:    http://{address}:{port}   ← этот адрес вводите на телефонах")
    if API_KEY:
        print("  API-ключ: включён (заголовок X-Api-Key)")
    print("=" * 62)


if __name__ == "__main__":
    import uvicorn

    db.init()
    print_banner(port=int(os.environ.get("PORT", "8000")))
    uvicorn.run(app, host="0.0.0.0", port=int(os.environ.get("PORT", "8000")))
