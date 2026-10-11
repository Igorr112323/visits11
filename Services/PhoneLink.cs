using System.Net;
using System.Net.Http;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Windows;

namespace Visits11.Services;

/// <summary>
/// Связь с телефоном преподавателя — только по USB-кабелю, Wi-Fi не используется.
/// ПК сам находит телефон (телефон слушает лишь свой USB-интерфейс) и забирает
/// NFC-касания студентов: GET /event (long-poll) → обработать → POST /event_result.
/// Все соединения исходящие со стороны ПК — брандмауэр Windows ничего не блокирует.
/// </summary>
public sealed class PhoneLink : IDisposable
{
    private const int Port = 8090;

    /// <summary>Так телефон преподавателя отвечает на GET /info — по этому признаку его узнаём.</summary>
    private const string Signature = "visits11-teacher";

    private CancellationTokenSource? _cts;
    private Task? _worker;

    /// <summary>NFC-запрос с телефона (json) → ответ (json). Блокирующий, потокобезопасный.</summary>
    public event Func<string, string>? NfcRelay;

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
            string? host = null;
            try { host = await DiscoverAsync(token); }
            catch (OperationCanceledException) { break; }
            catch { /* ошибка поиска — попробуем ещё раз */ }

            if (host is null)
            {
                try { await Task.Delay(3000, token); } catch (OperationCanceledException) { break; }
                continue;
            }

            try
            {
                await EventStreamAsync(host, token);
            }
            catch (OperationCanceledException) { break; }
            catch { /* связь оборвалась — ищем телефон заново */ }

            try { await Task.Delay(1000, token); } catch (OperationCanceledException) { break; }
        }
    }

    /// <summary>Перебирает адреса активных подсетей (USB-модем виден как Ethernet) и ищет наш телефон.</summary>
    private static async Task<string?> DiscoverAsync(CancellationToken token)
    {
        var targets = BuildTargets();
        foreach (var batch in targets.Chunk(128))
        {
            if (token.IsCancellationRequested) return null;

            var probes = batch.Select(host => ProbeAsync(host, token)).ToArray();
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

    /// <summary>Подключается к кандидату и проверяет, что это телефон Visits11.</summary>
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
                if (text.ToString().Contains(Signature)) break;
            }
        }
        catch
        {
            // этот адрес не наш — просто пропускаем
        }
        var response = text.ToString();
        return response.Contains("200") && response.Contains(Signature) ? host : null;
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

    // ------------------------------------------------------------- адреса сети

    /// <summary>Слова, по которым Windows показывает USB-модем телефона в списке адаптеров.</summary>
    private static readonly string[] UsbAdapterMarks =
    {
        "rndis", "remote ndis", "usb", "tether", "android", "apple mobile", "iphone",
    };

    /// <summary>
    /// Адреса подсетей (/24 и мельче) только USB-модема телефона (в Windows он виден
    /// как Ethernet-адаптер с пометкой RNDIS/USB). Wi-Fi и обычный Ethernet пропускаем:
    /// программа не должна ни искать телефон в сети, ни обращаться к посторонним адресам.
    /// </summary>
    private static List<string> BuildTargets()
    {
        var targets = new List<string>();
        var own = new HashSet<string>();
        try
        {
            foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (nic.OperationalStatus != OperationalStatus.Up) continue;
                if (nic.NetworkInterfaceType != NetworkInterfaceType.Ethernet) continue;

                var description = nic.Description + " " + nic.Name;
                if (!UsbAdapterMarks.Any(mark => description.Contains(mark, StringComparison.OrdinalIgnoreCase)))
                    continue;

                if (description.Contains("Hyper-V", StringComparison.OrdinalIgnoreCase) ||
                    description.Contains("Virtual", StringComparison.OrdinalIgnoreCase) ||
                    description.Contains("VMware", StringComparison.OrdinalIgnoreCase) ||
                    description.Contains("Loopback", StringComparison.OrdinalIgnoreCase)) continue;

                foreach (var address in nic.GetIPProperties().UnicastAddresses)
                {
                    if (address.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                    own.Add(address.Address.ToString());

                    // только /24 и мельче — иначе перебор адресов затянется
                    var prefix = address.PrefixLength is >= 24 and <= 32 ? address.PrefixLength : 24;
                    var ip = ToUint(address.Address.GetAddressBytes());
                    var mask = uint.MaxValue << (32 - prefix);
                    var network = ip & mask;
                    var broadcast = network | ~mask;
                    for (var host = network + 1; host < broadcast; host++)
                    {
                        targets.Add(ToStringIp(host));
                    }
                }
            }
        }
        catch
        {
            // сетевые интерфейсы недоступны — вернём что есть
        }
        return targets.Where(t => !own.Contains(t)).Distinct().ToList();
    }

    private static uint ToUint(byte[] bytes)
        => (uint)(bytes[0] << 24 | bytes[1] << 16 | bytes[2] << 8 | bytes[3]);

    private static string ToStringIp(uint value)
        => $"{(value >> 24) & 0xFF}.{(value >> 16) & 0xFF}.{(value >> 8) & 0xFF}.{value & 0xFF}";
}
