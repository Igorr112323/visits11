using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Windows;

namespace Visits11.Services;

public sealed record StudentDto(int Id, string Name, bool Marked);

public sealed record MarkResult(bool Ok, string? Name = null, string? PhoneId = null)
{
    public static readonly MarkResult Fail = new(false);
}

/// <summary>
/// Встроенный сервер (порт 8090–8099):
///  • POST /api/frame?rot=N — кадры камеры с телефона преподавателя (JPEG);
///  • GET / — страница отметки для студентов (по QR-коду);
///  • GET /api/checkin?phone=ID — автоматическая отметка по сохранённому ID телефона;
///  • GET /api/mark?student=ID — отметка выбранного студента (генерирует ID телефона).
/// </summary>
public sealed class PhoneServer : IDisposable
{
    private TcpListener? _listener;
    private CancellationTokenSource? _cts;

    public int Port { get; private set; }
    public bool IsRunning { get; private set; }
    public string LanAddress { get; private set; } = "127.0.0.1";
    public string Url => $"http://{LanAddress}:{Port}/";

    /// <summary>Поставщики данных и обработчики отметок (вызываются в потоке UI).</summary>
    public Func<bool> IsRollcallActive { get; set; } = () => false;
    public Func<string> GetGroupName { get; set; } = () => "";
    public Func<List<StudentDto>> GetStudents { get; set; } = () => new();
    public Func<int, MarkResult> MarkStudent { get; set; } = _ => MarkResult.Fail;
    public Func<string, MarkResult> CheckInByPhone { get; set; } = _ => MarkResult.Fail;

    /// <summary>Кадр видео с телефона (JPEG, rotation). Вызывается в потоке UI.</summary>
    public event Action<byte[], int>? FrameReceived;

