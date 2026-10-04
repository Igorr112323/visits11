using System.Collections.Concurrent;
using System.IO;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Windows;

namespace Visits11.Services;

/// <summary>
/// Встроенный сервер (порт 8090–8099) для приложений студентов:
///  • GET  /api/ping — опознавательный ответ (автопоиск ПК в сети);
///  • POST /api/login — вход по логину/паролю, выдаёт токен сессии;
///  • GET  /api/qr?token= — PNG с текущим QR студента (сам ПК генерирует
///    зашифрованный код каждые 5 секунд, время телефона не используется).
/// QR содержит зашифрованные AES данные (логин занятого студента, окно
/// времени, ID переклички, нонс) — по скриншоту нельзя понять, кто внутри,
/// и через ~10 секунд код перестаёт действовать.
/// </summary>
public sealed class PhoneServer : IDisposable
{
    private TcpListener? _listener;
    private CancellationTokenSource? _cts;
    private readonly ConcurrentDictionary<string, int> _sessions = new();

    public int Port { get; private set; }
    public bool IsRunning { get; private set; }
    public string Url => $"http://{DetectLanAddress()}:{Port}/";

    /// <summary>Проверка логина/пароля (в потоке UI): (успех, отказ из-за устройства, имя, id студента).</summary>
    public Func<string, string, string, (bool Ok, bool DeviceBlocked, string Name, int StudentId)> Login { get; set; } =
        (_, _, _) => (false, false, "", 0);

    /// <summary>Отметка студента по id (в потоке UI): (успех, отказ из-за устройства, имя).</summary>
    public Func<int, string, (bool Ok, bool DeviceBlocked, string Name)> MarkStudent { get; set; } =
        (_, _) => (false, false, "");

    public Func<string, string, (bool Ok, bool DeviceMismatch, string Name)> MarkStudentByQrId { get; set; } =
        (_, _) => (false, false, "");

    /// <summary>PNG текущего QR студента; null — перекличка не активна.</summary>
    public Func<int, byte[]?> GetQrPng { get; set; } = _ => null;

    /// <summary>Студент по привязанному устройству (в потоке UI); null — устройство неизвестно.</summary>
    public Func<string, int?> FindStudentByDevice { get; set; } = _ => null;

    /// <summary>Выдан токен сессии — сохранить, чтобы перезапуск ПК не разлогинивал студентов.</summary>
    public Action<string, int>? TokenIssued { get; set; }

    /// <summary>NFC-касаание: вход не прошёл — показать преподавателю (в потоке UI).</summary>
    public Action<string>? NfcLoginFailed { get; set; }

    /// <summary>NFC-касаание: отметка не прошла — показать преподавателю (в потоке UI).</summary>
    public Action? NfcMarkFailed { get; set; }

    public void Start()
    {
        if (IsRunning) return;

        for (var port = 8090; port <= 8099; port++)
        {
            try
            {
                var listener = new TcpListener(IPAddress.Any, port);
                listener.Start();
                _listener = listener;
                Port = port;
                break;
            }
            catch (SocketException)
            {
                // порт занят — пробуем следующий
            }
        }

        if (_listener is null) return;

        _cts = new CancellationTokenSource();
        IsRunning = true;
        var token = _cts.Token;
        _ = Task.Run(() => AcceptLoopAsync(_listener, token), token);
    }

    public void Dispose()
    {
        IsRunning = false;
        try { _cts?.Cancel(); } catch { /* уже остановлен */ }
        try { _listener?.Stop(); } catch { /* уже остановлен */ }
        _cts?.Dispose();
        _cts = null;
        _listener = null;
    }

    // ------------------------------------------------------------- приём данных

