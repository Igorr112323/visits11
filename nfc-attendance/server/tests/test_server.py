"""
Тесты локального сервера. Запуск:

    cd server
    pip install -r requirements.txt
    pytest -v

Тесты работают на отдельном файле базы (test_attendance.db) и не трогают
рабочий attendance.db.
"""

from __future__ import annotations

import os
import sys
import uuid
from datetime import timedelta
from pathlib import Path

# отдельная тестовая база — до импорта приложения
TEST_DB = Path(__file__).resolve().parent / "test_attendance.db"
if TEST_DB.exists():
    TEST_DB.unlink()
os.environ["ATTENDANCE_DB"] = str(TEST_DB)

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from fastapi.testclient import TestClient  # noqa: E402

import database as db  # noqa: E402
import main  # noqa: E402

db.init()  # в тестах схему создаём вручную (lifespan у TestClient не запускается)
client = TestClient(main.app)

TEACHER = "IVANOV_II"
SUBJECT = "Информатика"

def device() -> str:
    """Уникальный device_id — как у отдельного телефона студента."""
    return f"IOS-{uuid.uuid4().hex[:4]}-{uuid.uuid4().hex[:4]}"


def make_session(minutes: int = 120, session_id: str | None = None) -> dict:
    """Создаёт пару так же, как это делает телефон преподавателя."""
    response = client.post("/api/session", json={
        "session_id": session_id or str(uuid.uuid4()),
        "subject": SUBJECT,
        "teacher_id": TEACHER,
        "group_name": "ИС-21",
        "minutes": minutes,
    })
    assert response.status_code == 200, response.text
    return response.json()["session"]


def mark(session: dict, student: str, device: str, offset_seconds: int = 0,
         subject: str | None = None, teacher: str | None = None,
         rssi: int | None = None, source: str = "ios"):
    timestamp = (db.parse_iso(db.now_iso()) + timedelta(seconds=offset_seconds))
    body = {
        "session_id": session["id"],
        "student_id": student,
        "device_id": device,
        "timestamp": timestamp.isoformat(timespec="seconds"),
        "subject": subject if subject is not None else session["subject"],
        "teacher_id": teacher if teacher is not None else session["teacher_id"],
        "source": source,
    }
    if rssi is not None:
        body["rssi"] = rssi
    return client.post("/api/attendance", json=body)


# ------------------------------------------------------------------ базовые тесты


def test_health():
    response = client.get("/api/health")
    assert response.status_code == 200
    assert response.json()["ok"] is True


def test_create_and_list_sessions():
    session = make_session()
    assert session["status"] == "active"

    listed = client.get("/api/sessions").json()["sessions"]
    assert any(item["id"] == session["id"] for item in listed)

    active = client.get("/api/sessions", params={"active_only": True}).json()["sessions"]
    assert any(item["id"] == session["id"] for item in active)


def test_session_id_must_be_uuid():
    response = client.post("/api/session", json={
        "subject": SUBJECT, "teacher_id": TEACHER, "session_id": "para-1",
    })
    assert response.status_code == 422  # «para-1» — предсказуемый id, отклоняем


# --------------------------------------------------------- приём отметок (сценарий)


def test_mark_accepted_and_verified_by_tap():
    session = make_session()
    # телефон преподавателя прислал касание
    assert client.post("/api/tap", json={
        "session_id": session["id"], "tap_time": db.now_iso(),
    }).json()["ok"] is True

    response = mark(session, "s001", device())
    body = response.json()
    assert response.status_code == 200
    assert body["result"] == "accepted"
    assert body["verified"] is True
    assert body["present_count"] == 1

    present = client.get(f"/api/attendance/{session['id']}").json()
    assert present["present_count"] == 1
    assert present["present"][0]["student_id"] == "s001"
    assert present["present"][0]["verified"] == 1


def test_mark_accepted_unverified_without_tap():
    session = make_session()
    body = mark(session, "s002", device()).json()
    assert body["result"] == "accepted_unverified"
    assert body["verified"] is False


