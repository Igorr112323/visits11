using System.IO;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using QRCoder;
using Visits11.Services;

namespace Visits11.ViewModels;

/// <summary>
/// Вкладка «QR для скачивания»: постоянный QR-код адреса, по которому iPhone
/// открывает мастер установки приложения студента.
/// </summary>
public sealed class DownloadViewModel : ObservableObject, ITabViewModel
{
    private readonly PhoneServer _server;
    private readonly ToastService _toasts;

    private string? _qrUrl;
    private ImageSource? _qr;

    public DownloadViewModel(PhoneServer server, ToastService toasts)
    {
        _server = server;
        _toasts = toasts;
        CopyUrlCommand = new RelayCommand(_ => CopyUrl());
    }

    /// <summary>Адрес мастера установки; читается заново при каждом открытии вкладки (IP ПК мог смениться).</summary>
    public string Url => _server.Url + "setup";

    /// <summary>QR-код адреса.</summary>
    public ImageSource? Qr
    {
        get
        {
            var url = Url;
            if (_qr is null || _qrUrl != url)
            {
                try
                {
                    var data = new QRCodeGenerator().CreateQrCode(url, QRCodeGenerator.ECCLevel.M);
                    _qr = Decode(new PngByteQRCode(data).GetGraphic(10));
                    _qrUrl = url;
                }
                catch
                {
                    _qr = null;
                }
            }
            return _qr;
        }
    }

    public RelayCommand CopyUrlCommand { get; }

    public void OnActivated()
    {
        OnPropertyChanged(nameof(Url));
        OnPropertyChanged(nameof(Qr));
    }

    private void CopyUrl()
    {
        try
        {
            Clipboard.SetText(Url);
            _toasts.Info("Ссылка скопирована", Url);
        }
        catch
        {
            _toasts.Error("Не удалось скопировать", Url);
        }
    }

    private static ImageSource Decode(byte[] png)
    {
        var image = new BitmapImage();
        using (var stream = new MemoryStream(png))
        {
            image.BeginInit();
            image.CacheOption = BitmapCacheOption.OnLoad;
            image.StreamSource = stream;
            image.EndInit();
        }
        image.Freeze();
        return image;
    }
}
