using System.IO;
using System.Windows;

namespace Visits11.Server;

public partial class App : Application
{
    static App()
    {
        AppDomain.CurrentDomain.UnhandledException += (_, args) => Log(args.ExceptionObject as Exception);
    }

    protected override void OnStartup(StartupEventArgs e)
    {
        DispatcherUnhandledException += (_, args) => Log(args.Exception);
        base.OnStartup(e);
    }

    private static void Log(Exception? exception)
    {
        if (exception is null) return;
        try
        {
            var args = Environment.GetCommandLineArgs();
            var dataArg = args.FirstOrDefault(value => value.StartsWith("--data-dir=", StringComparison.OrdinalIgnoreCase));
            var directory = dataArg is null
                ? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "KubGAU", "Visits11", "Server")
                : dataArg[11..];
            Directory.CreateDirectory(directory);
            File.AppendAllText(Path.Combine(directory, "server-error.log"), exception + Environment.NewLine);
        }
        catch
        {
        }
    }
}