def test_duplicate_mark_is_blocked():
    session = make_session()
    own_device = device()
    first = mark(session, "s003", own_device)
    assert first.json()["result"] in ("accepted", "accepted_unverified")

    second = mark(session, "s003", own_device)
    assert second.status_code == 200
    assert second.json()["result"] == "duplicate"
    assert second.json()["present_count"] == 1  # второй строки в базе нет


def test_unknown_session_rejected():
    response = client.post("/api/attendance", json={
        "session_id": str(uuid.uuid4()),
        "student_id": "s004",
        "device_id": device(),
        "timestamp": db.now_iso(),
    })
    assert response.status_code == 404
    assert response.json()["reason"] == "unknown_session"


def test_device_mismatch_blocks_second_account():
    session = make_session()
    shared_device = device()
    mark(session, "s005", shared_device)  # телефон привязался к s005
    response = mark(session, "s006", shared_device)  # тот же телефон, другой логин
    assert response.status_code == 409
    assert response.json()["reason"] == "device_mismatch"


def test_subject_and_teacher_mismatch():
    session = make_session()
    bad_subject = mark(session, "s007", device(), subject="Физкультура")
    assert bad_subject.status_code == 409
    assert bad_subject.json()["reason"] == "subject_mismatch"

    bad_teacher = mark(session, "s007", device(), teacher="PETROV_PP")
    assert bad_teacher.status_code == 409
    assert bad_teacher.json()["reason"] == "teacher_mismatch"


def test_expired_session_rejected():
    session = make_session(minutes=1)
    # сдвигаем конец пары в прошлое
    with db.connect() as connection:
        connection.execute(
            "UPDATE sessions SET end_time = ? WHERE id = ?",
            ((db.now() - timedelta(minutes=5)).isoformat(timespec="seconds"), session["id"]),
        )
    response = mark(session, "s008", device())
    assert response.status_code == 409
    assert response.json()["reason"] == "session_expired"


def test_closed_session_rejected():
    session = make_session()
    assert client.post(f"/api/session/{session['id']}/close").status_code == 200
    response = mark(session, "s009", device())
    assert response.status_code == 409
    assert response.json()["reason"] == "session_closed"


# --------------------------------------------- синхронизация телефона преподавателя


def test_sync_creates_sessions_and_verifies_marks():
    session_id = str(uuid.uuid4())
    tap_time = db.now_iso()

    # сначала студент отметился, терминал ещё не синхронизирован
    client.post("/api/session", json={
        "session_id": session_id, "subject": "Математика",
        "teacher_id": TEACHER, "minutes": 90,
    })
    body = mark({"id": session_id, "subject": "Математика", "teacher_id": TEACHER},
                "s010", device())
    assert body.json()["verified"] is False

    # телефон преподавателя синхронизируется: пара + касание
    sync = client.post("/api/sync", json={
        "teacher_id": TEACHER,
        "sessions": [{
            "id": session_id, "subject": "Математика", "teacher_id": TEACHER,
            "start_time": db.now_iso(), "minutes": 90,
        }],
        "taps": [{"session_id": session_id, "tap_time": tap_time, "source": "android"}],
    }).json()
    assert sync["taps_added"] == 1
    assert sync["marks_verified"] == 1

    present = client.get(f"/api/attendance/{session_id}").json()
    assert present["present"][0]["verified"] == 1

    # повторная синхронизация идемпотентна
    again = client.post("/api/sync", json={
        "teacher_id": TEACHER, "sessions": [], "taps": [
            {"session_id": session_id, "tap_time": tap_time},
        ],
    }).json()
    assert again["taps_added"] == 0  # дубль не добавился


def test_sync_state_reports_known_data():
    session = make_session()
    client.post("/api/tap", json={"session_id": session["id"], "tap_time": db.now_iso()})
    state = client.get("/api/sync/state", params={"teacher_id": TEACHER}).json()
    assert session["id"] in state["session_ids"]
    assert any(key.startswith(session["id"]) for key in state["tap_keys"])


# ------------------------------------------------------------------ прочие функции


