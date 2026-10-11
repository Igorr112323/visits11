using System.Text;

namespace Visits11.Services;

/// <summary>
/// Статичный QR-код студента для входа в приложение (не привязан к перекличке).
/// Телефон преподавателя его не показывает — ПК экспортирует папку с картинками,
/// студент сохраняет свою картинку в галерею и входит через «Войти по QR» — или
/// сканирует любым QR-сканером и вводит логин/пароль руками.
///
/// Формат (сознательно только ASCII — такой QR читается любым сканером,
/// без догадок про кодировку; кириллица внутри ФИО упакована в base64url):
///
///     V11S1.&lt;login-b64url&gt;.&lt;password-b64url&gt;.&lt;fullname-b64url&gt;.&lt;crc32&gt;
///     Login: ARHIPOV_II
///     Password: K7M2QX9P
///
/// 1-я строка — машинная (её читает приложение), 2-я и 3-я — для человека.
/// CRC32 (8 hex-символов) считается от машинной строки без него самого: он
/// отсекает случайное изображение вместо «своего» QR и кривые распознавания.
/// </summary>
public static class StudentQr
{
    /// <summary>Версия формата. Меняется только вместе с форматом — старые APK её не поймут.</summary>
    public const string Prefix = "V11S1";

    /// <summary>Собирает содержимое QR-кода студента (только ASCII).</summary>
    public static string Build(string login, string password, string fullName)
    {
        var machine = string.Join('.',
            Prefix,
            ToBase64Url(login),
            ToBase64Url(password),
            ToBase64Url(fullName));

        var builder = new StringBuilder(machine);
        builder.Append('.').Append(Crc32Hex(machine));
        builder.Append('\n').Append("Login: ").Append(Ascii(login));
        builder.Append('\n').Append("Password: ").Append(Ascii(password));
        return builder.ToString();
    }

    /// <summary>Разбирает содержимое QR: (логин, пароль, ФИО) или null, если это не наш код.</summary>
    public static (string Login, string Password, string FullName)? Parse(string text)
    {
        if (string.IsNullOrWhiteSpace(text)) return null;

        // приложение читает первую строку: остальные — для человека
        var lineEnd = text.IndexOf('\n');
        var machine = (lineEnd >= 0 ? text[..lineEnd] : text).Trim();

        var parts = machine.Split('.');
        if (parts.Length != 5 || parts[0] != Prefix) return null;

        var body = string.Join('.', parts[0], parts[1], parts[2], parts[3]);
        if (!string.Equals(Crc32Hex(body), parts[4], StringComparison.OrdinalIgnoreCase)) return null;

        var login = FromBase64Url(parts[1]);
        var password = FromBase64Url(parts[2]);
        var fullName = FromBase64Url(parts[3]);
        if (string.IsNullOrEmpty(login) || string.IsNullOrEmpty(password)) return null;
        return (login, password, fullName ?? "");
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
