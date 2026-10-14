using System.Net;
using System.Net.Http;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Windows;

namespace Visits11.Services;

/// <summary>
/// Связь с телефоном преподавателя по USB-кабелю («USB-модем»).
/// ПК сам находит телефон и забирает BLE-касания студентов:
/// GET /event (long-poll) → обработать → POST /event_result.
/// Все соединения исходящие со стороны ПК — брандмауэр Windows ничего не блокирует.
/// </summary>
public sealed class PhoneLink : IDisposable
{
    private BoardLink? _board;
    private const int Port = 8090;

    /// <summary>Признак телефона преподавателя в ответе GET /info.</summary>
    private const string Signature = "visits11-teacher";
    private const string LegacySignature = "visits11-camera";

    /// <summary>Стандартные адреса USB-модема Android/iPhone — проверяются первыми за миллисекунды.</summary>
    private static readonly string[] PriorityUsbHosts =
    {
        "192.168.42.129", // стандартный адрес USB-модема Android (RNDIS)
        "192.168.42.1",
        "192.168.43.129",
        "192.168.43.1",
        "192.168.225.1",
        "172.20.10.1",
    };

    private CancellationTokenSource? _cts;
    private Task? _worker;

    /// <summary>BLE-запрос с телефона (json) → ответ (json). Блокирующий, потокобезопасный.</summary>
    public event Func<string, string>? MarkRelay;

    public void Start()
    {
        if (_worker is not null || _board is not null) return;
#if PHONE_TERMINAL
        // The phone distribution is compiled separately; no user-created mode file.
        _cts = new CancellationTokenSource();
        var token = _cts.Token;
        _worker = Task.Run(() => RunAsync(token));
#else
        // Default standalone EXE: automatically discover ESP32 over USB.
        _board = new BoardLink(request => MarkRelay?.Invoke(request) ?? "{\"code\":4}");
        _board.Start();
#endif
    }

    public void Dispose()
    {
        _board?.Dispose();
        try { _cts?.Cancel(); } catch { /* уже остановлен */ }
        // Do not wait on Dispatcher thread while an event is being processed.

        _cts = null;
        _worker = null;
    }

    // ------------------------------------------------------------- главный цикл

    private async Task RunAsync(CancellationToken token)
    {
        while (!token.IsCancellationRequested)
        {
            string? host = null;
            try { host = await DiscoverAsync(token); }
            catch (OperationCanceledException) { break; }
            catch { /* ошибка поиска — попробуем ещё раз */ }

            if (host is null)
            {
                try { await Task.Delay(1500, token); } catch (OperationCanceledException) { break; }
                continue;
            }

            try
            {
                await EventStreamAsync(host, token);
            }
            catch (OperationCanceledException) { break; }
            catch { /* связь оборвалась — ищем телефон заново */ }

            try { await Task.Delay(500, token); } catch (OperationCanceledException) { break; }
        }
    }

    /// <summary>
    /// Сначала мгновенно проверяет шлюзы и стандартные адреса USB-модема (192.168.42.129 и др.),
    /// затем при необходимости перебирает подсети активных интерфейсов ПК.
    /// </summary>
    private static async Task<string?> DiscoverAsync(CancellationToken token)
    {
        var (fastTargets, subnetTargets) = BuildTargets();

        var found = await ProbeBatchAsync(fastTargets, token);
        if (found is not null) return found;

        foreach (var batch in subnetTargets.Chunk(128))
        {
            if (token.IsCancellationRequested) return null;
            found = await ProbeBatchAsync(batch, token);
            if (found is not null) return found;
        }
        return null;
    }

    /// <summary>Проверяет пачку адресов параллельно и возвращает первый ответивший телефон, не дожидаясь остальных.</summary>
    private static async Task<string?> ProbeBatchAsync(IEnumerable<string> hosts, CancellationToken token)
    {
        var list = hosts.ToList();
        if (list.Count == 0) return null;

        using var batchCts = CancellationTokenSource.CreateLinkedTokenSource(token);
        var pending = list.Select(host => ProbeAsync(host, batchCts.Token)).ToList();

        while (pending.Count > 0 && !token.IsCancellationRequested)
        {
            Task<string?> finished;
            try
            {
                finished = await Task.WhenAny(pending);
            }
            catch
            {
                break;
            }
            pending.Remove(finished);

            if (finished.IsCompletedSuccessfully && finished.Result is string match)
            {
                try { batchCts.Cancel(); } catch { }
                return match;
            }
        }
        return null;
    }

    /// <summary>Подключается к кандидату и проверяет, что это телефон преподавателя Visits11.</summary>
    private static async Task<string?> ProbeAsync(string host, CancellationToken token)
    {
        var text = new StringBuilder();
        try
        {
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(token);
            timeout.CancelAfter(TimeSpan.FromMilliseconds(1200));

            using var tcp = new TcpClient();
            await tcp.ConnectAsync(host, Port, timeout.Token);

            var stream = tcp.GetStream();
            var request = Encoding.ASCII.GetBytes(
                $"GET /info HTTP/1.1\r\nHost: {host}\r\nConnection: close\r\n\r\n");
            await stream.WriteAsync(request, timeout.Token);

            var buffer = new byte[2048];
            while (text.Length <= 4096)
            {
                int read;
                try
                {
                    read = await stream.ReadAsync(buffer, timeout.Token);
                }
                catch (OperationCanceledException)
                {
                    break;
                }
                if (read == 0) break;
                text.Append(Encoding.ASCII.GetString(buffer, 0, read));
                var current = text.ToString();
                if (current.Contains(Signature) || current.Contains(LegacySignature)) break;
            }
        }
        catch
        {
            // этот адрес не наш — просто пропускаем
        }
        var response = text.ToString();
        return response.Contains("200") && (response.Contains(Signature) || response.Contains(LegacySignature))
            ? host
            : null;
    }

