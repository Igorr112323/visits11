using Visits11.Services;

namespace Visits11.ViewModels;

/// <summary>
/// Корневая модель: вкладки, переключение темы, модальные окна, тосты.
/// </summary>
public sealed class MainViewModel : ObservableObject
{
    private readonly ThemeService _theme;

    public MainViewModel(
        DatabaseService database,
        ToastService toasts,
        ThemeService theme,
        AuthService auth,
        WordService word)
    {
        _theme = theme;
        Toasts = toasts;

        Lesson = new LessonViewModel(database, toasts);
        Students = new StudentsViewModel(database, toasts, auth, word, this);
        Journal = new JournalViewModel(database, toasts, word);

        ToggleThemeCommand = new RelayCommand(_ => _theme.Toggle());
        _theme.ThemeChanged += () => OnPropertyChanged(nameof(IsDarkTheme));

        _currentTab = Lesson;
    }

    public LessonViewModel Lesson { get; }
    public StudentsViewModel Students { get; }
    public JournalViewModel Journal { get; }

    public ToastService Toasts { get; }

    public RelayCommand ToggleThemeCommand { get; }

    public bool IsDarkTheme => _theme.IsDark;

    // ------------------------------------------------------------------ вкладкам

    private object _currentTab;
    public object CurrentTab
    {
        get => _currentTab;
        private set
        {
            if (Set(ref _currentTab, value))
            {
                OnPropertyChanged(nameof(TabLessons));
                OnPropertyChanged(nameof(TabStudents));
                OnPropertyChanged(nameof(TabJournal));
                (_currentTab as ITabViewModel)?.OnActivated();
            }
        }
    }

    public bool TabLessons
    {
        get => ReferenceEquals(CurrentTab, Lesson);
        set { if (value) CurrentTab = Lesson; }
    }

    public bool TabStudents
    {
        get => ReferenceEquals(CurrentTab, Students);
        set { if (value) CurrentTab = Students; }
    }

    public bool TabJournal
    {
        get => ReferenceEquals(CurrentTab, Journal);
        set { if (value) CurrentTab = Journal; }
    }

    // ------------------------------------------------------------- модальные

    private ModalViewModel? _currentModal;
    public ModalViewModel? CurrentModal
    {
        get => _currentModal;
        private set => Set(ref _currentModal, value);
    }

    public void ShowModal(ModalViewModel modal)
    {
        modal.CloseRequested += CloseModal;
        CurrentModal = modal;
    }

    private void CloseModal()
    {
        if (CurrentModal is { } modal)
        {
            modal.CloseRequested -= CloseModal;
        }
        CurrentModal = null;
    }
}
