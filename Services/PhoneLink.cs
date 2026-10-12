using System.IO.Ports;
using System.Net;
using System.Net.Http;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using System.Windows;

namespace Visits11.Services;

/// <summary>
/// Связь с терминалом преподавателя по USB-кабелю:
/// - В обычной сборке (KubGAU-Journal.exe) — телефон преподавателя («USB-модем»):
///   GET /event (long-poll) → обработать → POST /event_result.
/// - В сборке под плату (KubGAU-Journal-Plata.exe, USE_BOARD) — плата ESP32 + PN532 по USB Serial (COM-порт, 115200):
///   PING → VISITS11-ESP32, TAP:&lt;id&gt;:&lt;token&gt;:&lt;device&gt; → REPLY:&lt;id&gt;:&lt;code&gt;:&lt;name&gt;.
/// </summary>
public sealed class PhoneLink : IDisposable
{
    private const int Port = 8090;

    /// <summary>Признак телефона преподавателя в ответе GET /info.</summary>
    private const string Signature = "visits11-teacher";
    private const string LegacySignature = "visits11-camera";

    /// <summary>Признак платы ESP32 в ответе на команду PING по USB Serial.</summary>
    private const string BoardSignature = "VISITS11-ESP32";
    private const int BoardBaudRate = 115200;

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

    /// <summary>NFC/BLE-запрос с терминала (json) → ответ (json). Блокирующий, потокобезопасный.</summary>
    public event Func<string, string>? NfcRelay;

    public void Start()
    {
        if (_worker is not null) return;
        _cts = new CancellationTokenSource();
#if USE_BOARD
        _worker = Task.Run(() => RunBoardSerialAsync(_cts.Token));
#else
        _worker = Task.Run(() => RunAsync(_cts.Token));
#endif
    }

    public void Dispose()
    {
        try { _cts?.Cancel(); } catch { /* уже остановлен */ }
        try { _worker?.Wait(TimeSpan.FromSeconds(2)); } catch { /* задачи отменяются сами */ }
        _cts?.Dispose();
        _cts = null;
        _worker = null;
    }

    // ------------------------------------------------- связь с платой ESP32 (USB COM-порт)