def test_student_registration_and_absent_list():
    session = make_session()
    client.post("/api/students/register", json={
        "student_id": "s011", "name": "Иванов Иван", "group_name": "ИС-21",
    })
    mark(session, "s011", device())

    report = client.get(f"/api/attendance/{session['id']}").json()
    assert report["present"][0]["student_name"] == "Иванов Иван"
    assert report["absent"] == []  # единственный студент группы отмечен


def test_csv_export():
    session = make_session()
    mark(session, "s012", device())
    response = client.get(f"/api/attendance/{session['id']}/export.csv")
    assert response.status_code == 200
    assert "s012" in response.content.decode("cp1251")


def test_rejected_marks_are_logged():
    session = make_session()
    mark(session, "s013", device(), subject="Другой предмет")
    report = client.get(f"/api/attendance/{session['id']}").json()
    assert any(item["reason"] == "subject_mismatch" for item in report["rejected"])


# ------------------------------------------------------- BLE: касание и сигнал


def test_ble_tap_verifies_only_matching_device():
    """Касание с device_id подтверждает отметку только этого телефона."""
    session = make_session()
    student_device = device()
    other_device = device()

    # терминал записал BLE-касание телефона студента
    tap = client.post("/api/tap", json={
        "session_id": session["id"],
        "tap_time": db.now_iso(),
        "result": "ble",
        "source": "android",
        "device_id": student_device,
        "rssi": -52,
    })
    assert tap.status_code == 200
    assert tap.json()["added"] is True

    # этот же телефон — подтверждено
    own = mark(session, "ble01", student_device)
    assert own.json()["verified"] is True

    # другой телефон в то же окно — не подтверждено
    alien = mark(session, "ble02", other_device)
    assert alien.json()["verified"] is False


def test_weak_signal_mark_is_unverified():
    """Отметка со слабым сигналом (студент в коридоре) не подтверждается."""
    session = make_session()
    own_device = device()
    client.post("/api/tap", json={
        "session_id": session["id"], "tap_time": db.now_iso(),
        "device_id": own_device, "rssi": -88,
    })

    response = mark(session, "ble03", own_device, rssi=-88, source="ble")
    body = response.json()
    assert response.status_code == 200
    assert body["weak_signal"] is True
    assert body["verified"] is False
    assert "слабый" in body["detail"].lower()


def test_ble_mark_with_good_signal_and_tap_is_verified():
    session = make_session()
    own_device = device()
    client.post("/api/tap", json={
        "session_id": session["id"], "tap_time": db.now_iso(),
        "device_id": own_device, "rssi": -48,
    })
    response = client.post("/api/attendance", json={
        "session_id": session["id"],
        "student_id": "ble04",
        "device_id": own_device,
        "timestamp": db.now_iso(),
        "subject": session["subject"],
        "teacher_id": session["teacher_id"],
        "source": "ble",
        "rssi": -48,
    })
    body = response.json()
    assert body["verified"] is True
    assert body["rssi"] == -48

    present = client.get(f"/api/attendance/{session['id']}").json()["present"]
    assert present[0]["rssi"] == -48


def test_two_devices_can_tap_in_same_second():
    """Раньше уникальность (session_id, tap_time) теряла второе касание."""
    session = make_session()
    tap_time = db.now_iso()  # одна и та же секунда
    first = client.post("/api/tap", json={
        "session_id": session["id"], "tap_time": tap_time, "device_id": device(),
    }).json()
    second = client.post("/api/tap", json={
        "session_id": session["id"], "tap_time": tap_time, "device_id": device(),
    }).json()
    assert first["added"] is True
    assert second["added"] is True

    taps = client.get(f"/api/taps/{session['id']}").json()["taps"]
    assert len(taps) == 2