    /// <summary>
    /// Забирает BLE-события с телефона: GET /event (long-poll) → обработать → POST /event_result.
    /// </summary>
    private async Task EventStreamAsync(string host, CancellationToken token)
    {
        using var client = new HttpClient { Timeout = TimeSpan.FromSeconds(6) };
        client.DefaultRequestHeaders.ConnectionClose = true;

        var failures = 0;
        var appliedPower = -1;
        while (!token.IsCancellationRequested && failures < 4)
        {
            try
            {
                var power = BluetoothSettings.Current.Power;
                if (power != appliedPower)
                {
                    using var settings = await client.GetAsync($"http://{host}:{Port}/power?level={power}", token);
                    if (settings.IsSuccessStatusCode) appliedPower = power;
                }
                using var response = await client.GetAsync($"http://{host}:{Port}/event?wait=3000", token);
                if (response.StatusCode == HttpStatusCode.NoContent)
                {
                    failures = 0;
                    continue;
                }
                if (!response.IsSuccessStatusCode)
                {
                    failures++;
                    try { await Task.Delay(1000, token); } catch (OperationCanceledException) { break; }
                    continue;
                }

                failures = 0;
                var request = await response.Content.ReadAsStringAsync(token);
                if (request.Length == 0) continue;

                var handler = MarkRelay;
                var result = handler is not null ? InvokeOnUi(handler, request) : "{\"code\":4}";

                using var content = new StringContent(result, Encoding.UTF8, "application/json");
                await client.PostAsync($"http://{host}:{Port}/event_result", content, token);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch
            {
                failures++;
                try { await Task.Delay(500, token); } catch (OperationCanceledException) { break; }
            }
        }
    }

    // ------------------------------------------------------------- адреса сети

    /// <summary>
    /// В русской Windows драйвер USB-модема называется «Удаленное устройство для совместного
    /// доступа к Интернету на основе NDIS» (без букв USB или RNDIS), а интерфейс — «Ethernet 2».
    /// Поэтому сначала берём стандартные адреса USB-модема и шлюзы всех активных адаптеров,
    /// а затем перебираем подсети /24 всех кабельных/NDIS/USB интерфейсов ПК.
    /// </summary>
    private static (List<string> Fast, List<string> Subnets) BuildTargets()
    {
        var fast = new List<string>(PriorityUsbHosts);
        var subnets = new List<string>();
        var own = new HashSet<string>();

        try
        {
            var interfaces = NetworkInterface.GetAllNetworkInterfaces()
                .Where(n => n.OperationalStatus == OperationalStatus.Up
                            && n.NetworkInterfaceType != NetworkInterfaceType.Loopback
                            && n.NetworkInterfaceType != NetworkInterfaceType.Tunnel)
                // кабельные и NDIS/USB-адаптеры проверяем раньше Wi-Fi
                .OrderBy(n => n.NetworkInterfaceType == NetworkInterfaceType.Wireless80211 ? 1 : 0)
                .ToList();

            foreach (var nic in interfaces)
            {
                var props = nic.GetIPProperties();

                // В режиме USB-модема телефон сам выдаёт ПК адрес по DHCP и числится шлюзом
                foreach (var gateway in props.GatewayAddresses)
                {
                    if (gateway.Address.AddressFamily == AddressFamily.InterNetwork &&
                        !IPAddress.IsLoopback(gateway.Address))
                    {
                        fast.Add(gateway.Address.ToString());
                    }
                }

                foreach (var address in props.UnicastAddresses)
                {
                    if (address.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                    var ipBytes = address.Address.GetAddressBytes();
                    if (ipBytes[0] == 169 && ipBytes[1] == 254) continue; // APIPA без DHCP

                    own.Add(address.Address.ToString());

                    // На любой подсети Android/iOS телефон почти всегда сидит на .129 или .1
                    var ip = ToUint(ipBytes);
                    var slash24 = ip & 0xFFFFFF00u;
                    fast.Add(ToStringIp(slash24 | 129u));
                    fast.Add(ToStringIp(slash24 | 1u));

                    var prefix = address.PrefixLength is >= 24 and <= 30 ? address.PrefixLength : 24;
                    var mask = uint.MaxValue << (32 - prefix);
                    var network = ip & mask;
                    var broadcast = network | ~mask;
                    for (var host = network + 1; host < broadcast; host++)
                    {
                        subnets.Add(ToStringIp(host));
                    }
                }
            }
        }
        catch
        {
            // сетевые интерфейсы недоступны — останутся стандартные адреса PriorityUsbHosts
        }

        var cleanFast = fast.Where(t => !own.Contains(t)).Distinct().ToList();
        var fastSet = new HashSet<string>(cleanFast);
        var cleanSubnets = subnets.Where(t => !own.Contains(t) && !fastSet.Contains(t)).Distinct().ToList();
        return (cleanFast, cleanSubnets);
    }

    private static string InvokeOnUi(Func<string, string> handler, string request)
    {
        try
        {
            var dispatcher = Application.Current?.Dispatcher;
            if (dispatcher is null || dispatcher.CheckAccess())
            {
                return handler(request);
            }
            return dispatcher.Invoke(() => handler(request));
        }
        catch
        {
            return "{\"code\":4}";
        }
    }

    private static uint ToUint(byte[] bytes)
        => (uint)(bytes[0] << 24 | bytes[1] << 16 | bytes[2] << 8 | bytes[3]);

    private static string ToStringIp(uint value)
        => $"{(value >> 24) & 0xFF}.{(value >> 16) & 0xFF}.{(value >> 8) & 0xFF}.{value & 0xFF}";
}
