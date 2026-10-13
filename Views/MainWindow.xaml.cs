using System.Windows;
using System.Windows.Input;
using Visits11.ViewModels;

namespace Visits11.Views;

public partial class MainWindow : Window
{
    public MainWindow(MainViewModel viewModel)
    {
        InitializeComponent();
        DataContext = viewModel;
    }

    /// <summary>Перетаскивание окна за топбар (двойной клик разворачивает сам).</summary>
    private void Topbar_OnMouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (e.OriginalSource is System.Windows.DependencyObject source)
        {
            for (var node = source; node is not null; node = System.Windows.Media.VisualTreeHelper.GetParent(node))
                if (node is System.Windows.Controls.Primitives.Selector || node is System.Windows.Controls.Primitives.ButtonBase) return;
        }
        if (e.ClickCount == 1)
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
