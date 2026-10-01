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

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        var appDir = GetAppDirectory();

        Db = new DatabaseService(Path.Combine(appDir, "attendance.db"));
        Db.Initialize();

        Theme = new ThemeService(Path.Combine(appDir, "settings.json"));
        Theme.LoadAndApply();

        Toasts = new ToastService();

        DispatcherUnhandledException += (_, args) =>
        {
            MessageBox.Show(args.Exception.Message, "Ошибка", MessageBoxButton.OK, MessageBoxImage.Error);
            args.Handled = true;
        };

        var viewModel = new MainViewModel(Db, Toasts, Theme, new AuthService(), new WordService());
        var window = new MainWindow(viewModel);
        window.Show();
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
            if (string.Equals(name, "Visits11", StringComparison.OrdinalIgnoreCase))
            {
                return Path.GetDirectoryName(processPath)!;
            }
        }
        return AppContext.BaseDirectory;
    }
}
