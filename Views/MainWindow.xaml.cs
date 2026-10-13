using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using Visits11.ViewModels;

namespace Visits11.Views;

public partial class MainWindow : Window
{
    private static readonly int[] AllowedPower = { -12, -9, -6, -3, 0, 3, 6, 9 };

    public MainWindow(MainViewModel viewModel)
    {
        InitializeComponent();
        DataContext = viewModel;
    }

    /// <summary>Перетаскивание окна за топбар (двойной клик разворачивает сам).</summary>
    private void Topbar_OnMouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (e.ClickCount == 1 && !IsInsideInput(e.OriginalSource as DependencyObject))
        {
            try
            {
                DragMove();
            }
            catch
            {
                // окно может быть неактивно — игнорируем
            }
        }
    }

    /// <summary>Поле мощности — не повод тащить окно за собой.</summary>
    private static bool IsInsideInput(DependencyObject? source)
    {
        while (source is not null)
        {
            if (source is TextBox) return true;
            source = source is Visual visual ? VisualTreeHelper.GetParent(visual) : null;
        }
        return false;
    }

    /// <summary>Ввод мощности передатчика коробочки: Enter — применить.</summary>
    private void PowerInput_OnKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key != Key.Enter)
        {
            return;
        }

        e.Handled = true;
        ApplyPower();
    }

    /// <summary>Значение уходит в коробочку командой «POWER:<дБм>», ответ показываем уведомлением.</summary>
    private void ApplyPower()
    {
        var text = PowerInput.Text.Trim();
        if (!int.TryParse(text, out var dbm) || Array.IndexOf(AllowedPower, dbm) < 0)
        {
            App.Toasts.Error("Мощность BLE", "введите: -12, -9, -6, -3, 0, 3, 6 или 9 дБм");
            return;
        }

        PowerInput.IsEnabled = false;
        Task.Run(() =>
        {
            var applied = App.Box.SetPower(dbm);
            Dispatcher.Invoke(() =>
            {
                PowerInput.IsEnabled = true;
                if (applied is null)
                {
                    App.Toasts.Error("Мощность BLE", "коробочка не ответила — проверь USB-кабель");
                    return;
                }

                PowerInput.Text = applied.Value.ToString();
                App.Toasts.Success("Мощность BLE", $"коробочка: {applied.Value} дБм");
            });
        });
    }

    private void MinimizeButton_Click(object sender, RoutedEventArgs e)
    {
        WindowState = WindowState.Minimized;
    }

    private void MaximizeButton_Click(object sender, RoutedEventArgs e)
    {
        WindowState = WindowState == WindowState.Maximized
            ? WindowState.Normal
            : WindowState.Maximized;
    }

    private void CloseButton_Click(object sender, RoutedEventArgs e)
    {
        Close();
    }
}