    private async Task RunBoardSerialAsync(CancellationToken token)
    {
        while (!token.IsCancellationRequested)
        {
            SerialPort? port = null;
            try
            {
                port = await DiscoverBoardPortAsync(token);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch
            {
                // ошибка при поиске порта — повторим на следующем шаге
            }

            if (port is null)
            {
                try { await Task.Delay(1000, token); } catch (OperationCanceledException) { break; }
                continue;
            }

            try
            {
                await BoardEventStreamAsync(port, token);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch
            {
                // кабель выдернули или порт закрылся — ищем плату заново
            }
            finally
            {
                CloseSerialQuietly(port);
            }

            try { await Task.Delay(400, token); } catch (OperationCanceledException) { break; }
        }
    }

    /// <summary>
    /// Перебирает COM-порты Windows (начиная с верхних номеров, где обычно сидит USB-UART CP2102/CH340)
    /// и находит плату ESP32 по рукопожатию PING → VISITS11-ESP32.
    /// Открытый порт возвращается сразу (чтобы не перезагружать ESP32 повторным открытием DTR/RTS).
    /// </summary>
    private static async Task<SerialPort?> DiscoverBoardPortAsync(CancellationToken token)
    {
        string[] portNames;
        try
        {
            portNames = SerialPort.GetPortNames()
                .Distinct(StringComparer.OrdinalIgnoreCase)
                .OrderByDescending(ParseComNumber)
                .ToArray();
        }
        catch
        {
            return null;
        }

        foreach (var portName in portNames)
        {
            if (token.IsCancellationRequested) return null;

            SerialPort? port = null;
            try
            {
                port = new SerialPort(portName, BoardBaudRate, Parity.None, 8, StopBits.One)
                {
                    Encoding = Encoding.UTF8,
                    NewLine = "\n",
                    ReadTimeout = 150,
                    WriteTimeout = 500,
                    DtrEnable = false,
                    RtsEnable = false,
                    Handshake = Handshake.None,
                };
                port.Open();

                try
                {
                    port.DiscardInBuffer();
                    port.DiscardOutBuffer();
                }
                catch { }

                // На некоторых драйверах CP2102/CH340 открытие порта кратковременно дёргает DTR/RTS
                // и перезагружает ESP32 (~350–450 мс до старта setup). Делаем до 3 попыток PING,
                // не закрывая порт.
                var collected = new StringBuilder();
                var buffer = new byte[512];
                var pingBytes = Encoding.ASCII.GetBytes("\nPING\n");

                for (var attempt = 0; attempt < 3 && !token.IsCancellationRequested; attempt++)
                {
                    port.Write(pingBytes, 0, pingBytes.Length);

                    var deadline = Environment.TickCount64 + 550;
                    while (Environment.TickCount64 < deadline && !token.IsCancellationRequested)
                    {
                        try
                        {
                            var available = port.BytesToRead;
                            if (available > 0)
                            {
                                var toRead = Math.Min(available, buffer.Length);
                                var read = port.Read(buffer, 0, toRead);
                                if (read > 0)
                                {
                                    collected.Append(Encoding.UTF8.GetString(buffer, 0, read));
                                    if (collected.ToString().Contains(BoardSignature, StringComparison.Ordinal))
                                    {
                                        return port;
                                    }
                                }
                            }
                            else
                            {
                                await Task.Delay(20, token);
                            }
                        }
                        catch (TimeoutException)
                        {
                            // ждём дальше
                        }
                    }
                }
            }
            catch (OperationCanceledException)
            {
                CloseSerialQuietly(port);
                throw;
            }
            catch
            {
                // порт занят или не отвечает — проверяем следующий
            }

            CloseSerialQuietly(port);
        }

        return null;
    }

    /// <summary>
    /// Читает строки TAP:&lt;id&gt;:&lt;token&gt;:&lt;device&gt; от платы ESP32, проверяет студента в журнале
    /// и отправляет обратно строку REPLY:&lt;id&gt;:&lt;code&gt;:&lt;studentName&gt;\n.
    /// </summary>
    private async Task BoardEventStreamAsync(SerialPort port, CancellationToken token)
    {
        var lineBuffer = new List<byte>(512);
        var readChunk = new byte[256];
        var pingBytes = Encoding.ASCII.GetBytes("PING\n");

        var lastRxAt = Environment.TickCount64;
        var lastPingAt = Environment.TickCount64;

        while (!token.IsCancellationRequested && port.IsOpen)
        {
            var available = port.BytesToRead;
            if (available > 0)
            {
                var count = port.Read(readChunk, 0, Math.Min(available, readChunk.Length));
                if (count > 0)
                {
                    lastRxAt = Environment.TickCount64;
                    for (var i = 0; i < count; i++)
                    {
                        var b = readChunk[i];
                        if (b == (byte)'\n')
                        {
                            if (lineBuffer.Count > 0)
                            {
                                var line = Encoding.UTF8.GetString(lineBuffer.ToArray()).TrimEnd('\r', ' ');
                                lineBuffer.Clear();
                                if (line.Length > 0)
                                {
                                    HandleBoardLine(port, line);
                                }
                            }
                        }
                        else if (b != (byte)'\r')
                        {
                            if (lineBuffer.Count < 4096)
                            {
                                lineBuffer.Add(b);
                            }
                            else
                            {
                                lineBuffer.Clear();
                            }
                        }
                    }
                }
                continue;
            }

            var now = Environment.TickCount64;
            if (now - lastRxAt > 8000)
            {
                // плата перестала отвечать на PING — переподключаемся
                break;
            }
            if (now - lastRxAt > 3000 && now - lastPingAt > 2500)
            {
                lastPingAt = now;
                port.Write(pingBytes, 0, pingBytes.Length);
            }

            await Task.Delay(10, token);
        }
    }

    private void HandleBoardLine(SerialPort port, string line)
    {
        if (!line.StartsWith("TAP:", StringComparison.Ordinal))
        {
            return;
        }

        // Формат от платы: TAP:<id>:<token>:<device>
        var rest = line.Substring(4);
        var firstColon = rest.IndexOf(':');
        if (firstColon <= 0) return;

        var idPart = rest.Substring(0, firstColon);
        if (!long.TryParse(idPart, out var id) || id <= 0) return;

        var afterId = rest.Substring(firstColon + 1);
        var secondColon = afterId.IndexOf(':');
        string tapToken;
        string tapDevice;
        if (secondColon >= 0)
        {
            tapToken = afterId.Substring(0, secondColon);
            tapDevice = afterId.Substring(secondColon + 1);
        }
        else
        {
            tapToken = afterId;
            tapDevice = string.Empty;
        }

        var requestJson = JsonSerializer.Serialize(new
        {
            id,
            token = tapToken,
            device = tapDevice,
        });

        var handler = NfcRelay;
        var resultJson = handler is not null ? InvokeOnUi(handler, requestJson) : "{\"code\":4}";

        var replyCode = 4;
        var replyName = string.Empty;
        try
        {
            using var doc = JsonDocument.Parse(resultJson);
            var root = doc.RootElement;
            if (root.TryGetProperty("code", out var codeProp) && codeProp.ValueKind == JsonValueKind.Number)
            {
                replyCode = codeProp.GetInt32();
            }
            if (root.TryGetProperty("value", out var valueProp) && valueProp.ValueKind == JsonValueKind.String)
            {
                replyName = valueProp.GetString() ?? string.Empty;
            }
        }
        catch
        {
            replyCode = 4;
            replyName = string.Empty;
        }

        replyName = replyName.Replace('\r', ' ').Replace('\n', ' ').Trim();
        var replyLine = $"REPLY:{id}:{replyCode}:{replyName}\n";
        var replyBytes = Encoding.UTF8.GetBytes(replyLine);
        port.Write(replyBytes, 0, replyBytes.Length);
    }

    private static int ParseComNumber(string portName)
    {
        if (portName.StartsWith("COM", StringComparison.OrdinalIgnoreCase) &&
            int.TryParse(portName.AsSpan(3), out var num))
        {
            return num;
        }
        return 0;
    }

    private static void CloseSerialQuietly(SerialPort? port)
    {
        if (port is null) return;
        try { if (port.IsOpen) port.Close(); } catch { }
        try { port.Dispose(); } catch { }
    }

    // ------------------------------------------------------------- главный цикл (телефон)

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
    /// Забирает NFC-события с телефона: GET /event (long-poll) → обработать → POST /event_result.
    /// </summary>
    private async Task EventStreamAsync(string host, CancellationToken token)
    {
        using var client = new HttpClient { Timeout = TimeSpan.FromSeconds(6) };
        client.DefaultRequestHeaders.ConnectionClose = true;

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
                    failures++;
                    try { await Task.Delay(1000, token); } catch (OperationCanceledException) { break; }
                    continue;
                }

                failures = 0;
                var request = await response.Content.ReadAsStringAsync(token);
                if (request.Length == 0) continue;

                var handler = NfcRelay;
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
