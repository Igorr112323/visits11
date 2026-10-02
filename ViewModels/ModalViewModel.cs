namespace Visits11.ViewModels;

/// <summary>
/// Модель модального окна. OnConfirm возвращает false, чтобы окно осталось открытым
/// (например, при ошибке валидации).
/// </summary>
public sealed class ModalViewModel : ObservableObject
{
    public string Title { get; init; } = string.Empty;
    public string? Message { get; init; }
    public bool ShowInput { get; init; }
    public string InputLabel { get; init; } = string.Empty;
    public string Placeholder { get; init; } = string.Empty;
    public string ConfirmText { get; init; } = "ОК";

    private string _inputText = string.Empty;
    public string InputText
    {
        get => _inputText;
        set => Set(ref _inputText, value);
    }

    public Func<ModalViewModel, bool>? OnConfirm { get; init; }

    internal event Action? CloseRequested;

    public RelayCommand ConfirmCommand => new(_ =>
    {
        if (OnConfirm?.Invoke(this) != false)
        {
            CloseRequested?.Invoke();
        }
    });

    public RelayCommand CancelCommand => new(_ => CloseRequested?.Invoke());
}
