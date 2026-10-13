using System.Diagnostics;
using System.IO.Ports;
using System.Management;
using System.Text;
using System.Text.Json;

namespace Visits11.Services;

/// <summary>USB UART: verify firmware signature before sending settings or student events.</summary>
public sealed class BoardLink : IDisposable
{
    private readonly Func<string, string> _relay;
    private readonly CancellationTokenSource _stop = new();
    private Task? _worker;
    public BoardLink(Func<string, string> relay) => _relay = relay;
    public void Start() => _worker ??= Task.Run(Run);
    public void Dispose() => _stop.Cancel(); // never wait on UI: relay may be using Dispatcher

    private static IEnumerable<string> UsbPorts()
    {
        var ports = new List<string>();
        using var search = new ManagementObjectSearcher("SELECT Name, PNPDeviceID FROM Win32_PnPEntity WHERE Name LIKE '%(COM%'");
        foreach (ManagementObject item in search.Get())
        {
            using (item)
            {
                var id = item["PNPDeviceID"]?.ToString() ?? "";
                if (!new[] { "VID_10C4", "VID_1A86", "VID_0403", "VID_303A" }.Any(id.Contains)) continue;
                var match = System.Text.RegularExpressions.Regex.Match(item["Name"]?.ToString() ?? "", @"\((COM\d+)\)");
                if (match.Success) ports.Add(match.Groups[1].Value);
            }
        }
        return ports;
    }
    private void Run()
    {
        while (!_stop.IsCancellationRequested)
        {
            try
            {
                foreach (var name in UsbPorts())
                {
                    if (_stop.IsCancellationRequested) return;
                    try { Connect(name); } catch { /* unplugged, occupied, or not our firmware */ }
                }
            }
            catch { /* WMI/driver temporarily unavailable */ }
            _stop.Token.WaitHandle.WaitOne(1200);
        }
    }
    private void Connect(string name)
    {
        using var port = new SerialPort(name, 115200) {
            ReadTimeout = 300, WriteTimeout = 500, Encoding = Encoding.ASCII,
            NewLine = "\n", DtrEnable = false, RtsEnable = false
        };
        port.Open();
        var watch = Stopwatch.StartNew();
        long nextProbe = 0;
        bool recognized = false;
        var buffer = new StringBuilder();
        // Opening some USB adapters resets ESP32; allow the firmware to boot.
        while (!_stop.IsCancellationRequested && watch.ElapsedMilliseconds < 3500)
        {
            if (watch.ElapsedMilliseconds >= nextProbe) { port.WriteLine("V11?"); nextProbe = watch.ElapsedMilliseconds + 700; }
            if (ReadLine(port, buffer) == "V11ESP32\t1") { recognized = true; break; }
        }
        if (!recognized) return;
        var lastSeen = watch.ElapsedMilliseconds;
        long heartbeat = 0;
        while (!_stop.IsCancellationRequested)
        {
            if (watch.ElapsedMilliseconds >= heartbeat)
            {
                port.WriteLine($"P\t{BluetoothSettings.Current.Power}");
                heartbeat = watch.ElapsedMilliseconds + 1000;
            }
            string? line = ReadLine(port, buffer);
            if (line is not null)
            {
                if (line.StartsWith("P\t") || line == "V11ESP32\t1") lastSeen = watch.ElapsedMilliseconds;
                var fields = line.Split('\t');
                if (fields.Length == 4 && fields[0] == "E" && long.TryParse(fields[1], out long id)
                    && fields[2].Length is > 0 and <= 512 && fields[3].Length is > 0 and <= 128)
                {
                    lastSeen = watch.ElapsedMilliseconds;
                    string request = JsonSerializer.Serialize(new { id, token = fields[2], device = fields[3] });
                    int code = 4;
                    try {
                        using var answer = JsonDocument.Parse(_relay(request));
                        if (answer.RootElement.TryGetProperty("code", out var c) && c.TryGetInt32(out var v) && v is 0 or 2 or 4 or 5) code = v;
                    } catch { }
                    port.WriteLine($"R\t{id}\t{code}");
                }
            }
            if (watch.ElapsedMilliseconds - lastSeen > 5000) return;
        }
    }
    private static string? ReadLine(SerialPort port, StringBuilder buffer)
    {
        try
        {
            while (true)
            {
                int c = port.ReadChar();
                if (c == '\n') { string result = buffer.ToString().TrimEnd('\r'); buffer.Clear(); return result; }
                if (buffer.Length >= 2048) throw new System.IO.IOException("Serial frame too large");
                buffer.Append((char)c);
            }
        }
        catch (TimeoutException) { return null; }
    }
}
