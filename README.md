# Visits11

WPF-приложение (.NET 8) для учёта посещаемости студентов. Работает полностью офлайн:
данные хранятся в локальной SQLite-базе `attendance.db` рядом с исполняемым файлом.

## Возможности

- **Занятия** — выбор группы, перекличка: студенты сами отмечаются, сканируя QR-код
  с экрана (первый раз выбирают себя на открывшейся странице, дальше — автоматически
  по сохранённому ID телефона `XXXX-XXXX-XXXX`); круговая статистика,
  список студентов с отметками «Есть»/«Нет».
- **Камера с телефона** — APK-приложение преподавателя (папка `android/`) передаёт
  видео в окно «Занятия» по USB-кабелю (нужно лишь включить «USB-модем» —
  приложение само подскажет и откроет нужную настройку); ПК находит телефон
  автоматически. Телефон не подключён — заглушка.
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

## Приложение камеры (Android)

В папке `android/` — минимальное Java-приложение (без зависимостей).
Никаких адресов и настроек вводить не нужно:

1. Подключите телефон к ПК USB-кабелем.
2. Приложение само попросит включить «USB-модем» и откроет нужный экран настроек
   (единственный тумблер, который Android не разрешает включать приложениям).
3. ПК находит телефон автоматически (камера использует исходящее соединение —
   файрвол Windows для неё не спрашивает).

Сборка APK (JDK 17, Gradle 8.9, Android SDK 34):

```bash
cd android && gradle assembleRelease
```

Готовый файл: `app/build/outputs/apk/release/app-release.apk`.

## Автосборка

GitHub Actions собирает проект и публикует артефакты:
`.github/workflows/build.yml` → `Visits11.exe`,
`.github/workflows/apk.yml` → `Visits11-Teacher.apk` (вкладка **Actions → Artifacts**).

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
│   ├── ToastService.cs             — уведомления
│   └── PhoneServer.cs              — встроенный сервер: QR-страница отметки + видеопоток
├── ViewModels/                     — Main, Lesson, Students, Journal, Modal
├── Views/                          — MainWindow + 3 вкладки
├── Controls/                       — CircularProgress, Widget
├── Styles/                         — темы, стили, иконки, конвертеры
└── android/                        — APK камеры преподавателя (Java, без зависимостей)
```

## Шрифт

Manrope распространяется по лицензии [SIL Open Font License 1.1](Assets/Fonts/OFL.txt).
