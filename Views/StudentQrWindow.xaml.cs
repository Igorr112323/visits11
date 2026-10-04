using System.Windows;
using System.Windows.Media.Imaging;

namespace Visits11.Views;

public sealed record StudentQrWindowData(string FullName, BitmapSource QrImage);

public partial class StudentQrWindow : Window
{
    public StudentQrWindow(string fullName, BitmapSource qrImage)
    {
        InitializeComponent();
        DataContext = new StudentQrWindowData(fullName, qrImage);
    }

    private void Close_OnClick(object sender, RoutedEventArgs e) => Close();
}
