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

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        var appDir = GetAppDirectory();

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
        Phone.Dispose();
        base.OnExit(e);
    }

    /// <summary>
    /// Каталог приложения. Для single-file публикации — папка с .exe.
    /// </summary>
    private static string GetAppDirectory()
    {
        var processPath = Environment.ProcessPath;
        if (!string.IsNullOrEmpty(processPath))
        {
            var name = Path.GetFileNameWithoutExtension(processPath);
            if (!string.Equals(name, "dotnet", StringComparison.OrdinalIgnoreCase))
            {
                var dir = Path.GetDirectoryName(processPath);
                if (!string.IsNullOrEmpty(dir))
                {
                    return dir;
                }
            }
        }
        return AppContext.BaseDirectory;
    }
}
