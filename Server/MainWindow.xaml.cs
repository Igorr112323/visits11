using System.ComponentModel;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.CompilerServices;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;

namespace Visits11.Server;

public partial class MainWindow : Window, INotifyPropertyChanged
{
    private readonly ServerHost _host;
    private readonly DispatcherTimer _timer;
    private string _statusText = "Запуск сервера";
    private string _detailText = "Подготавливаем локальную базу и сетевой интерфейс.";
    private int _pendingCount;
    private bool _isOnline;

    public MainWindow()
    {
        InitializeComponent();
        var localData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        var directory = Path.Combine(localData, "KubGAU", "Visits11", "Server");
        var args = Environment.GetCommandLineArgs();
        var dataArg = args.FirstOrDefault(value => value.StartsWith("--data-dir=", StringComparison.OrdinalIgnoreCase));
        var portArg = args.FirstOrDefault(value => value.StartsWith("--port=", StringComparison.OrdinalIgnoreCase));
        if (dataArg is not null) directory = dataArg[11..];
        var port = portArg is not null && int.TryParse(portArg[7..], out var parsedPort) ? parsedPort : 8091;
        _host = new ServerHost(directory, port);
        DataContext = this;
        _timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(2) };
        _timer.Tick += (_, _) => RefreshPendingCount();
        Loaded += OnLoaded;
    }

    public event PropertyChangedEventHandler? PropertyChanged;

    public string StatusText
    {
        get => _statusText;
        private set => Set(ref _statusText, value);
    }

    public string DetailText
    {
        get => _detailText;
        private set => Set(ref _detailText, value);
    }

    public int PendingCount
    {
        get => _pendingCount;
        private set => Set(ref _pendingCount, value);
    }

    public bool IsOnline
    {
        get => _isOnline;
        private set
        {
            if (Set(ref _isOnline, value)) OnPropertyChanged(nameof(StatusBrush));
        }
    }

    public Brush StatusBrush => new SolidColorBrush(IsOnline ? Color.FromRgb(56, 200, 120) : Color.FromRgb(222, 87, 87));
    public string LocalAddress => $"http://127.0.0.1:{_host.Port}";
    public string LanAddress => $"http://{GetLanAddress()}:{_host.Port}";
    public string SyncKey => _host.SyncKey;
    public string DatabasePath => _host.DatabasePath;

    private async void OnLoaded(object sender, RoutedEventArgs e)
    {
        try
        {
            await _host.StartAsync();
            IsOnline = true;
            StatusText = "Сервер работает";
            DetailText = "Сервер принимает отметки и ждёт подключения преподавательского журнала.";
            OnPropertyChanged(nameof(SyncKey));
            OnPropertyChanged(nameof(DatabasePath));
            _timer.Start();
            RefreshPendingCount();
        }
        catch (Exception exception)
        {
            IsOnline = false;
            StatusText = "Не удалось запустить сервер";
            DetailText = exception.Message;
        }
    }

    private void RefreshPendingCount()
    {
        try
        {
            PendingCount = _host.GetPendingCount();
        }
        catch
        {
            PendingCount = 0;
        }
    }

    private void Copy_OnClick(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement { Tag: string value } && !string.IsNullOrWhiteSpace(value))
            Clipboard.SetText(value);
    }

    private void CopyKey_OnClick(object sender, RoutedEventArgs e)
    {
        if (!string.IsNullOrWhiteSpace(SyncKey)) Clipboard.SetText(SyncKey);
    }

    private void TitleBar_OnMouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (e.ButtonState == MouseButtonState.Pressed) DragMove();
    }

    private void Minimize_OnClick(object sender, RoutedEventArgs e) => WindowState = WindowState.Minimized;

    private async void Close_OnClick(object sender, RoutedEventArgs e)
    {
        _timer.Stop();
        await _host.StopAsync();
        Close();
    }

    protected override async void OnClosed(EventArgs e)
    {
        _timer.Stop();
        await _host.DisposeAsync();
        base.OnClosed(e);
    }

    private static string GetLanAddress()
    {
        foreach (var network in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (network.OperationalStatus != OperationalStatus.Up || network.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
            foreach (var item in network.GetIPProperties().UnicastAddresses)
            {
                if (item.Address.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(item.Address))
                    return item.Address.ToString();
            }
        }
        return "127.0.0.1";
    }

    private bool Set<T>(ref T field, T value, [CallerMemberName] string? propertyName = null)
    {
        if (EqualityComparer<T>.Default.Equals(field, value)) return false;
        field = value;
        OnPropertyChanged(propertyName);
        return true;
    }

    private void OnPropertyChanged([CallerMemberName] string? propertyName = null)
        => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));
}