    private async Task AcceptLoopAsync(TcpListener listener, CancellationToken token)
    {
        while (!token.IsCancellationRequested)
        {
            TcpClient client;
            try
            {
                client = await listener.AcceptTcpClientAsync(token);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (SocketException)
            {
                continue;
            }
            _ = Task.Run(() => HandleClientAsync(client, token), token);
        }
    }

    private async Task HandleClientAsync(TcpClient client, CancellationToken token)
    {
        try
        {
            using (client)
            {
                client.ReceiveTimeout = 15000;
                client.SendTimeout = 15000;
                var stream = client.GetStream();

                var request = await ReadRequestAsync(stream, token);
                if (request is null) return;

                switch (request.Path)
                {
                    case "/api/ping":
                        Respond(stream, "200 OK", "application/json",
                            Encoding.UTF8.GetBytes("{\"app\":\"visits11-server\"}"));
                        break;

                    case "/api/login" when request.Method == "POST":
                        HandleLogin(stream, request.Body);
                        break;

                    case "/api/qr":
                        HandleQr(stream, GetQueryParam(request.Query, "token"));
                        break;

                    case "/api/nfc_mark" when request.Method == "POST":
                        HandleNfcMark(stream, request.Body);
                        break;

                    case "/":
                    case "/index.html":
                        Respond(stream, "200 OK", "text/html; charset=utf-8",
                            Encoding.UTF8.GetBytes(InfoPage));
                        break;

                    case "/favicon.ico":
                        Respond(stream, "204 No Content", "text/plain", Array.Empty<byte>());
                        break;

                    default:
                        Respond(stream, "404 Not Found", "text/plain", Encoding.UTF8.GetBytes("not found"));
                        break;
                }
            }
        }
        catch
        {
            // соединение оборвано — игнорируем
        }
    }

    private void HandleLogin(NetworkStream stream, byte[]? body)
    {
        string login = "";
        string password = "";
        string device = "";
        try
        {
            using var doc = JsonDocument.Parse(Encoding.UTF8.GetString(body ?? Array.Empty<byte>()));
            if (doc.RootElement.TryGetProperty("login", out var loginValue) &&
                loginValue.ValueKind == JsonValueKind.String)
            {
                login = loginValue.GetString() ?? "";
            }
            if (doc.RootElement.TryGetProperty("password", out var passwordValue) &&
                passwordValue.ValueKind == JsonValueKind.String)
            {
                password = passwordValue.GetString() ?? "";
            }
            if (doc.RootElement.TryGetProperty("device", out var deviceValue) &&
                deviceValue.ValueKind == JsonValueKind.String)
            {
                device = deviceValue.GetString() ?? "";
            }
        }
        catch (JsonException)
        {
            // повреждённый запрос — просто отказ
        }

        var result = OnUi(() => Login(login, password, device));
        if (result.Ok)
        {
            var token = IssueToken(result.StudentId);
            RespondJson(stream, new Dictionary<string, object?>
            {
                ["ok"] = true,
                ["name"] = result.Name,
                ["token"] = token,
            });
        }
        else if (result.DeviceBlocked)
        {
            // аккаунт привязан к другому телефону
            RespondJson(stream, new Dictionary<string, object?>
            {
                ["ok"] = false,
                ["device"] = true,
            });
        }
        else
        {
            RespondJson(stream, new Dictionary<string, object?> { ["ok"] = false });
        }
    }

    private void HandleQr(NetworkStream stream, string token)
    {
        if (!_sessions.TryGetValue(token, out var studentId))
        {
            Respond(stream, "401 Unauthorized", "application/json",
                Encoding.UTF8.GetBytes("{\"ok\":false}"));
            return;
        }

        var png = OnUi(() => GetQrPng(studentId));
        if (png is null || png.Length == 0)
        {
            // перекличка не активна
            Respond(stream, "204 No Content", "text/plain", Array.Empty<byte>());
        }
        else
        {
            Respond(stream, "200 OK", "image/png", png);
        }
    }

    /// <summary>Отметка «телефон к телефону»: телефон преподавателя пересылает токен студента.</summary>
    private void HandleNfcMark(NetworkStream stream, byte[]? body)
    {
        string token = "";
        string device = "";
        try
        {
            using var doc = JsonDocument.Parse(Encoding.UTF8.GetString(body ?? Array.Empty<byte>()));
            if (doc.RootElement.TryGetProperty("token", out var value) &&
                value.ValueKind == JsonValueKind.String)
            {
                token = value.GetString() ?? "";
            }
            if (doc.RootElement.TryGetProperty("device", out var deviceValue) &&
                deviceValue.ValueKind == JsonValueKind.String)
            {
                device = deviceValue.GetString() ?? "";
            }
        }
        catch (JsonException)
        {
            // повреждённый запрос
        }

        if (string.IsNullOrEmpty(token) || !_sessions.TryGetValue(token, out var studentId))
        {
            // сессия неизвестна — студенту нужно перевойти
            Respond(stream, "200 OK", "application/json",
                Encoding.UTF8.GetBytes("{\"ok\":false,\"relogin\":true}"));
            return;
        }

        var result = OnUi(() => MarkStudent(studentId, device));
        if (result.DeviceBlocked)
        {
            // отметка с чужого телефона
            RespondJson(stream, new Dictionary<string, object?>
            {
                ["ok"] = false,
                ["device"] = true,
            });
            return;
        }
        RespondJson(stream, new Dictionary<string, object?>
        {
            ["ok"] = result.Ok,
            ["name"] = result.Name,
        });
    }

    // ------------------------------------------------------------- HTTP-парсер

    private sealed class HttpRequest
    {
        public string Method = string.Empty;
        public string Path = string.Empty;
        public string Query = string.Empty;
        public byte[]? Body;
    }

    private static async Task<HttpRequest?> ReadRequestAsync(NetworkStream stream, CancellationToken token)
    {
        var buffer = new byte[32 * 1024];
        var all = new MemoryStream();
        HttpRequest? request = null;
        var headerEnd = -1;
        var contentLength = 0;

        while (true)
        {
            var read = await stream.ReadAsync(buffer.AsMemory(0, buffer.Length), token);
            if (read == 0) return null;
            all.Write(buffer, 0, read);

            var bytes = all.GetBuffer();
            if (headerEnd < 0)
            {
                headerEnd = FindHeaderEnd(bytes, (int)all.Length);
                if (headerEnd >= 0)
                {
                    var headerText = Encoding.UTF8.GetString(bytes, 0, headerEnd);
                    if (!TryParseHeader(headerText, out request, out contentLength)) return request;
                    if (contentLength > 1_000_000) return null;
                }
            }

            if (headerEnd >= 0 && (int)all.Length - (headerEnd + 4) >= contentLength) break;
            if (all.Length > 1_000_000) return null;
        }

        if (request is null || headerEnd < 0) return request;

        if (contentLength > 0)
        {
            var body = new byte[contentLength];
            Array.Copy(all.GetBuffer(), headerEnd + 4, body, 0, contentLength);
            request.Body = body;
        }
        return request;
    }

    private static int FindHeaderEnd(byte[] buffer, int length)
    {
        for (var i = 0; i < length - 3; i++)
        {
            if (buffer[i] == 13 && buffer[i + 1] == 10 && buffer[i + 2] == 13 && buffer[i + 3] == 10) return i;
        }
        return -1;
    }

    private static bool TryParseHeader(string headerText, out HttpRequest request, out int contentLength)
    {
        request = new HttpRequest();
        contentLength = 0;

        var lines = headerText.Split("\r\n");
        var first = lines.Length > 0 ? lines[0].Split(' ') : Array.Empty<string>();
        if (first.Length < 2) return false;

        request.Method = first[0].ToUpperInvariant();
        var target = first[1];
        var queryIndex = target.IndexOf('?');
        if (queryIndex >= 0)
        {
            request.Path = target[..queryIndex];
            request.Query = target[(queryIndex + 1)..];
        }
        else
        {
            request.Path = target;
            request.Query = string.Empty;
        }

        foreach (var line in lines.Skip(1))
        {
            var colon = line.IndexOf(':');
            if (colon <= 0) continue;
            var name = line[..colon].Trim();
            var value = line[(colon + 1)..].Trim();
            if (name.Equals("Content-Length", StringComparison.OrdinalIgnoreCase))
            {
                int.TryParse(value, out contentLength);
            }
        }
        return true;
    }

    private static string GetQueryParam(string query, string name)
    {
        foreach (var pair in query.Split('&', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
        {
            var eq = pair.IndexOf('=');
            if (eq < 0) continue;
            if (pair[..eq].Equals(name, StringComparison.OrdinalIgnoreCase))
            {
                return Uri.UnescapeDataString(pair[(eq + 1)..]);
            }
        }
        return string.Empty;
    }

    // ------------------------------------------------------------- ответы

    private static void Respond(NetworkStream stream, string status, string contentType, byte[] body)
    {
        var head = Encoding.ASCII.GetBytes(
            $"HTTP/1.1 {status}\r\n" +
            $"Content-Type: {contentType}\r\n" +
            $"Content-Length: {body.Length}\r\n" +
            "Connection: close\r\n" +
            "Cache-Control: no-store\r\n\r\n");
        stream.Write(head, 0, head.Length);
        stream.Write(body, 0, body.Length);
        stream.Flush();
    }

    private static void RespondJson(NetworkStream stream, Dictionary<string, object?> payload)
    {
        Respond(stream, "200 OK", "application/json", JsonSerializer.SerializeToUtf8Bytes(payload));
    }

    private const string InfoPage = @"<!doctype html>
<html lang='ru'><head><meta charset='utf-8'>
<meta name='viewport' content='width=device-width,initial-scale=1'>
<title>Отметка</title>
<style>
body{font-family:system-ui,sans-serif;background:#0a0d1a;color:#f0f2fa;min-height:100vh;display:flex;align-items:center;justify-content:center;padding:20px}
.card{background:#141827;border:1px solid #2a3150;border-radius:20px;padding:28px 34px;text-align:center}
h1{font-size:16px;font-weight:700}
p{color:#9099b8;font-size:13px;margin-top:6px}
</style></head><body><div class='card'><h1>Отметка посещаемости</h1><p>Установите приложение «Visits11 Студент» и войдите по логину и паролю.</p></div></body></html>";

    /// <summary>Токены из базы: студенты остаются «вошедшими» и после перезапуска ПК.</summary>
    public void LoadSessions(IEnumerable<KeyValuePair<string, int>> sessions)
    {
        foreach (var (storedToken, studentId) in sessions)
        {
            _sessions[storedToken] = studentId;
        }
    }

    /// <summary>Выдаёт новый токен сессии и сообщает о нём для сохранения.</summary>
    private string IssueToken(int studentId)
    {
        var token = Convert.ToHexString(RandomNumberGenerator.GetBytes(16));
        _sessions[token] = studentId;
        TokenIssued?.Invoke(token, studentId);
        return token;
    }

    /// <summary>
    /// Обработка NFC-запроса, который ПК сам забрал с телефона преподавателя
    /// (исходящее соединение — брандмауэр Windows не мешает).
    /// Запрос: {"type":1,"login","password","device"} или {"type":2,"token","device"}.
    /// Ответ: {"code":0..5,"value":...} — 0 вход+токен, 1 отмечен+имя, 2 плохой пароль,
    /// 3 перелогинься, 4 не получилось, 5 чужой телефон.
    /// </summary>
    public string Relay(string json)
    {
        int type = 0;
        long id = 0;
        string login = "", password = "", token = "", device = "", studentKey = "";
        try
        {
            using var doc = JsonDocument.Parse(json);
            var root = doc.RootElement;
            if (root.TryGetProperty("type", out var typeValue) &&
                typeValue.ValueKind == JsonValueKind.Number)
            {
                type = typeValue.GetInt32();
            }
            if (root.TryGetProperty("login", out var loginValue) &&
                loginValue.ValueKind == JsonValueKind.String)
            {
                login = loginValue.GetString() ?? "";
            }
            if (root.TryGetProperty("password", out var passwordValue) &&
                passwordValue.ValueKind == JsonValueKind.String)
            {
                password = passwordValue.GetString() ?? "";
            }
            if (root.TryGetProperty("token", out var tokenValue) &&
                tokenValue.ValueKind == JsonValueKind.String)
            {
                token = tokenValue.GetString() ?? "";
            }
            if (root.TryGetProperty("studentKey", out var studentKeyValue) &&
                studentKeyValue.ValueKind == JsonValueKind.String)
            {
                studentKey = studentKeyValue.GetString() ?? "";
            }
            if (root.TryGetProperty("device", out var deviceValue) &&
                deviceValue.ValueKind == JsonValueKind.String)
            {
                device = deviceValue.GetString() ?? "";
            }
            if (root.TryGetProperty("id", out var idValue) &&
                idValue.ValueKind == JsonValueKind.Number)
            {
                id = idValue.GetInt64();
            }
        }
        catch (JsonException)
        {
            return "{\"code\":4}";
        }

        if (type == 1)
        {
            var loginResult = OnUi(() => Login(login, password, device));
            if (loginResult.DeviceBlocked) return RelayJson(5, string.Empty, id);
            if (!loginResult.Ok)
            {
                OnUi(() => { NfcLoginFailed?.Invoke(login); return true; });
                return RelayJson(2, string.Empty, id);
            }

            // первое касание отмечает сразу же; если перекличка не идёт или
            // студент не из этой группы — честно отвечаем «не получилось»,
            // а не «вы отмечены»
            var mark = OnUi(() => MarkStudent(loginResult.StudentId, device));
            if (mark.DeviceBlocked) return RelayJson(5, string.Empty, id);
            if (!mark.Ok)
            {
                OnUi(() => { NfcMarkFailed?.Invoke(); return true; });
                return RelayJson(4, string.Empty, id);
            }
            return RelayJson(0, IssueToken(loginResult.StudentId), id);
        }

        if (type == 3)
        {
            var mark = OnUi(() => MarkStudentByQrId(studentKey, device));
            if (!mark.Ok)
            {
                OnUi(() => { NfcMarkFailed?.Invoke(); return true; });
                return RelayJson(4, string.Empty, id);
            }
            return RelayJson(1, mark.Name, id);
        }

        if (type == 2)
        {
            if (token.Length > 0 && _sessions.TryGetValue(token, out var studentId))
            {
                var mark = OnUi(() => MarkStudent(studentId, device));
                if (mark.DeviceBlocked) return RelayJson(5, string.Empty, id);
                if (!mark.Ok)
                {
                    OnUi(() => { NfcMarkFailed?.Invoke(); return true; });
                    return RelayJson(4, string.Empty, id);
                }
                return RelayJson(1, mark.Name, id);
            }

            // токен ПК незнаком (например, ПК перезапускали или меняли базу),
            // но телефон уже привязан к студенту — этим же касанием выдаём
            // свежий токен и отмечаем
            if (device.Length > 0)
            {
                var bound = OnUi(() => FindStudentByDevice(device));
                if (bound is int renewId)
                {
                    var mark = OnUi(() => MarkStudent(renewId, device));
                    if (mark.DeviceBlocked) return RelayJson(5, string.Empty, id);
                    if (!mark.Ok)
                    {
                        OnUi(() => { NfcMarkFailed?.Invoke(); return true; });
                        return RelayJson(4, string.Empty, id);
                    }
                    return RelayJson(0, IssueToken(renewId), id);
                }
            }

            return RelayJson(3, string.Empty, id);
        }

        return RelayJson(4, string.Empty);
    }

    private static string RelayJson(int code, string value, long id = 0)
    {
        var escaped = value.Replace("\\", "\\\\").Replace("\"", "\\\"");
        return id > 0
            ? $"{{\"code\":{code},\"value\":\"{escaped}\",\"id\":{id}}}"
            : $"{{\"code\":{code},\"value\":\"{escaped}\"}}";
    }

    // ------------------------------------------------------------- утилиты

    private static T OnUi<T>(Func<T> func)
    {
        var dispatcher = Application.Current?.Dispatcher;
        if (dispatcher is null) return default!;
        return dispatcher.CheckAccess() ? func() : dispatcher.Invoke(func);
    }

    private static string DetectLanAddress()
    {
        try
        {
            // Wi-Fi в приоритете: студенты чаще всего в беспроводной сети
            var addresses = NetworkInterface.GetAllNetworkInterfaces()
                .Where(n => n.OperationalStatus == OperationalStatus.Up)
                .Where(n => n.NetworkInterfaceType is NetworkInterfaceType.Ethernet
                            or NetworkInterfaceType.Wireless80211)
                // USB-модем телефона не подходит: студенты до него не достучатся
                .Where(n => !n.Description.Contains("RNDIS", StringComparison.OrdinalIgnoreCase)
                            && !n.Description.Contains("Remote NDIS", StringComparison.OrdinalIgnoreCase)
                            && !n.Description.Contains("NCM", StringComparison.OrdinalIgnoreCase)
                            && !n.Description.Contains("USB", StringComparison.OrdinalIgnoreCase))
                .OrderBy(n => n.NetworkInterfaceType == NetworkInterfaceType.Wireless80211 ? 0 : 1)
                .SelectMany(n => n.GetIPProperties().UnicastAddresses)
                .Where(a => a.Address.AddressFamily == AddressFamily.InterNetwork
                            && !IPAddress.IsLoopback(a.Address))
                .Select(a => a.Address.ToString())
                .Where(ip => !ip.StartsWith("192.168.42.") && !ip.StartsWith("192.168.44."))
                .ToList();
            return addresses.FirstOrDefault() ?? "127.0.0.1";
        }
        catch
        {
            return "127.0.0.1";
        }
    }
}
