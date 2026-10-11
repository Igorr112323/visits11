using System.IO;
using System.Text;
using QRCoder;
using Visits11.Models;

namespace Visits11.Services;

/// <summary>
/// Экспорт QR-кодов студентов: в выбранной папке создаётся подпапка
/// «QR-коды &lt;группа&gt;», внутри — по одной картинке PNG на студента
/// и файл-инструкция «Как войти.txt».
///
/// Картинку студенту пересылает преподаватель (мессенджер, почта, флешка).
/// Студент сохраняет её в галерею и входит через «Войти по QR из галереи» —
/// или сканирует любым QR-сканером и вводит логин с паролем руками (в коде
/// есть эти строки). Никакой сервер для этого не нужен: в QR лежит только
/// логин, пароль и ФИО студента.
/// </summary>
public sealed class QrExportService
{
    /// <summary>UTF-8 с BOM — чтобы «Блокнот» Windows показал инструкцию без «кракозябр».</summary>
    private static readonly UTF8Encoding Utf8Bom = new(encoderShouldEmitUTF8Identifier: true);

    /// <summary>
    /// Создаёт папку с QR-кодами и возвращает её путь и число картинок.
    /// </summary>
    /// <param name="parentDirectory">Папка, выбранная преподавателем в диалоге.</param>
    /// <param name="groupName">Название группы — попадает в имя подпапки.</param>
    /// <param name="students">Студенты группы (логин и пароль уже сгенерированы).</param>
    public (string Folder, int Count) Export(string parentDirectory, string groupName, IReadOnlyList<Student> students)
    {
        var folder = Path.Combine(parentDirectory, $"QR-коды {Sanitize(groupName)}");
        Directory.CreateDirectory(folder);

        // повторный экспорт не должен оставлять картинки давно удалённых студентов
        foreach (var stale in Directory.EnumerateFiles(folder, "*.png"))
        {
            try
            {
                File.Delete(stale);
            }
            catch (IOException)
            {
                // файл открыт в просмотрщике — не мешает экспорту
            }
        }

        var used = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var student in students)
        {
            var text = StudentQr.Build(student.Login, student.Password, student.FullName);

            // страховка от опечатки в формате: картинки не должны разойтись с тем,
            // что разбирают приложения студента (сборка кода и проверка CRC32)
            if (StudentQr.Parse(text) is null)
            {
                throw new InvalidDataException($"Некорректный QR-код для «{student.FullName}».");
            }

            var png = Render(text);
            var fileName = Unique($"{student.FullName} ({student.Login})", used) + ".png";
            File.WriteAllBytes(Path.Combine(folder, fileName), png);
        }

        File.WriteAllText(Path.Combine(folder, "Как войти.txt"), Instructions(groupName), Utf8Bom);
        return (folder, students.Count);
    }

    /// <summary>PNG с QR-кодом: module size 12 px — код читается даже с фотографии экрана.</summary>
    private static byte[] Render(string text)
    {
        var generator = new QRCodeGenerator();
        var data = generator.CreateQrCode(text, QRCodeGenerator.ECCLevel.M);
        var qr = new PngByteQRCode(data);
        return qr.GetGraphic(12);
    }

    /// <summary>Имя файла без символов, которые запрещены в Windows.</summary>
    private static string Sanitize(string name)
    {
        var builder = new StringBuilder(name.Length);
        foreach (var ch in name)
        {
            builder.Append(char.IsControl(ch) || "\\/:*?\"<>|".Contains(ch) ? '-' : ch);
        }
        var clean = builder.ToString().Trim().TrimEnd('.');
        return clean.Length == 0 ? "группа" : clean;
    }

    /// <summary>Одинаковые имена файлов (полные тёзки) разводим суффиксом «(2)», «(3)»…</summary>
    private static string Unique(string name, HashSet<string> used)
    {
        var candidate = name;
        var index = 2;
        while (!used.Add(candidate))
        {
            candidate = $"{name} ({index++})";
        }
        return candidate;
    }

    /// <summary>Инструкция для студента — лежит в той же папке.</summary>
    private static string Instructions(string groupName)
        => $"""
            QR-коды для входа в приложение «КубГАУ Студент»
            Группа: {groupName}
            
            В этой папке — по одной картинке PNG на каждого студента.
            Имя файла: «ФИО (логин).png». Свою картинку нужно передать студенту
            (мессенджер, почта, флешка) — каждый студент получает только свой файл.
            
            КАК ВОЙТИ (телефон студента)
            1. Сохраните картинку со своим QR-кодом в галерею телефона.
            2. Откройте приложение «КубГАУ Студент».
            3. На экране входа нажмите «Войти по QR из галереи» и выберите картинку.
               На iPhone на экране входа — такая же кнопка.
            Приложение распознает код само, вход выполнится сразу.
            
            ЗАПАСНОЙ СПОСОБ
            Любым QR-сканером отсканируйте картинку: под машинной строкой в коде
            напечатаны «Login:» и «Password:» — эти логин и пароль можно ввести
            в приложении руками.
            
            ВАЖНО
            • QR-код содержит ваш личный логин и пароль — не пересылайте его другим.
            • Аккаунт привязывается к первому телефону, с которого был вход:
              отметить кого-то «за другого» с чужого телефона не получится.
            • QR-код постоянный: его достаточно сохранить один раз.
            """;
}
