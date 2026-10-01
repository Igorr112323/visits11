using System.Security.Cryptography;
using System.Text;

namespace Visits11.Services;

/// <summary>
/// Генерация логинов (транслит Фамилия_ИО), паролей и ID телефона.
/// </summary>
public sealed class AuthService
{
    private const string PasswordAlphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private const string HexAlphabet = "0123456789ABCDEF";

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

    private static string Clean(string text)
    {
        var builder = new StringBuilder(text.Length);
        foreach (var ch in text)
        {
            if (char.IsAsciiLetterOrDigit(ch)) builder.Append(ch);
        }
        return builder.ToString();
    }

    /// <summary>
    /// Логин вида ARHIPOV_II (Фамилия + инициалы имени и отчества).
    /// Если логин занят — добавляется числовой суффикс.
    /// </summary>
    public string GenerateLogin(string fullName, Func<string, bool> loginExists)
    {
        var words = fullName.Split(' ', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
        var surname = words.Length > 0 ? Clean(Transliterate(words[0])).ToUpperInvariant() : "STUDENT";
        if (surname.Length == 0) surname = "STUDENT";

        var initials = new StringBuilder();
        foreach (var word in words.Skip(1))
        {
            var letter = Clean(Transliterate(word[0].ToString())).ToUpperInvariant();
            if (letter.Length > 0) initials.Append(letter[0]);
        }

        var baseLogin = initials.Length > 0 ? $"{surname}_{initials}" : surname;
        if (!loginExists(baseLogin)) return baseLogin;

        for (var i = 2; i < 1000; i++)
        {
            var candidate = $"{baseLogin}{i}";
            if (!loginExists(candidate)) return candidate;
        }
        return $"{baseLogin}{RandomNumberGenerator.GetInt32(1000, 9999)}";
    }

    /// <summary>Пароль: 10 символов из алфавита без неоднозначных символов.</summary>
    public static string GeneratePassword()
    {
        var chars = new char[10];
        for (var i = 0; i < chars.Length; i++)
        {
            chars[i] = PasswordAlphabet[RandomNumberGenerator.GetInt32(PasswordAlphabet.Length)];
        }
        return new string(chars);
    }

    /// <summary>ID телефона в формате XXXX-XXXX-XXXX (12 hex-символов).</summary>
    public static string GeneratePhoneId()
    {
        var chars = new char[12];
        for (var i = 0; i < chars.Length; i++)
        {
            chars[i] = HexAlphabet[RandomNumberGenerator.GetInt32(HexAlphabet.Length)];
        }
        return $"{new string(chars, 0, 4)}-{new string(chars, 4, 4)}-{new string(chars, 8, 4)}";
    }
}
