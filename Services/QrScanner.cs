using System.Windows.Media;
using System.Windows.Media.Imaging;
using ZXing;
using ZXing.Common;

namespace Visits11.Services;

/// <summary>
/// Распознавание QR-кода из кадра видео (камера преподавателя).
/// QR студента содержит зашифрованный блок — наружу не отдаётся ничего.
/// </summary>
public static class QrScanner
{
    private static readonly BarcodeReaderGeneric Reader = new()
    {
        AutoRotate = true,
        Options = new DecodingOptions
        {
            TryHarder = true,
            PossibleFormats = new[] { BarcodeFormat.QR_CODE },
        },
    };

    /// <summary>Содержимое QR (сырые байты) или null, если кода в кадре нет.</summary>
    public static byte[]? Scan(BitmapSource frame)
    {
        try
        {
            var gray = new FormatConvertedBitmap(frame, PixelFormats.Gray8, null, 0);
            gray.Freeze();
            var width = gray.PixelWidth;
            var height = gray.PixelHeight;
            var pixels = new byte[width * height];
            gray.CopyPixels(pixels, width, 0);
            var result = Reader.Decode(pixels, width, height, RGBLuminanceSource.BitmapFormat.Gray8);
            if (result is null) return null;
            if (result.Text is { } text && text.StartsWith("V11B", StringComparison.Ordinal))
                return System.Text.Encoding.ASCII.GetBytes(text);
            return result.RawBytes;
        }
        catch
        {
            return null;
        }
    }
}
