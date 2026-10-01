# Visits11

WPF-приложение (.NET 8) для учёта посещаемости студентов. Работает полностью офлайн:
данные хранятся в локальной SQLite-базе `attendance.db` рядом с исполняемым файлом.

## Возможности

- **Занятия** — выбор группы, перекличка с рамкой сканирования, круговая статистика,
  список студентов с отметками «Есть»/«Нет». Первая отметка студента генерирует
  уникальный ID телефона (`XXXX-XXXX-XXXX`) и сохраняет его в базу.
- **Студенты** — управление группами, таблица студентов с логинами/паролями
  (клик по чипу копирует в буфер), импорт списка из Word (.docx), экспорт в Word.
- **Журнал** — история занятий по группам, детали посещаемости, экспорт занятия в Word.
- Светлая и тёмная темы (выбор сохраняется в `settings.json` рядом с exe).

## Сборка

Требуется [.NET 8 SDK](https://dotnet.microsoft.com/download/dotnet/8.0) и Windows.

```bash
dotnet build -c Release
```

Single-file EXE без внешних зависимостей:

```bash
dotnet publish -c Release -r win-x64 -p:PublishSingleFile=true -p:SelfContained=true -p:IncludeNativeLibrariesForSelfExtract=true
```

Готовый файл: `bin/Release/net8.0-windows/win-x64/publish/Visits11.exe`.

## Автосборка

GitHub Actions (`.github/workflows/build.yml`) собирает проект и публикует
`Visits11.exe` в артефакты запуска: вкладка **Actions → build → Artifacts**.

## Структура

```
├── Visits11.csproj
├── App.xaml / App.xaml.cs          — точка входа, композиция сервисов
├── Assets/Fonts/                   — Manrope (SIL OFL)
├── Models/                         — Group, Student, Lesson
├── Services/
│   ├── DatabaseService.cs          — SQLite (attendance.db)
│   ├── AuthService.cs              — логины (транслит), пароли, ID телефона
│   ├── WordService.cs              — импорт/экспорт .docx
│   ├── ThemeService.cs             — темы + settings.json
│   └── ToastService.cs             — уведомления
├── ViewModels/                     — Main, Lesson, Students, Journal, Modal
├── Views/                          — MainWindow + 3 вкладки
├── Controls/                       — CircularProgress, Widget
└── Styles/                         — темы, стили, иконки, конвертеры
```

## Шрифт

Manrope распространяется по лицензии [SIL Open Font License 1.1](Assets/Fonts/OFL.txt).
