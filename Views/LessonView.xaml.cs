using System.Windows;
using System.Windows.Controls;
using System.Windows.Media.Animation;
using System.Windows.Media;

namespace Visits11.Views;

public partial class LessonView : UserControl
{
    public LessonView()
    {
        InitializeComponent();
        Loaded += (_, _) => StartScanLoop();
        ScanFrame.SizeChanged += (_, _) => StartScanLoop();
    }

    /// <summary>Цикл линии сканирования: 2 секунды сверху вниз.</summary>
    private void StartScanLoop()
    {
        if (ScanFrame.ActualHeight <= 12) return;
        if (ScanLine.RenderTransform is not TranslateTransform translate) return;

        var animation = new DoubleAnimation
        {
            From = 2,
            To = ScanFrame.ActualHeight - 4,
            Duration = TimeSpan.FromSeconds(2),
            RepeatBehavior = RepeatBehavior.Forever,
        };
        translate.BeginAnimation(TranslateTransform.YProperty, animation);
    }
}
