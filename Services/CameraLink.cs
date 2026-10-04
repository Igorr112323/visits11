using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Windows;

namespace Visits11.Services;

/// <summary>
/// Поиск Android-телефона преподавателя только в подсети USB-модема и приём событий по кабелю.
/// Телефон отдаёт JPEG по GET /frame?since=N (long-poll ~0.8с), поэтому
/// соединение инициирует ПК — файрвол Windows для камеры не спрашивает.
/// </summary>
public sealed class CameraLink : IDisposable
{
    private const int Port = 8090;

    private readonly record struct ProbeTarget(IPAddress RemoteAddress, IPAddress LocalAddress);
    private sealed record PhoneEndpoint(string Host, IPAddress LocalAddress);

    private readonly int _serverPort;

    private CancellationTokenSource? _cts;
    private Task? _worker;

    /// <summary>Кадр (JPEG, поворот в градусах). Вызывается в потоке UI.</summary>
    public event Action<byte[], int>? FrameReceived;

    /// <summary>NFC-запрос с телефона (json) → ответ (json). Блокирующий, потокобезопасный.</summary>
    public event Func<string, string>? NfcRelay;

    /// <param name="serverPort">порт встроенного сервера — телефон передаёт его студентам для NFC-отметок.</param>
    public CameraLink(int serverPort)
    {
        _serverPort = serverPort;
    }

    public void Start()
    {
        if (_worker is not null) return;
        _cts = new CancellationTokenSource();
        _worker = Task.Run(() => RunAsync(_cts.Token));
    }

    public void Dispose()
    {
        try { _cts?.Cancel(); } catch { /* уже остановлен */ }
        try { _worker?.Wait(TimeSpan.FromSeconds(2)); } catch { /* задачи отменяются сами */ }
        _cts?.Dispose();
        _cts = null;
        _worker = null;
    }

    // ------------------------------------------------------------- главный цикл

    private async Task RunAsync(CancellationToken token)
    {
        while (!token.IsCancellationRequested)
        {
            PhoneEndpoint? phone = null;
            try { phone = await DiscoverAsync(token); }
            catch (OperationCanceledException) { break; }
            catch { /* ошибка поиска — попробуем ещё раз */ }

            if (phone is null)
            {
                try { await Task.Delay(3000, token); } catch (OperationCanceledException) { break; }
                continue;
            }

            try
            {
                // ПК сам инициирует оба соединения, привязывая их к локальному USB/RNDIS-адресу.
                // Оборвалось видео — переподключаемся и восстанавливаем NFC-обмен.
                using var linked = CancellationTokenSource.CreateLinkedTokenSource(token);
                var cameraTask = StreamAsync(phone, linked.Token);
                var eventTask = EventStreamAsync(phone.Host, phone.LocalAddress, linked.Token);
                await cameraTask;
                linked.Cancel();
                try { await eventTask; } catch { /* отменено — нормально */ }
            }
            catch (OperationCanceledException) { break; }
            catch { /* поток оборвался — пересканируем */ }

            try { await Task.Delay(1000, token); } catch (OperationCanceledException) { break; }
        }
    }

    /// <summary>
    /// Windows may report RNDIS with a generic Ethernet name, so inspect active
    /// wired adapters (not Wi-Fi), prioritizing recognizable USB adapters.
    /// The APK responds only on its USB-tether address and to a peer in that subnet.
    /// </summary>
    private static async Task<PhoneEndpoint?> DiscoverAsync(CancellationToken token)
    {
        var targets = BuildTargets();
        foreach (var batch in targets.Chunk(128))
        {
            if (token.IsCancellationRequested) return null;

            var probes = batch.Select(target => ProbeAsync(target, token)).ToArray();
            try { await Task.WhenAll(probes); }
            catch { /* пробы не бросают исключений — на всякий случай */ }

            foreach (var probe in probes)
            {
                if (probe.IsCompletedSuccessfully && probe.Result is not null)
                {
                    return probe.Result;
                }
            }
        }
        return null;
    }

