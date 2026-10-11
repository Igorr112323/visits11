using System.IO;
using System.Text;
using System.Windows.Media.Imaging;
using QRCoder;

namespace Visits11.Services;

/// <summary>
/// QR-код студента — единственный способ входа в приложение (логина и пароля
/// в системе больше нет). Телефон преподавателя его не показывает: ПК отдаёт
/// картинку по кнопке «Показать QR-код» или выгружает папку с кодами группы.
/// Студент либо сканирует код камерой, либо сохраняет картинку и входит из галереи.
///
/// Формат (сознательно только ASCII — такой QR читается любым сканером, без
/// догадок про кодировку; кириллица внутри ФИО упакована в base64url):
///
///     V11S2.&lt;token-b64url&gt;.&lt;fullname-b64url&gt;.&lt;crc32&gt;
///     Student: Ivanov Ivan Ivanovich
///     Code: QVJISVBPVl9JSQ
///
/// 1-я строка — машинная (её читает приложение), 2-я и 3-я — для человека.
/// CRC32 (8 hex-символов) считается от машинной строки без него самого: он
/// отсекает случайное изображение вместо «своего» QR и кривые распознавания.
/// </summary>
public static class StudentQr
{
    /// <summary>Версия формата. Меняется только вместе с форматом — старые APK её не поймут.</summary>
    public const string Prefix = "V11S2";

    /// <summary>Собирает содержимое QR-кода студента (только ASCII).</summary>
    public static string Build(string token, string fullName)
    {
        var machine = string.Join('.',
            Prefix,
            ToBase64Url(token),
            ToBase64Url(fullName));

        var builder = new StringBuilder(machine);
        builder.Append('.').Append(Crc32Hex(machine));
        builder.Append('\n').Append("Student: ").Append(Ascii(AuthService.Transliterate(fullName)));
        builder.Append('\n').Append("Code: ").Append(Ascii(token));
        return builder.ToString();
    }

    /// <summary>Разбирает содержимое QR: (код студента, ФИО) или null, если это не наш код.</summary>
    public static (string Token, string FullName)? Parse(string text)
    {
        if (string.IsNullOrWhiteSpace(text)) return null;

        // приложение читает первую строку: остальные — для человека
        var lineEnd = text.IndexOf('\n');
        var machine = (lineEnd >= 0 ? text[..lineEnd] : text).Trim();

        var parts = machine.Split('.');
        if (parts.Length != 4 || parts[0] != Prefix) return null;

        var body = string.Join('.', parts[0], parts[1], parts[2]);
        if (!string.Equals(Crc32Hex(body), parts[3], StringComparison.OrdinalIgnoreCase)) return null;

        var token = FromBase64Url(parts[1]);
        var fullName = FromBase64Url(parts[2]);
        if (string.IsNullOrEmpty(token)) return null;
        return (token, fullName ?? "");
    }

    /// <summary>PNG с QR-кодом: module size 12 px — код читается даже с фотографии экрана.</summary>
    public static byte[] RenderPng(string content)
    {
        var generator = new QRCodeGenerator();
        var data = generator.CreateQrCode(content, QRCodeGenerator.ECCLevel.M);
        return new PngByteQRCode(data).GetGraphic(12);
    }

    /// <summary>Тот же QR в виде картинки для окна «Показать QR-код».</summary>
    public static BitmapSource RenderImage(string content)
    {
        var image = new BitmapImage();
        using (var stream = new MemoryStream(RenderPng(content)))
        {
            image.BeginInit();
            image.CacheOption = BitmapCacheOption.OnLoad;
            image.StreamSource = stream;
            image.EndInit();
        }
        image.Freeze();
        return image;
    }

    /// <summary>base64url без выравнивания: '+/' и '=' заменены на безопасные символы.</summary>
    public static string ToBase64Url(string value)
        => Convert.ToBase64String(Encoding.UTF8.GetBytes(value))
            .TrimEnd('=')
            .Replace('+', '-')
            .Replace('/', '_');

    /// <summary>Обратная распаковка base64url (битые данные → null).</summary>
    public static string? FromBase64Url(string value)
    {
        var normalized = value.Replace('-', '+').Replace('_', '/');
        var padding = (4 - normalized.Length % 4) % 4;
        normalized = normalized.PadRight(normalized.Length + padding, '=');
        try
        {
            return Encoding.UTF8.GetString(Convert.FromBase64String(normalized));
        }
        catch (FormatException)
        {
            return null;
        }
    }

    /// <summary>CRC32 (IEEE 802.3), 8 hex-символов — тот же алгоритм на Android и iOS.</summary>
    public static string Crc32Hex(string value)
    {
        var bytes = Encoding.UTF8.GetBytes(value);
        var crc = 0xFFFFFFFFu;
        foreach (var b in bytes)
        {
            crc ^= b;
            for (var bit = 0; bit < 8; bit++)
            {
                crc = (crc & 1) == 1 ? (crc >> 1) ^ 0xEDB88320u : crc >> 1;
            }
        }
        return (~crc).ToString("x8");
    }

    /// <summary>
    /// ASCII-вид для «человеческой» части QR: всё, что не ASCII-графика, заменяем
    /// на «?» — сам QR обязан остаться ASCII-текстом.
    /// </summary>
    private static string Ascii(string value)
    {
        var builder = new StringBuilder(value.Length);
        foreach (var ch in value)
        {
            builder.Append(ch is >= ' ' and <= '~' ? ch : '?');
        }
        return builder.ToString();
    }
}