    public void Start()
    {
        if (IsRunning) return;

        LanAddress = DetectLanAddress();
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
                    case "/api/frame":
                        Respond(stream, "204 No Content", "text/plain", Array.Empty<byte>());
                        if (request.Body is { Length: > 0 } jpeg)
                        {
                            var rotation = ParseRotation(request.Query);
                            RaiseFrame(jpeg, rotation);
                        }
                        break;

                    case "/api/checkin":
                    {
                        var phone = GetQueryParam(request.Query, "phone");
                        var result = OnUi(() => CheckInByPhone(phone));
                        RespondJson(stream, result);
                        break;
                    }

                    case "/api/mark":
                    {
                        var idText = GetQueryParam(request.Query, "student");
                        var result = int.TryParse(idText, out var studentId)
                            ? OnUi(() => MarkStudent(studentId))
                            : MarkResult.Fail;
                        RespondJson(stream, result);
                        break;
                    }

                    case "/":
                    case "/checkin":
                    case "/index.html":
                        Respond(stream, "200 OK", "text/html; charset=utf-8", BuildPage());
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
                    if (contentLength > 15_000_000) return null;
                }
            }

            if (headerEnd >= 0 && (int)all.Length - (headerEnd + 4) >= contentLength) break;
            if (all.Length > 15_000_000) return null;
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

    private static int ParseRotation(string query)
    {
        var raw = GetQueryParam(query, "rot");
        return int.TryParse(raw, out var value) ? ((value % 360) + 360) % 360 : 0;
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

    private static void RespondJson(NetworkStream stream, MarkResult result)
    {
        var json = "{\"ok\":" + (result.Ok ? "true" : "false") +
                   ",\"name\":" + JsonString(result.Name) +
                   ",\"phoneId\":" + JsonString(result.PhoneId) + "}";
        Respond(stream, "200 OK", "application/json", Encoding.UTF8.GetBytes(json));
    }

    private static string JsonString(string? value)
        => value is null ? "null" : "\"" + value.Replace("\\", "\\\\").Replace("\"", "\\\"") + "\"";

    // ------------------------------------------------------------- страница

    private string BuildPage()
    {
        if (!OnUi(() => IsRollcallActive())) return InactivePage;

        var group = OnUi(() => GetGroupName());
        var students = OnUi(() => GetStudents());

        var list = string.Join(",", students.Select(s =>
            "{\"id\":" + s.Id + ",\"name\":" + JsonString(s.Name) + ",\"done\":" + (s.Marked ? "true" : "false") + "}"));

        return ActivePageTemplate
            .Replace("__GROUP__", WebUtility.HtmlEncode(group))
            .Replace("__STUDENTS__", list);
    }

    private const string ActivePageTemplate = @"<!doctype html>
<html lang='ru'><head><meta charset='utf-8'>
<meta name='viewport' content='width=device-width,initial-scale=1'>
<title>Отметка</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:system-ui,-apple-system,'Segoe UI',Roboto,sans-serif;background:#0a0d1a;color:#f0f2fa;min-height:100vh;display:flex;align-items:center;justify-content:center;padding:20px}
.card{width:100%;max-width:420px;background:#141827;border:1px solid #2a3150;border-radius:20px;padding:22px}
h1{font-size:17px;font-weight:700}
.sub{color:#9099b8;font-size:13px;margin:4px 0 16px}
ul{list-style:none;display:flex;flex-direction:column;gap:8px;max-height:60vh;overflow:auto}
ul button{width:100%;text-align:left;padding:13px 14px;border-radius:12px;border:1px solid #2a3150;background:#1c2136;color:#f0f2fa;font-size:15px}
ul button:active{background:#6366f1;border-color:#6366f1}
ul button.done{opacity:.4}
.ok{display:none;text-align:center;padding:14px 0}
.mark{width:64px;height:64px;border-radius:50%;background:rgba(52,211,153,.15);color:#34d399;font-size:34px;line-height:64px;margin:0 auto 14px}
.ok b{font-size:16px}
.ok small{display:block;color:#9099b8;margin-top:8px;font-size:13px}
</style></head><body><div class='card'>
<div id='main'><h1>Отметка посещаемости</h1><p class='sub'>Группа: __GROUP__</p><ul id='list'></ul></div>
<div id='ok' class='ok'><div class='mark'>&#10003;</div><b id='okName'></b><small>Вы отмечены &mdash; можно закрыть страницу.</small></div>
</div>
<script>
var LIST=__STUDENTS__;
function showOk(n){document.getElementById('main').style.display='none';document.getElementById('ok').style.display='block';document.getElementById('okName').textContent=n;}
function render(){var ul=document.getElementById('list');LIST.forEach(function(s){var li=document.createElement('li');var b=document.createElement('button');b.textContent=s.done?s.name+' \u2713':s.name;if(s.done){b.className='done';}else{b.onclick=function(){fetch('/api/mark?student='+s.id).then(function(r){return r.json()}).then(function(d){if(d.ok){if(d.phoneId){localStorage.setItem('v11phone',d.phoneId);}showOk(d.name);}});};}li.appendChild(b);ul.appendChild(li);});}
var saved=localStorage.getItem('v11phone');
if(saved){fetch('/api/checkin?phone='+encodeURIComponent(saved)).then(function(r){return r.json()}).then(function(d){if(d.ok){showOk(d.name);}else{render();}}).catch(function(){render();});}
else{render();}
</script></body></html>";

    private const string InactivePage = @"<!doctype html>
<html lang='ru'><head><meta charset='utf-8'>
<meta name='viewport' content='width=device-width,initial-scale=1'>
<title>Отметка</title>
<style>
body{font-family:system-ui,sans-serif;background:#0a0d1a;color:#f0f2fa;min-height:100vh;display:flex;align-items:center;justify-content:center;padding:20px}
.card{background:#141827;border:1px solid #2a3150;border-radius:20px;padding:28px 34px;text-align:center}
h1{font-size:16px;font-weight:700}
p{color:#9099b8;font-size:13px;margin-top:6px}
</style></head><body><div class='card'><h1>Перекличка не активна</h1><p>Обновите страницу, когда преподаватель начнёт перекличку.</p></div></body></html>";

    // ------------------------------------------------------------- утилиты

    private void RaiseFrame(byte[] jpeg, int rotation)
    {
        var dispatcher = Application.Current?.Dispatcher;
        if (dispatcher is null) return;
        dispatcher.BeginInvoke(() => FrameReceived?.Invoke(jpeg, rotation));
    }

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
            var addresses = NetworkInterface.GetAllNetworkInterfaces()
                .Where(n => n.OperationalStatus == OperationalStatus.Up)
                .Where(n => n.NetworkInterfaceType is NetworkInterfaceType.Ethernet
                            or NetworkInterfaceType.Wireless80211)
                .SelectMany(n => n.GetIPProperties().UnicastAddresses)
                .Where(a => a.Address.AddressFamily == AddressFamily.InterNetwork
                            && !IPAddress.IsLoopback(a.Address))
                .Select(a => a.Address.ToString())
                .ToList();
            return addresses.FirstOrDefault() ?? "127.0.0.1";
        }
        catch
        {
            return "127.0.0.1";
        }
    }
}