    /// <summary>ПК проверяет кандидата исходящим запросом, привязанным к USB-адаптеру.</summary>
    private static async Task<PhoneEndpoint?> ProbeAsync(ProbeTarget target, CancellationToken token)
    {
        var text = new StringBuilder();
        try
        {
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(token);
            timeout.CancelAfter(TimeSpan.FromMilliseconds(1200));

            using var tcp = new TcpClient(AddressFamily.InterNetwork);
            tcp.Client.Bind(new IPEndPoint(target.LocalAddress, 0));
            await tcp.ConnectAsync(target.RemoteAddress, Port, timeout.Token);

            var stream = tcp.GetStream();
            var host = target.RemoteAddress.ToString();
            var request = Encoding.ASCII.GetBytes(
                $"GET /info HTTP/1.1\r\nHost: {host}\r\nConnection: close\r\n\r\n");
            await stream.WriteAsync(request, timeout.Token);

            // читаем ответ ДО КОНЦА (телефон закрывает соединение): ответ может
            // прийти несколькими кусками, одного чтения мало
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
                    break; // время вышло — проверяем то, что успели прочитать
                }
                if (read == 0) break;
                text.Append(Encoding.ASCII.GetString(buffer, 0, read));
                if (text.ToString().Contains("visits11-camera")) break;
            }
        }
        catch
        {
            // этот адрес не наш — просто пропускаем
        }
        var response = text.ToString();
        return response.Contains("200") && response.Contains("visits11-camera")
            ? new PhoneEndpoint(target.RemoteAddress.ToString(), target.LocalAddress)
            : null;
    }

    /// <summary>
    /// Забирает NFC-события с телефона: GET /event (long-poll) → обработать → POST /event_result.
    /// Всё исходящее со стороны ПК — брандмауэр Windows ничего не спрашивает.
    /// </summary>
    private async Task EventStreamAsync(string host, IPAddress localAddress, CancellationToken token)
    {
        using var client = CreateUsbHttpClient(localAddress, TimeSpan.FromSeconds(6));

        var failures = 0;
        while (!token.IsCancellationRequested && failures < 4)
        {
            try
            {
                using var response = await client.GetAsync($"http://{host}:{Port}/event?wait=3000", token);
                if (response.StatusCode == HttpStatusCode.NoContent)
                {
                    failures = 0;
                    continue;
                }
                if (!response.IsSuccessStatusCode)
                {
                    // старый APK преподавателя без /event — подождём и повторим
                    failures++;
                    try { await Task.Delay(1000, token); } catch (OperationCanceledException) { break; }
                    continue;
                }

                failures = 0;
                var request = await response.Content.ReadAsStringAsync(token);
                if (request.Length == 0) continue;

                var handler = NfcRelay;
                var result = handler is not null ? handler(request) : "{\"code\":4}";

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

    /// <summary>Цикл кадров: запрашивает /frame?since=N один за другим.</summary>
    private async Task StreamAsync(PhoneEndpoint phone, CancellationToken token)
    {
        var host = phone.Host;
        // ПК инициирует запросы, каждый сокет привязан к USB/RNDIS-адресу.
        using var client = CreateUsbHttpClient(phone.LocalAddress, TimeSpan.FromSeconds(4));
        client.DefaultRequestHeaders.Add("X-Host", $"{phone.LocalAddress}:{_serverPort}");

        var failures = 0;
        long seq = 0;

        while (!token.IsCancellationRequested && failures < 4)
        {
            try
            {
                using var response = await client.GetAsync($"http://{host}:{Port}/frame?since={seq}", token);
                if (response.StatusCode == HttpStatusCode.NoContent)
                {
                    // телефон жив, кадра пока нет
                    failures = 0;
                    continue;
                }
                if (!response.IsSuccessStatusCode)
                {
                    failures++;
                    continue;
                }

                var rotation = 0;
                if (response.Headers.TryGetValues("X-Rot", out var rotValues) &&
                    int.TryParse(rotValues.FirstOrDefault(), out var parsedRot))
                {
                    rotation = ((parsedRot % 360) + 360) % 360;
                }
                if (response.Headers.TryGetValues("X-Seq", out var seqValues))
                {
                    long.TryParse(seqValues.FirstOrDefault(), out seq);
                }

                var jpeg = await response.Content.ReadAsByteArrayAsync(token);
                if (jpeg.Length > 0)
                {
                    RaiseFrame(jpeg, rotation);
                    failures = 0;
                }
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
    /// Windows reports some phone RNDIS adapters with a generic or localized name.
    /// Scan active wired Ethernet interfaces (never Wi-Fi), preferring recognizable
    /// USB adapters. The APK answers only on its USB-tether address/subnet.
    /// </summary>
    private static List<ProbeTarget> BuildTargets()
    {
        var adapters = new List<(IPAddress LocalAddress, int PrefixLength, List<IPAddress> Gateways, bool IsUsb)>();
        var usbGatewayTargets = new List<ProbeTarget>();
        var otherGatewayTargets = new List<ProbeTarget>();
        var usbSubnetTargets = new List<ProbeTarget>();
        var otherSubnetTargets = new List<ProbeTarget>();
        var seenTargets = new HashSet<ProbeTarget>();

        try
        {
            foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (nic.OperationalStatus != OperationalStatus.Up) continue;
                if (nic.NetworkInterfaceType != NetworkInterfaceType.Ethernet) continue;
                if (IsVirtualAdapter(nic)) continue;

                var properties = nic.GetIPProperties();
                var gateways = properties.GatewayAddresses
                    .Select(gateway => gateway.Address)
                    .Where(IsPrivateOrLinkLocalIPv4)
                    .ToList();
                var isUsb = IsUsbTetherAdapter(nic);

                foreach (var address in properties.UnicastAddresses)
                {
                    if (!IsPrivateOrLinkLocalIPv4(address.Address)) continue;
                    if (IPAddress.IsLoopback(address.Address)) continue;
                    adapters.Add((address.Address, address.PrefixLength, gateways, isUsb));
                }
            }

            var ownAddresses = adapters.Select(adapter => adapter.LocalAddress).ToHashSet();
            foreach (var adapter in adapters.OrderByDescending(adapter => adapter.IsUsb))
            {
                foreach (var gateway in adapter.Gateways)
                {
                    if (gateway.Equals(adapter.LocalAddress) || ownAddresses.Contains(gateway)) continue;
                    var target = new ProbeTarget(gateway, adapter.LocalAddress);
                    if (!seenTargets.Add(target)) continue;
                    (adapter.IsUsb ? usbGatewayTargets : otherGatewayTargets).Add(target);
                }
            }

            foreach (var adapter in adapters.OrderByDescending(adapter => adapter.IsUsb))
            {
                // USB tethering normally uses /24. For host routes, use /24 as fallback.
                var prefix = adapter.PrefixLength is >= 24 and <= 30 ? adapter.PrefixLength : 24;
                var ip = ToUint(adapter.LocalAddress.GetAddressBytes());
                var mask = uint.MaxValue << (32 - prefix);
                var network = ip & mask;
                var broadcast = network | ~mask;
                for (var host = network + 1; host < broadcast; host++)
                {
                    var remoteAddress = IPAddress.Parse(ToStringIp(host));
                    if (!IsPrivateOrLinkLocalIPv4(remoteAddress) || ownAddresses.Contains(remoteAddress)) continue;
                    var target = new ProbeTarget(remoteAddress, adapter.LocalAddress);
                    if (!seenTargets.Add(target)) continue;
                    (adapter.IsUsb ? usbSubnetTargets : otherSubnetTargets).Add(target);
                }
            }
        }
        catch
        {
            // сетевые интерфейсы недоступны — вернём уже собранные адреса
        }

        return usbGatewayTargets
            .Concat(otherGatewayTargets)
            .Concat(usbSubnetTargets)
            .Concat(otherSubnetTargets)
            .ToList();
    }

    private static bool IsVirtualAdapter(NetworkInterface nic)
    {
        var identity = $"{nic.Name} {nic.Description}";
        return identity.Contains("Hyper-V", StringComparison.OrdinalIgnoreCase)
               || identity.Contains("Virtual", StringComparison.OrdinalIgnoreCase)
               || identity.Contains("VMware", StringComparison.OrdinalIgnoreCase)
               || identity.Contains("Loopback", StringComparison.OrdinalIgnoreCase)
               || identity.Contains("Wi-Fi Direct", StringComparison.OrdinalIgnoreCase);
    }

    private static bool IsPrivateOrLinkLocalIPv4(IPAddress address)
    {
        if (address.AddressFamily != AddressFamily.InterNetwork) return false;
        var bytes = address.GetAddressBytes();
        return bytes[0] == 10
               || (bytes[0] == 172 && bytes[1] is >= 16 and <= 31)
               || (bytes[0] == 192 && bytes[1] == 168)
               || (bytes[0] == 169 && bytes[1] == 254);
    }

    private static bool IsUsbTetherAdapter(NetworkInterface nic)
    {
        var identity = $"{nic.Name} {nic.Description}";
        return identity.Contains("USB", StringComparison.OrdinalIgnoreCase)
               || identity.Contains("RNDIS", StringComparison.OrdinalIgnoreCase)
               || identity.Contains("NDIS", StringComparison.OrdinalIgnoreCase)
               || identity.Contains("NCM", StringComparison.OrdinalIgnoreCase)
               || identity.Contains("Tether", StringComparison.OrdinalIgnoreCase)
               || identity.Contains("Gadget", StringComparison.OrdinalIgnoreCase)
               || identity.Contains("Android", StringComparison.OrdinalIgnoreCase);
    }

    private static uint ToUint(byte[] bytes)
        => (uint)(bytes[0] << 24 | bytes[1] << 16 | bytes[2] << 8 | bytes[3]);

    /// <summary>
    /// Создаёт HTTP-клиент без системного прокси и привязывает каждый исходящий сокет
    /// к локальному USB/RNDIS-адресу Windows. Входящее соединение к ПК не требуется.
    /// </summary>
    private static HttpClient CreateUsbHttpClient(IPAddress localAddress, TimeSpan timeout)
    {
        var handler = new SocketsHttpHandler
        {
            UseProxy = false,
            ConnectCallback = (context, cancellationToken) =>
                ConnectOnUsbAsync(context, localAddress, cancellationToken)
        };
        var client = new HttpClient(handler) { Timeout = timeout };
        client.DefaultRequestHeaders.ConnectionClose = true;
        return client;
    }

    private static async ValueTask<Stream> ConnectOnUsbAsync(
        SocketsHttpConnectionContext context,
        IPAddress localAddress,
        CancellationToken cancellationToken)
    {
        if (!IPAddress.TryParse(context.DnsEndPoint.Host, out var remoteAddress)
            || remoteAddress.AddressFamily != AddressFamily.InterNetwork)
        {
            throw new SocketException((int)SocketError.AddressFamilyNotSupported);
        }

        var socket = new Socket(AddressFamily.InterNetwork, SocketType.Stream, ProtocolType.Tcp);
        try
        {
            socket.Bind(new IPEndPoint(localAddress, 0));
            await socket.ConnectAsync(
                new IPEndPoint(remoteAddress, context.DnsEndPoint.Port), cancellationToken);
            return new NetworkStream(socket, ownsSocket: true);
        }
        catch
        {
            socket.Dispose();
            throw;
        }
    }

    private static string ToStringIp(uint value)
        => $"{(value >> 24) & 0xFF}.{(value >> 16) & 0xFF}.{(value >> 8) & 0xFF}.{value & 0xFF}";

    private void RaiseFrame(byte[] jpeg, int rotation)
    {
        var dispatcher = Application.Current?.Dispatcher;
        if (dispatcher is null) return;
        dispatcher.BeginInvoke(() => FrameReceived?.Invoke(jpeg, rotation));
    }
}
