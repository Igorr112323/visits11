using System.Collections.ObjectModel;
using System.Windows.Threading;

namespace Visits11.Services;

public enum ToastType
{
    Success,
    Error,
    Info,
}

public sealed class Toast
{
    public ToastType Type { get; init; }
    public string Title { get; init; } = string.Empty;
    public string Message { get; init; } = string.Empty;
}

/// <summary>
/// Уведомления в правом нижнем углу. Живут 3 секунды.
/// </summary>
public sealed class ToastService
{
    private const int MaxToasts = 5;
    private static readonly TimeSpan Lifetime = TimeSpan.FromSeconds(3.2);

    public ObservableCollection<Toast> Toasts { get; } = new();

    public void Success(string title, string message = "") => Add(ToastType.Success, title, message);
    public void Error(string title, string message = "") => Add(ToastType.Error, title, message);
    public void Info(string title, string message = "") => Add(ToastType.Info, title, message);

    private void Add(ToastType type, string title, string message)
    {
        var toast = new Toast { Type = type, Title = title, Message = message };

        if (Toasts.Count >= MaxToasts)
        {
            Toasts.RemoveAt(0);
        }
        Toasts.Add(toast);

        var timer = new DispatcherTimer { Interval = Lifetime };
        timer.Tick += (_, _) =>
        {
            timer.Stop();
            Toasts.Remove(toast);
        };
        timer.Start();
    }
}
