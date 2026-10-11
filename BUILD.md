# Как собрать и получить файлы (Visits11)

В проекте четыре приложения — четыре артефакта:

| Артефакт | Что это | Исходники | Команда сборки |
|---|---|---|---|
| `KubGAU-Journal.exe` | Журнал преподавателя для Windows (WPF, .NET 8, single-file) | корень репозитория | `dotnet publish` |
| `KubGAU-Teacher.apk` | Телефон преподавателя (NFC-терминал) | `android/app` | `gradle :app:assembleRelease` |
| `KubGAU-Student.apk` | Телефон студента (Android) | `android/student` | `gradle :student:assembleRelease` |
| `Visits11-Student-iOS-unsigned.ipa` | iPhone-студент | `ios/` | `xcodebuild` + `xcodegen` |

**Самый простой путь** — не собирать вообще: готовые файлы публикуются автоматически
в [релизе v1.0.0](https://github.com/Igorr112323/visits11/releases). Их создаёт
GitHub Actions при каждом push:

| Workflow | Что собирает | Триггер |
|---|---|---|
| `.github/workflows/build.yml` | `KubGAU-Journal.exe` (windows-latest) | любой push |
| `.github/workflows/apk.yml` | оба APK (ubuntu-latest) | изменения в `android/**` |
| `.github/workflows/ios.yml` | `Visits11-Student-iOS-unsigned.ipa` (macos-14) | изменения в `ios/**` |

Можно запустить вручную: **Actions → нужный workflow → Run workflow**.
Сборка занимает 1–3 минуты, файлы подменяются в релизе автоматически (без ручных шагов).

---

## 1. Android: APK преподавателя и студента

**Требуется:** JDK 17, Gradle 8.9, Android SDK с платформой `android-34`
(и `ANDROID_HOME`/`local.properties` с `sdk.dir`).

```bash
cd android

# оба APK сразу
gradle assembleRelease

# или по отдельности
gradle :app:assembleRelease       # преподаватель
gradle :student:assembleRelease   # студент
```

Результаты:

```
android/app/build/outputs/apk/release/app-release.apk         → KubGAU-Teacher.apk
android/student/build/outputs/apk/release/student-release.apk → KubGAU-Student.apk
```

Установка на телефон (USB-отладка включена):

```bash
adb install -r android/app/build/outputs/apk/release/app-release.apk
adb install -r android/student/build/outputs/apk/release/student-release.apk
```

**Важные уточнения** (в типовых инструкциях для Android это описано иначе, но здесь
проект другой):

- **Flavor'ов нет.** Каталоги `app/` и `student/` — это два **отдельных модуля**
  (`include ':app'` и `include ':student'` в `settings.gradle`), поэтому команд
  `assembleTeacherRelease`/`assembleStudentRelease` и путей
  `app/build/outputs/apk/teacher/release/` в этом проекте не существует.
- **Gradle wrapper в репозитории отсутствует** — используется установленный Gradle 8.9
  (как в CI). Если нужен wrapper: `gradle wrapper --gradle-version 8.9` и дальше `./gradlew`.
- **Релиз подписан debug-ключом** (`signingConfig signingConfigs.debug` в
  `android/{app,student}/build.gradle`) — это сделано намеренно, чтобы APK ставился
  в обход магазина. Для Google Play нужен свой keystore и `signingConfigs.release`.
- Студенческий APK тянет из Maven Central `com.google.zxing:core:3.5.3`
  (распознавание QR из картинки) и `com.journeyapps:zxing-android-embedded:4.3.0`
  (сканирование камерой) — первая сборка требует интернета. Версия `4.3.0` —
  последняя выпущенная; `4.3.1` не существует.
- Проект собирается без Android Studio: достаточно Gradle + SDK. В Android Studio
  проект открывается как обычно (папка `android/`), модули `app` и `student` уже настроены.

---

## 2. Windows: EXE журнала

**Требуется:** Windows 10/11, [.NET 8 SDK](https://dotnet.microsoft.com/download/dotnet/8.0).
WPF-проект собирается **только на Windows** (на Linux/macOS `net8.0-windows` не компилируется).

```powershell
# из корня репозитория
dotnet publish -c Release -r win-x64 -p:PublishSingleFile=true -p:SelfContained=true -p:IncludeNativeLibrariesForSelfExtract=true -o publish

# получаем один файл:
#   publish\Visits11.exe   → переименовать в KubGAU-Journal.exe (как в релизе)
```

Запуск: скопировать `KubGAU-Journal.exe` в любую папку и запустить — рядом появятся
`attendance.db` (база) и `settings.json` (тема). Установка и .NET на целевой машине
не требуются (self-contained).

**Уточнение:** EXE — это **не** эмулятор Android и не обёртка над APK. Это отдельное
нативное приложение (WPF), внутри которого: журнал, база SQLite, HTTP-сервер для
телефонов (`PhoneServer`, порты 8090–8099) и приём кадров/NFC с телефона преподавателя
(`CameraLink`). BlueStacks/NoxPlayer сюда не нужны и работать не будут.

---

## 3. iOS: приложение студента

**Требуется:** macOS с Xcode 15+ (и `brew install xcodegen` — проект генерируется из
`ios/project.yml`, файла `.xcodeproj` в репозитории нет).

### Вариант «без подписи» (как в CI — только ipa для последующей подписи)

```bash
brew install xcodegen
cd ios
xcodegen generate
# XcodeGen пишет формат Xcode 16 (objectVersion 77); на Xcode 15 понижаем:
sed -i '' 's/objectVersion = 77;/objectVersion = 56;/' Visits11Student.xcodeproj/project.pbxproj

xcodebuild -project Visits11Student.xcodeproj -scheme Visits11Student \
  -configuration Release -sdk iphoneos -derivedDataPath build \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY="" clean build

mkdir Payload
cp -r build/Build/Products/Release-iphoneos/Visits11Student.app Payload/
zip -qry Visits11-Student-iOS-unsigned.ipa Payload
```

Готовый `.app` лежит в `ios/build/Build/Products/Release-iphoneos/Visits11Student.app`,
а `.ipa` получается упаковкой этой `.app` в папку `Payload/` (это и есть формат ipa).

### Вариант «со своей подписью» (Xcode GUI)

1. `xcodegen generate`, открыть `ios/Visits11Student.xcodeproj`.
2. Вкладка **Signing & Capabilities**: включить автоматическую подпись, выбрать свой
   Apple ID (бесплатный тоже подходит — подпись живёт 7 дней) и Bundle Identifier.
3. Выбрать устройство и **Product → Run** — приложение установится на iPhone.
4. Для файла `.ipa`: **Product → Archive**, затем в Organizer
   (`~/Library/Developer/Xcode/Archives/…`) — **Distribute App → Custom → App Store Connect
   / Ad Hoc / Development**; файл `.app` из архива лежит в
   `…/Products/Applications/Visits11Student.app`, `.ipa` создаёт сам Organizer.

**Бесплатная установка без Xcode:** подписать unsigned-ipa своим Apple ID через
[Sideloadly](https://sideloadly.io/) (Windows/macOS, бесплатно, подпись живёт 7 дней).
Для распространения на всех студентов нужен Apple Developer Account ($99/год) —
Apple не разрешает установку сторонних приложений без аккаунта разработчика.

---

## 4. Проверка файлов после сборки

```bash
# целостность (по желанию — сравнить два независимых сбора между собой)
sha256sum KubGAU-Teacher.apk KubGAU-Student.apk KubGAU-Journal.exe
# macOS: shasum -a 256 ...
# Windows: Get-FileHash .\KubGAU-Journal.exe -Algorithm SHA256
```

Сборка сходится, если CI на GitHub показывал зелёные запуски
`build`, `Build Android APK`, `Build iOS` — сейчас они зелёные на коммите `25c982f`
и артефакты релиза обновлены именно им.

---

## 5. Мелкие трудности и их решения

| Симптом | Решение |
|---|---|
| `SDK location not found` | Указать `ANDROID_HOME` или создать `android/local.properties` с `sdk.dir=/path/to/Android/sdk` |
| Студенческий APK не собирается, «не найден com.google.zxing» | Нет доступа в интернет для Maven Central (первая сборка модуля `:student`) |
| Студенческий APK не собирается, «не найден com.journeyapps» | То же: `zxing-android-embedded` тянется из Maven Central |
| `dotnet publish` падает вне Windows | WPF собирается только на Windows — используйте CI (`build.yml`) |
| `xcodegen: command not found` | `brew install xcodegen` |
| `objectVersion 77` не поддерживается Xcode 15 | `sed` из раздела 3 (как в CI) |
| Нужен `.ipa`, а Xcode не даёт Archive без аккаунта | Собрать unsigned-ipa (раздел 3) и подписать через Sideloadly |
