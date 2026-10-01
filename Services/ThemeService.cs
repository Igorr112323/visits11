using System.IO;
using System.Text.Json;
using System.Windows;

namespace Visits11.Services;

/// <summary>
/// Переключение светлой/тёмной темы. Выбор сохраняется в settings.json рядом с exe.
/// </summary>
public sealed class ThemeService
{
    public const string Light = "light";
    public const string Dark = "dark";

    private sealed class SettingsDto
    {
        public string Theme { get; set; } = Light;
    }

    private readonly string _settingsPath;

    public string Current { get; private set; } = Light;
    public bool IsDark => Current == Dark;

    /// <summary>Вызывается после смены темы.</summary>
    public event Action? ThemeChanged;

    public ThemeService(string settingsPath)
    {
        _settingsPath = settingsPath;
    }

    public void LoadAndApply()
    {
        var theme = Light;
        try
        {
            if (File.Exists(_settingsPath))
            {
                var settings = JsonSerializer.Deserialize<SettingsDto>(File.ReadAllText(_settingsPath));
                if (settings is { Theme: Light or Dark }) theme = settings.Theme;
            }
        }
        catch
        {
            // повреждённый settings.json — используем светлую тему
        }
        Apply(theme, save: false);
    }

    public void Toggle() => Apply(IsDark ? Light : Dark, save: true);

    private void Apply(string theme, bool save)
    {
        Current = theme;

        var dictionaries = Application.Current.Resources.MergedDictionaries;
        var index = -1;
        for (var i = 0; i < dictionaries.Count; i++)
        {
            if (dictionaries[i].Source is { } source && source.OriginalString.Contains("Theme."))
            {
                index = i;
                break;
            }
        }

        if (index >= 0)
        {
            var file = IsDark ? "Theme.Dark" : "Theme.Light";
            dictionaries[index] = new ResourceDictionary
            {
                Source = new Uri($"pack://application:,,,/Styles/{file}.xaml"),
            };
        }

        if (save) Save();
        ThemeChanged?.Invoke();
    }

    private void Save()
    {
        try
        {
            File.WriteAllText(_settingsPath, JsonSerializer.Serialize(new SettingsDto { Theme = Current }));
        }
        catch
        {
            // нет прав на запись — тема просто не сохранится
        }
    }
}
