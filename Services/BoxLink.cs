using System.IO.Ports;
using System.Text.Json;

namespace Visits11.Services;

/// <summary>
/// Связь с коробочкой-считывателем (ESP32) по USB COM-порту.
/// ПК сам находит порт (PING → VISITS11-ESP32) и обменивается отметками:
/// коробочка присылает «TAP:…», ПК отвечает «REPLY:…» — решение принимает программа,
/// точно так же, как для касаний с телефона преподавателя.
/// </summary>
public sealed class BoxLink : IDisposable
{
    private const int BaudRate = 115200;
    private const string Signature = "VISITS11-ESP32";
    private const int ReadTimeoutMs = 300;

    private CancellationTokenSource? _cts;
    private Task? _worker;

    /// <summary>Запрос отметки (json) → ответ (json). Такой же, как у телефона преподавателя.</summary>
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
        try { _worker?.Wait(TimeSpan.FromSeconds(2)); } catch { /* задача отменяется сама */ }
        _cts?.Dispose();
        _cts = null;
        _worker = null;
    }

    // ------------------------------------------------------------- главный цикл

    private async Task RunAsync(CancellationToken token)
    {
        while (!token.IsCancellationRequested)
        {
            SerialPort? port = null;
            try { port = FindBox(); }
            catch { /* поиск повторяем */ }

            if (port is null)
            {
                try { await Task.Delay(2000, token); } catch (OperationCanceledException) { break; }
                continue;
            }

            try { await Task.Run(() => Serve(port, token), token); }
            catch (OperationCanceledException) { Dispose(port); break; }
            catch { /* кабель выдернули — ищем порт заново */ }
            Dispose(port);

            try { await Task.Delay(1000, token); } catch (OperationCanceledException) { break; }
        }
    }

    /// <summary>Перебирает COM-порты и находит тот, где живёт коробочка.</summary>
    private SerialPort? FindBox()
    {
        foreach (var name in SerialPort.GetPortNames())
        {
            SerialPort? port = null;
            try
            {
                port = new SerialPort(name, BaudRate, Parity.None, 8, StopBits.One)
                {
                    ReadTimeout = ReadTimeoutMs,
                    WriteTimeout = 1000,
                    NewLine = "\n",
                    Handshake = Handshake.None,
                    // микросхема USB-порта подключает эти линии к кнопкам EN/BOOT — их нельзя тянуть вниз
                    DtrEnable = false,
                    RtsEnable = false,
                };
                port.Open();
                port.DiscardInBuffer();
                port.WriteLine("PING");

                var until = DateTime.UtcNow.AddMilliseconds(1500);
                while (DateTime.UtcNow < until)
                {
                    string line;
                    try { line = port.ReadLine(); }
                    catch (TimeoutException) { continue; }
                    if (line.Contains(Signature, StringComparison.Ordinal))
                    {
                        return port;
                    }
                }
                Dispose(port);
            }
            catch
            {
                Dispose(port);
            }
        }
        return null;
    }

    /// <summary>Читает строки коробочки и отвечает на отметки, пока порт открыт.</summary>
    private void Serve(SerialPort port, CancellationToken token)
    {
        var lastPing = DateTime.UtcNow;

        while (!token.IsCancellationRequested)
        {
            if ((DateTime.UtcNow - lastPing).TotalSeconds >= 2)
            {
                lastPing = DateTime.UtcNow;
                port.WriteLine("PING");
            }

            string line;
            try
            {
                line = port.ReadLine();
            }
            catch (TimeoutException)
            {
                continue;
            }
            catch
            {
                return;
            }

            if (line.StartsWith("TAP:", StringComparison.Ordinal))
            {
                HandleTap(port, line);
            }
        }
    }

    /// <summary>
    /// Отметка от коробочки: «TAP:<номер>:<код-студента>:<id-телефона>».
    /// Программа обрабатывает её как обычное касание и отвечает «REPLY:<номер>:<код>:<имя>».
    /// </summary>
    private void HandleTap(SerialPort port, string line)
    {
        var parts = line.Split(':', 4);
        if (parts.Length < 3)
        {
            return;
        }

        var id = parts[1];
        var token = parts[2];
        var device = parts.Length > 3 ? parts[3] : string.Empty;
        var request = $"{{\"id\":{id},\"token\":\"{Escape(token)}\",\"device\":\"{Escape(device)}\"}}";

        var handler = NfcRelay;
        var answer = handler is null ? "{\"code\":4,\"value\":\"\"}" : handler(request);
        var (code, value) = ParseAnswer(answer);

        try
        {
            port.WriteLine($"REPLY:{id}:{code}:{Clean(value)}");
        }
        catch
        {
            // порт закрылся — ответ не отправится, коробочка сама покажет «перекличка не идёт»
        }
    }

    // ------------------------------------------------------------- разбор ответа

    /// <summary>Ответ программы: {"id":N,"code":C,"value":"…"}.</summary>
    private static (int Code, string Value) ParseAnswer(string json)
    {
        try
        {
            using var document = JsonDocument.Parse(json);
            var root = document.RootElement;

            var code = root.TryGetProperty("code", out var codeValue) && codeValue.ValueKind == JsonValueKind.Number
                ? codeValue.GetInt32()
                : 4;
            var value = root.TryGetProperty("value", out var valueValue) && valueValue.ValueKind == JsonValueKind.String
                ? valueValue.GetString() ?? string.Empty
                : string.Empty;
            return (code, value);
        }
        catch
        {
            return (4, string.Empty);
        }
    }

    private static string Escape(string text)
        => text.Replace("\\", "\\\\").Replace("\"", "\\\"");

    /// <summary>Имя студента уходит одной строкой: переводы строк и двоеточия-разделители убираем.</summary>
    private static string Clean(string value)
        => value.Replace("\r", " ").Replace("\n", " ").Trim();

    private static void Dispose(SerialPort? port)
    {
        if (port is null) return;
        try { if (port.IsOpen) port.Close(); } catch { /* порт уже закрыт */ }
        try { port.Dispose(); } catch { /* уже освобождён */ }
    }
}
