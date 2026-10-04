using System.Net;
using System.Net.Http.Json;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text.Json;

namespace Visits11.Services;

public sealed record RemoteMark(string Id, string StudentKey, string DeviceId, string OccurredAt);

public sealed class RemoteSyncService : IDisposable
{
    private sealed record SettingsFile(string BaseUrl, string SyncKey);
    private sealed record AckRequest(string Id);

    private readonly string _settingsPath;
    private readonly HttpClient _http = new() { Timeout = TimeSpan.FromSeconds(5) };
    private CancellationTokenSource? _cts;
    private volatile string _baseUrl = "http://127.0.0.1:8091";
    private volatile string _syncKey = string.Empty;
    private volatile string _status = "Сервер не настроен";

    public RemoteSyncService(string settingsPath)
    {
        _settingsPath = settingsPath;
        Load();
    }

    public event Action? StatusChanged;
    public Func<RemoteMark, bool>? ProcessMark { get; set; }

    public string BaseUrl => _baseUrl;
    public string SyncKey => _syncKey;
    public string Status => _status;
    public string StatusLabel => string.IsNullOrWhiteSpace(_syncKey) ? "Сервер: настройка" : _status;

    public string UrlForQr()
    {
        if (!Uri.TryCreate(_baseUrl, UriKind.Absolute, out var uri)) return _baseUrl;
        if (!uri.IsLoopback) return _baseUrl.TrimEnd('/');
        var address = GetLanAddress();
        return $"{uri.Scheme}://{address}:{uri.Port}";
    }

    public void Configure(string baseUrl, string syncKey)
    {
        if (!Uri.TryCreate(baseUrl.Trim(), UriKind.Absolute, out var uri)
            || (uri.Scheme != Uri.UriSchemeHttp && uri.Scheme != Uri.UriSchemeHttps))
        {
            throw new ArgumentException("Укажите полный адрес сервера, например http://192.168.1.10:8091");
        }
        if (string.IsNullOrWhiteSpace(syncKey)) throw new ArgumentException("Введите ключ преподавателя из окна серверного EXE");
        _baseUrl = uri.ToString().TrimEnd('/');
        _syncKey = syncKey.Trim();
        Save();
        SetStatus("Сервер: проверка связи");
    }

    public void Start()
    {
        if (_cts is not null) return;
        _cts = new CancellationTokenSource();
        var cancellationToken = _cts.Token;
        _ = Task.Run(() => PollLoop(cancellationToken));
    }

    public void Dispose()
    {
        _cts?.Cancel();
        _cts?.Dispose();
        _cts = null;
        _http.Dispose();
    }

    private async Task PollLoop(CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            try
            {
                if (string.IsNullOrWhiteSpace(_syncKey))
                {
                    SetStatus("Сервер: настройка");
                    await Task.Delay(2000, cancellationToken);
                    continue;
                }

                using var request = new HttpRequestMessage(HttpMethod.Get, _baseUrl + "/api/marks/pending");
                request.Headers.TryAddWithoutValidation("X-Teacher-Key", _syncKey);
                using var response = await _http.SendAsync(request, cancellationToken);
                response.EnsureSuccessStatusCode();
                var marks = await response.Content.ReadFromJsonAsync<List<RemoteMark>>(cancellationToken: cancellationToken) ?? new List<RemoteMark>();
                SetStatus("Сервер: подключён");

                foreach (var mark in marks)
                {
                    if (cancellationToken.IsCancellationRequested) break;
                    bool processed;
                    try
                    {
                        processed = ProcessMark?.Invoke(mark) ?? false;
                    }
                    catch
                    {
                        processed = false;
                    }
                    if (!processed) continue;

                    using var ack = new HttpRequestMessage(HttpMethod.Post, _baseUrl + "/api/marks/ack")
                    {
                        Content = JsonContent.Create(new AckRequest(mark.Id))
                    };
                    ack.Headers.TryAddWithoutValidation("X-Teacher-Key", _syncKey);
                    using var ackResponse = await _http.SendAsync(ack, cancellationToken);
                    ackResponse.EnsureSuccessStatusCode();
                }
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                break;
            }
            catch
            {
                SetStatus("Сервер: нет связи");
            }

            try
            {
                await Task.Delay(2000, cancellationToken);
            }
            catch (OperationCanceledException)
            {
                break;
            }
        }
    }

    private void Load()
    {
        try
        {
            if (!File.Exists(_settingsPath)) return;
            var settings = JsonSerializer.Deserialize<SettingsFile>(File.ReadAllText(_settingsPath));
            if (settings is null) return;
            if (Uri.TryCreate(settings.BaseUrl, UriKind.Absolute, out var uri)
                && (uri.Scheme == Uri.UriSchemeHttp || uri.Scheme == Uri.UriSchemeHttps))
            {
                _baseUrl = uri.ToString().TrimEnd('/');
            }
            _syncKey = settings.SyncKey ?? string.Empty;
        }
        catch
        {
            _baseUrl = "http://127.0.0.1:8091";
            _syncKey = string.Empty;
        }
    }

    private void Save()
    {
        var directory = Path.GetDirectoryName(_settingsPath);
        if (!string.IsNullOrEmpty(directory)) Directory.CreateDirectory(directory);
        File.WriteAllText(_settingsPath, JsonSerializer.Serialize(new SettingsFile(_baseUrl, _syncKey), new JsonSerializerOptions { WriteIndented = true }));
    }

    private void SetStatus(string value)
    {
        if (_status == value) return;
        _status = value;
        StatusChanged?.Invoke();
    }

    private static string GetLanAddress()
    {
        foreach (var network in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (network.OperationalStatus != OperationalStatus.Up) continue;
            if (network.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
            foreach (var address in network.GetIPProperties().UnicastAddresses)
            {
                if (address.Address.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(address.Address))
                    return address.Address.ToString();
            }
        }
        return "127.0.0.1";
    }
}
