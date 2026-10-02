using System.Collections.Concurrent;
using System.IO;
using System.Security.Cryptography;
using System.Text;

namespace Visits11.Services;

public static class QrKeys
{
    private const int WindowSeconds = 3;
    private const int Tolerance = 1;
    private const int PayloadLength = 31;
    private const int MacLength = 12;

    private static readonly object Gate = new();
    private static readonly ConcurrentDictionary<(int Id, uint Window), byte> Used = new();
    private static byte[]? _master;

    private static byte[] Master()
    {
        lock (Gate)
        {
            if (_master is not null) return _master;

            string? path = null;
            try
            {
                path = Path.Combine(PhoneServer.ExeFolder(), "qr.key");
                if (File.Exists(path))
                {
                    var saved = Convert.FromHexString(File.ReadAllText(path).Trim());
                    if (saved.Length == 32)
                    {
                        _master = saved;
                        return _master;
                    }
                }
            }
            catch
            {
            }

            _master = RandomNumberGenerator.GetBytes(32);
            try
            {
                if (path is not null)
                    File.WriteAllText(path, Convert.ToHexString(_master).ToLowerInvariant());
            }
            catch
            {
            }
            return _master;
        }
    }

    public static byte[] ForStudent(int studentId)
    {
        return HMACSHA256.HashData(Master(), Encoding.ASCII.GetBytes("stu" + studentId));
    }

    public static bool LooksLikePayload(byte[] raw)
    {
        return raw.Length == PayloadLength
            && raw[0] == (byte)'V' && raw[1] == (byte)'1' && raw[2] == (byte)'1' && raw[3] == (byte)'B';
    }

    public static int? Verify(byte[] raw)
    {
        if (!LooksLikePayload(raw)) return null;

        byte[] body;
        try
        {
            var encoded = Encoding.ASCII.GetString(raw, 4, raw.Length - 4).Replace('-', '+').Replace('_', '/');
            body = Convert.FromBase64String(encoded + "=");
        }
        catch
        {
            return null;
        }
        if (body.Length != 20) return null;

        var head = new byte[12];
        Encoding.ASCII.GetBytes("V11B").CopyTo(head, 0);
        Array.Copy(body, 0, head, 4, 8);

        var studentId = BitConverter.ToInt32(head, 4);
        var window = BitConverter.ToUInt32(head, 8);
        if (studentId <= 0) return null;

        var current = (uint)(DateTimeOffset.UtcNow.ToUnixTimeSeconds() / WindowSeconds);
        var distance = Math.Abs((long)window - current);
        if (distance > Tolerance) return null;

        var expected = HMACSHA256.HashData(ForStudent(studentId), head);
        if (!CryptographicOperations.FixedTimeEquals(
                expected.AsSpan(0, MacLength), body.AsSpan(8, MacLength)))
            return null;

        Prune(current);
        if (!Used.TryAdd((studentId, window), 0)) return null;
        return studentId;
    }

    private static void Prune(uint current)
    {
        foreach (var key in Used.Keys)
        {
            if (Math.Abs((long)key.Window - current) > Tolerance + 2)
                Used.TryRemove(key, out _);
        }
    }
}
