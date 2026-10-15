using System.ComponentModel;
using System.IO;

namespace Visits11.Services;

/// <summary>Same four hardware power levels for both terminals. Not a distance in cm.</summary>
public sealed class BluetoothSettings : INotifyPropertyChanged
{
    public static BluetoothSettings Current { get; } = new();
    private readonly string _path = Path.Combine(AppDataStorage.DirectoryPath, "bluetooth-power.txt");
    private int _power;
    public string[] Levels { get; } = { "Минимум", "Низкая", "Средняя", "Высокая" };
    public event PropertyChangedEventHandler? PropertyChanged;
    public int Power
    {
        get => _power;
        set
        {
            if (value is < 0 or > 3 || _power == value) return;
            _power = value;
            try { File.WriteAllText(_path, value.ToString()); } catch { /* session setting still applies */ }
            PropertyChanged?.Invoke(this, new(nameof(Power)));
        }
    }
    private BluetoothSettings()
    {
        try { if (int.TryParse(File.ReadAllText(_path), out var p) && p is >= 0 and <= 3) _power = p; }
        catch { }
    }
}
