using System.Net;
using System.Net.Http;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Windows;

namespace Visits11.Services;

/// <summary>
/// Автопоиск телефона-камеры в сети (USB-модем или Wi-Fi) и приём кадров.
/// Телефон отдаёт JPEG по GET /frame?since=N (long-poll ~0.8с), поэтому
/// соединение инициирует ПК — файрвол Windows для камеры не спрашивает.
/// </summary>
public sealed class CameraLink : IDisposable
{
    private const int Port = 8090;

    private CancellationTokenSource? _cts;
    private Task? _worker;

    /// <summary>Кадр (JPEG, поворот в градусах). Вызывается в потоке UI.</summary>
    public event Action<byte[], int>? FrameReceived;

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

            try { await StreamAsync(host, token); }
            catch (OperationCanceledException) { break; }
            catch { /* поток оборвался — пересканируем */ }

            try { await Task.Delay(1000, token); } catch (OperationCanceledException) { break; }
        }
    }

    /// <summary>Перебирает адреса всех активных подсетей, ищет наш телефон по /info.</summary>
    private static async Task<string?> DiscoverAsync(CancellationToken token)
    {
        var targets = BuildTargets();
        foreach (var batch in targets.Chunk(60))
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

    /// <summary>Подключается к кандидату и проверяет, что это Visits11-камера.</summary>
    private static async Task<string?> ProbeAsync(string host, CancellationToken token)
    {
        try
        {
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(token);
            timeout.CancelAfter(TimeSpan.FromMilliseconds(600));

            using var tcp = new TcpClient();
            await tcp.ConnectAsync(host, Port, timeout.Token);

            var stream = tcp.GetStream();
            var request = Encoding.ASCII.GetBytes(
                $"GET /info HTTP/1.1\r\nHost: {host}\r\nConnection: close\r\n\r\n");
            await stream.WriteAsync(request, timeout.Token);

            var buffer = new byte[1024];
            var read = await stream.ReadAsync(buffer, timeout.Token);
            var response = Encoding.ASCII.GetString(buffer, 0, read);
            if (response.Contains("200") && response.Contains("visits11-camera"))
            {
                return host;
            }
        }
        catch
        {
            // этот адрес не наш — просто пропускаем
        }
        return null;
    }

    /// <summary>Цикл кадров: запрашивает /frame?since=N один за другим.</summary>
    private async Task StreamAsync(string host, CancellationToken token)
    {
        using var client = new HttpClient { Timeout = TimeSpan.FromSeconds(4) };
        client.DefaultRequestHeaders.ConnectionClose = true;

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

    /// <summary>Адреса всех подсетей (/24 и мельче) активных Ethernet/Wi-Fi интерфейсов.</summary>
    private static List<string> BuildTargets()
    {
        var targets = new List<string>();
        var own = new HashSet<string>();
        try
        {
            foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (nic.OperationalStatus != OperationalStatus.Up) continue;
                if (nic.NetworkInterfaceType is not (NetworkInterfaceType.Ethernet
                                                     or NetworkInterfaceType.Wireless80211)) continue;

                var description = nic.Description;
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

    private void RaiseFrame(byte[] jpeg, int rotation)
    {
        var dispatcher = Application.Current?.Dispatcher;
        if (dispatcher is null) return;
        dispatcher.BeginInvoke(() => FrameReceived?.Invoke(jpeg, rotation));
    }
}
