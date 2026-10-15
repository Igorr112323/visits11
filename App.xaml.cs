using System.IO;
using System.Windows;
using Visits11.Services;
using Visits11.ViewModels;
using Visits11.Views;

namespace Visits11;

public partial class App : Application
{
    public static DatabaseService Db { get; private set; } = null!;
    public static ThemeService Theme { get; private set; } = null!;
    public static ToastService Toasts { get; private set; } = null!;
    public static PhoneLink Phone { get; private set; } = null!;

    private System.Threading.Mutex? _instance;
    private bool _ownsInstance;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        var appDir = AppDataStorage.DirectoryPath;
        // Serialize migration and prevent two copies of this terminal journal sharing one database.
        var key = Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(
            System.Text.Encoding.UTF8.GetBytes(appDir.ToUpperInvariant())));
        _instance = new System.Threading.Mutex(false, @"Local\Visits11-" + key);
        try { _ownsInstance = _instance.WaitOne(0); }
        catch (System.Threading.AbandonedMutexException) { _ownsInstance = true; }
        if (!_ownsInstance)
        {
            MessageBox.Show("Этот журнал уже открыт. Используйте открытое окно.", "Журнал КубГАУ");
            Shutdown();
            return;
        }
        try
        {
            AppDataStorage.Prepare(AppContext.BaseDirectory, appDir);
        }
        catch (Exception error)
        {
            MessageBox.Show("Не удалось подготовить данные журнала. Исходная база сохранена.\n" + error.Message,
                "Журнал КубГАУ", MessageBoxButton.OK, MessageBoxImage.Error);
            Shutdown();
            return; // Never silently replace a failed migration with an empty journal.
        }

        Db = new DatabaseService(Path.Combine(appDir, "attendance.db"));
        Db.Initialize();

        Theme = new ThemeService(Path.Combine(appDir, "settings.json"));
        Theme.LoadAndApply();

        Toasts = new ToastService();

        // связь с телефоном преподавателя — только по USB-кабелю, Wi-Fi не используется
        Phone = new PhoneLink();
        Phone.Start();

        DispatcherUnhandledException += (_, args) =>
        {
            MessageBox.Show(args.Exception.Message, "Ошибка", MessageBoxButton.OK, MessageBoxImage.Error);
            args.Handled = true;
        };

        var viewModel = new MainViewModel(Db, Toasts, Theme, new WordService(),
            new QrExportService(), Phone);
        var window = new MainWindow(viewModel);
        window.Show();
    }

    protected override void OnExit(ExitEventArgs e)
    {
        Phone?.Dispose();
        if (_ownsInstance) _instance?.ReleaseMutex();
        _instance?.Dispose();
        base.OnExit(e);
    }

}
