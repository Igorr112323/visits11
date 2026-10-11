using System.Security.Cryptography;
using System.Text;

namespace Visits11.Services;

/// <summary>
/// Служебные генераторы: код студента для QR-кода (заменяет логин и пароль)
/// и транслитерация (нужна, чтобы QR-код оставался ASCII-текстом).
/// </summary>
public sealed class AuthService
{
    private static readonly Dictionary<string, string> Translit = new()
    {
        ["а"] = "a", ["б"] = "b", ["в"] = "v", ["г"] = "g", ["д"] = "d",
        ["е"] = "e", ["ё"] = "e", ["ж"] = "zh", ["з"] = "z", ["и"] = "i",
        ["й"] = "y", ["к"] = "k", ["л"] = "l", ["м"] = "m", ["н"] = "n",
        ["о"] = "o", ["п"] = "p", ["р"] = "r", ["с"] = "s", ["т"] = "t",
        ["у"] = "u", ["ф"] = "f", ["х"] = "kh", ["ц"] = "ts", ["ч"] = "ch",
        ["ш"] = "sh", ["щ"] = "shch", ["ъ"] = "", ["ы"] = "y", ["ь"] = "",
        ["э"] = "e", ["ю"] = "yu", ["я"] = "ya",
    };

    /// <summary>Транслитерация строки (кириллица → латиница).</summary>
    public static string Transliterate(string text)
    {
        var builder = new StringBuilder(text.Length);
        foreach (var ch in text)
        {
            var lower = char.ToLowerInvariant(ch).ToString();
            if (Translit.TryGetValue(lower, out var mapped))
            {
                builder.Append(char.IsUpper(ch) && mapped.Length > 0
                    ? char.ToUpperInvariant(mapped[0]) + mapped[1..]
                    : mapped);
            }
            else
            {
                builder.Append(ch);
            }
        }
        return builder.ToString();
    }

    /// <summary>
    /// Код студента для QR-кода: 128 бит случайности в base64url (22 символа).
    /// Логина и пароля в системе больше нет — вход выполняет этот код,
    /// а от передачи чужого QR защищает привязка к телефону (DeviceId).
    /// </summary>
    public static string GenerateQrToken()
        => Convert.ToBase64String(RandomNumberGenerator.GetBytes(16))
            .TrimEnd('=')
            .Replace('+', '-')
            .Replace('/', '_');

}
