"""
КубГАУ · Учёт посещаемости — запуск локального сервера.

Этот файл нужен для сборки одного файла `KubGAU-Attendance-Server.exe`
(Windows, Python на компьютере преподавателя не требуется):

    pyinstaller --onefile --name KubGAU-Attendance-Server launcher.py

Что делает exe:
  • создаёт базу `attendance.db` рядом с собой (путь можно переопределить
    переменной окружения `ATTENDANCE_DB`);
  • слушает 0.0.0.0:8000 — то есть доступен телефонам в той же сети
    (порт можно переопределить переменной `PORT`);
  • печатает в консоль адрес, который нужно ввести в приложениях.

Обычный запуск из исходников тоже работает:  python launcher.py
"""

from __future__ import annotations

import os
import socket
import sys
from pathlib import Path

DEFAULT_PORT = 8000


def base_dir() -> Path:
    """Папка, рядом с которой лежим: у exe — папка exe, иначе — папка файла."""
    if getattr(sys, "frozen", False):
        return Path(sys.executable).resolve().parent
    return Path(__file__).resolve().parent


def prepare_console() -> None:
    """Кириллица в консоли Windows: включаем UTF-8, если система позволяет."""
    if os.name != "nt":
        return
    try:
        import ctypes

        ctypes.windll.kernel32.SetConsoleOutputCP(65001)
    except Exception:  # noqa: BLE001 — косметика, без неё просто может быть крякозябр
        pass
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:  # noqa: BLE001
        pass


def lan_addresses(port: int) -> list[str]:
    """Адреса, по которым сервер виден другим устройствам сети."""
    addresses: list[str] = []

    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip: str = info[4][0]
            if not ip.startswith("127.") and ip not in addresses:
                addresses.append(ip)
    except OSError:
        pass

    if not addresses:
        # запасной способ: узнаём свой адрес «наружу» (пакеты никуда не уходят)
        try:
            probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            probe.settimeout(1)
            probe.connect(("8.8.8.8", 80))
            addresses.append(probe.getsockname()[0])
            probe.close()
        except OSError:
            pass

    return [f"http://{ip}:{port}" for ip in addresses]


def print_banner(port: int, database: Path) -> None:
    addresses = lan_addresses(port)
    line = "=" * 64

    print()
    print(line)
    print("  КубГАУ · Сервер отметок посещаемости — запущен")
    print(line)
    print()
    print("  Адрес для телефонов — введите его в приложениях")
    print("  (преподаватель: Android, студент: iPhone):")
    print()
    if addresses:
        for address in addresses:
            print(f"      {address}")
    else:
        print("      (адрес не определился — проверьте подключение к Wi-Fi)")
    print()
    print("  Проверка в браузере:      " + (addresses[0] if addresses else f"http://<IP>:{port}"))
    print("  Документация (Swagger):   " + (f"{addresses[0]}/docs" if addresses else f"http://<IP>:{port}/docs"))
    print(f"  База данных:              {database}")
    print()
    print("  Если телефон не подключается:")
    print("    • разрешите доступ в брандмауэре Windows (частные сети) —")
    print("      окно появится при первом запуске, нажмите «Разрешить доступ»;")
    print("    • телефон и ноутбук должны быть в одной Wi-Fi сети.")
    print()
    print("  Остановить сервер: Ctrl + C")
    print(line)
    print(flush=True)


def read_port() -> int:
    """Порт из аргумента командной строки или переменной PORT (по умолчанию 8000)."""
    if len(sys.argv) > 1 and sys.argv[1].isdigit():
        return int(sys.argv[1])
    value = os.environ.get("PORT", "").strip()
    return int(value) if value.isdigit() else DEFAULT_PORT


def main() -> None:
    prepare_console()

    # Базу кладём рядом с exe: преподаватель может просто скопировать папку.
    database = Path(os.environ.get("ATTENDANCE_DB") or base_dir() / "attendance.db")
    os.environ["ATTENDANCE_DB"] = str(database)

    port = read_port()
    print_banner(port, database)

    # Импортируем после настройки окружения: database.py читает ATTENDANCE_DB
    # на этапе импорта.
    import uvicorn
    import main as server_main

    # Явно указываем loop/http/ws: exe собирается PyInstaller'ом, и динамический
    # разбор этих модулей ему не виден.
    config = uvicorn.Config(
        server_main.app,
        host="0.0.0.0",
        port=port,
        loop="asyncio",
        http="h11",
        ws="none",
        log_level="info",
        access_log=True,
    )
    uvicorn.Server(config).run()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\nСервер остановлен.")
    except Exception as error:  # noqa: BLE001 — окно не должно закрываться молча
        print(f"\nНе удалось запустить сервер: {error}")
        if getattr(sys, "frozen", False):
            input("Нажмите Enter, чтобы закрыть окно…")
        raise