def test_sync_accepts_ble_taps_with_device_and_rssi():
    session_id = str(uuid.uuid4())
    own_device = device()
    tap_time = db.now_iso()

    client.post("/api/session", json={
        "session_id": session_id, "subject": "Физика",
        "teacher_id": TEACHER, "minutes": 90,
    })
    body = mark({"id": session_id, "subject": "Физика", "teacher_id": TEACHER},
                "ble05", own_device,
                rssi=-55)
    assert body.json()["verified"] is False  # касание ещё не пришло

    sync = client.post("/api/sync", json={
        "teacher_id": TEACHER,
        "sessions": [],
        "taps": [{
            "session_id": session_id, "tap_time": tap_time,
            "result": "ble", "device_id": own_device, "rssi": -55,
        }],
    }).json()
    assert sync["taps_added"] == 1
    assert sync["marks_verified"] == 1

    present = client.get(f"/api/attendance/{session_id}").json()["present"]
    assert present[0]["verified"] == 1


def test_rssi_validation_rejects_absurd_values():
    session = make_session()
    response = client.post("/api/attendance", json={
        "session_id": session["id"],
        "student_id": "ble06",
        "device_id": device(),
        "timestamp": db.now_iso(),
        "rssi": 40,          # положительный RSSI невозможен
    })
    assert response.status_code == 422


# ------------------------------------------------------- вход по логину и паролю

def test_register_with_password_and_login_ok():
    """Студент регистрируется с паролем — вход тем же паролем проходит."""
    student_id = f"LOGIN_{uuid.uuid4().hex[:6]}"
    response = client.post(
        "/api/students/register",
        json={"student_id": student_id, "name": "Иванов Иван", "password": "kubgau2026"},
    )
    assert response.status_code == 200, response.text
    assert "password_hash" not in response.json()["student"]

    login = client.post(
        "/api/students/login",
        json={"student_id": student_id, "password": "kubgau2026"},
    )
    assert login.status_code == 200, login.text
    body = login.json()
    assert body["ok"] is True
    assert body["student"]["id"] == student_id
    assert body["student"]["name"] == "Иванов Иван"
    assert "password_hash" not in body["student"]


def test_login_wrong_password():
    """Неверный пароль — сервер говорит об этом прямо (401 wrong_password)."""
    student_id = f"WRONG_{uuid.uuid4().hex[:6]}"
    client.post(
        "/api/students/register",
        json={"student_id": student_id, "name": "Петров Пётр", "password": "correct-horse"},
    )
    response = client.post(
        "/api/students/login",
        json={"student_id": student_id, "password": "не-тот-пароль"},
    )
    assert response.status_code == 401
    assert response.json()["reason"] == "wrong_password"


def test_login_unknown_student():
    """Такого логина нет — приложение предложит создать аккаунт."""
    response = client.post(
        "/api/students/login",
        json={"student_id": f"NOBODY_{uuid.uuid4().hex[:6]}", "password": "любой"},
    )
    assert response.status_code == 404
    assert response.json()["reason"] == "unknown_student"


def test_login_without_password_set():
    """Логин зарегистрирован без пароля — вход невозможен, нужен аккаунт с паролем."""
    student_id = f"NOSET_{uuid.uuid4().hex[:6]}"
    client.post("/api/students/register", json={"student_id": student_id, "name": "Сидоров С."})
    response = client.post(
        "/api/students/login",
        json={"student_id": student_id, "password": "любой-пароль"},
    )
    assert response.status_code == 409
    assert response.json()["reason"] == "no_password"


def test_password_is_not_stored_as_plaintext():
    """В базе лежит хэш PBKDF2, а не сам пароль; список студентов хэш не отдаёт."""
    student_id = f"HASH_{uuid.uuid4().hex[:6]}"
    client.post(
        "/api/students/register",
        json={"student_id": student_id, "name": "Хэш Х.", "password": "секретный-пароль"},
    )
    with db.connect() as connection:
        stored = connection.execute(
            "SELECT password_hash FROM students WHERE id = ?", (student_id,)
        ).fetchone()["password_hash"]

    assert stored and stored.startswith("pbkdf2_sha256$")
    assert "секретный-пароль" not in stored

    listed = client.get("/api/students").json()["students"]
    me = next(student for student in listed if student["id"] == student_id)
    assert "password_hash" not in me
