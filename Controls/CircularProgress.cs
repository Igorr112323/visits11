using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Shapes;

namespace Visits11.Controls;

/// <summary>
/// Круговой индикатор прогресса. Percent — доля (0..1).
/// </summary>
public sealed class CircularProgress : Control
{
    static CircularProgress()
    {
        DefaultStyleKeyProperty.OverrideMetadata(typeof(CircularProgress),
            new FrameworkPropertyMetadata(typeof(CircularProgress)));
    }

    private Path? _bar;
    private bool _animating;

    public CircularProgress()
    {
        SizeChanged += (_, _) => UpdateArc();
    }

    public override void OnApplyTemplate()
    {
        base.OnApplyTemplate();
        _bar = GetTemplateChild("PART_Bar") as Path;
        UpdateArc();
    }

    public double Percent
    {
        get => (double)GetValue(PercentProperty);
        set => SetValue(PercentProperty, value);
    }

    public static readonly DependencyProperty PercentProperty =
        DependencyProperty.Register(
            nameof(Percent),
            typeof(double),
            typeof(CircularProgress),
            new PropertyMetadata(0.0, OnPercentChanged));

    public Brush BarBrush
    {
        get => (Brush)GetValue(BarBrushProperty);
        set => SetValue(BarBrushProperty, value);
    }

    public static readonly DependencyProperty BarBrushProperty =
        DependencyProperty.Register(
            nameof(BarBrush),
            typeof(Brush),
            typeof(CircularProgress),
            new PropertyMetadata(Brushes.Transparent));

    public Brush TrackBrush
    {
        get => (Brush)GetValue(TrackBrushProperty);
        set => SetValue(TrackBrushProperty, value);
    }

    public static readonly DependencyProperty TrackBrushProperty =
        DependencyProperty.Register(
            nameof(TrackBrush),
            typeof(Brush),
            typeof(CircularProgress),
            new PropertyMetadata(Brushes.Transparent));

    public double BarThickness
    {
        get => (double)GetValue(BarThicknessProperty);
        set => SetValue(BarThicknessProperty, value);
    }

    public static readonly DependencyProperty BarThicknessProperty =
        DependencyProperty.Register(
            nameof(BarThickness),
            typeof(double),
            typeof(CircularProgress),
            new PropertyMetadata(9.0));

    private static void OnPercentChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        var control = (CircularProgress)d;

        if (control._animating)
        {
            control.UpdateArc();
            return;
        }

        var target = Math.Clamp((double)e.NewValue, 0.0, 1.0);
        control._animating = true;

        var animation = new DoubleAnimation
        {
            From = control.Percent,
            To = target,
            Duration = TimeSpan.FromMilliseconds(400),
            EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut },
        };
        animation.Completed += (_, _) => control._animating = false;
        control.BeginAnimation(PercentProperty, animation, HandoffBehavior.SnapshotAndReplace);
    }

    private void UpdateArc()
    {
        if (_bar is null || ActualWidth <= 0 || ActualHeight <= 0) return;

        var percent = Math.Clamp(Percent, 0.0, 1.0);
        if (percent <= 0.001)
        {
            _bar.Data = Geometry.Empty;
            return;
        }

        var size = Math.Min(RenderSize.Width, RenderSize.Height);
        var radius = Math.Max((size - BarThickness) / 2.0, 0.1);
        var center = size / 2.0;

        if (percent >= 0.999)
        {
            _bar.Data = new EllipseGeometry(new Point(center, center), radius, radius);
            return;
        }

        const double startAngle = -90.0;
        var endAngle = startAngle + 360.0 * percent;

        var start = PointOnCircle(center, radius, startAngle);
        var end = PointOnCircle(center, radius, endAngle);

        var geometry = new StreamGeometry();
        var context = geometry.Open();
        context.BeginFigure(start, isFilled: false, isClosed: false);
        context.ArcTo(
            end,
            new Size(radius, radius),
            rotationAngle: 0,
            isLargeArc: 360.0 * percent > 180.0,
            SweepDirection.Clockwise,
            isStroked: true,
            isSmoothJoin: false);
        context.Close();
        geometry.Freeze();

        _bar.Data = geometry;
    }

    private static Point PointOnCircle(double center, double radius, double angleDeg)
    {
        var radians = angleDeg * Math.PI / 180.0;
        return new Point(center + radius * Math.Cos(radians), center + radius * Math.Sin(radians));
    }
}
