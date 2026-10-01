using System.Windows;

namespace Visits11.Controls;

/// <summary>Вспомогательные attached-свойства для шаблонов.</summary>
public static class Widget
{
    public static readonly DependencyProperty RadiusProperty =
        DependencyProperty.RegisterAttached(
            "Radius",
            typeof(CornerRadius),
            typeof(Widget),
            new PropertyMetadata(new CornerRadius(12)));

    public static CornerRadius GetRadius(DependencyObject obj)
        => (CornerRadius)obj.GetValue(RadiusProperty);

    public static void SetRadius(DependencyObject obj, CornerRadius value)
        => obj.SetValue(RadiusProperty, value);
}
