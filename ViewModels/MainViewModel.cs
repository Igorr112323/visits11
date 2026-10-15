using Visits11.Services;

namespace Visits11.ViewModels;

/// <summary>
/// Корневая модель: вкладки, переключение темы, модальные окна, тосты.
/// </summary>
public sealed class MainViewModel : ObservableObject
{
    private readonly ThemeService _theme;

    private readonly PhoneLink _phone;

    public MainViewModel(
        DatabaseService database,
        ToastService toasts,
        ThemeService theme,
        WordService word,
        QrExportService qrExport,
        PhoneLink phone)
    {
        _theme = theme;
        _phone = phone;
        Toasts = toasts;

        Lesson = new LessonViewModel(database, toasts, phone);
        Students = new StudentsViewModel(database, toasts, word, qrExport, this);
        Journal = new JournalViewModel(database, toasts, word);

        ToggleThemeCommand = new RelayCommand(_ => _theme.Toggle());
        _theme.ThemeChanged += () => OnPropertyChanged(nameof(IsDarkTheme));

        // Индикатор связи с платой: только в режиме «плата по USB»
        ShowBoardIndicator = phone.UsesBoard;
        if (ShowBoardIndicator)
        {
            phone.BoardConnected += state =>
            {
                var dispatcher = System.Windows.Application.Current?.Dispatcher;
                if (dispatcher is null || dispatcher.CheckAccess()) BoardConnected = state;
                else dispatcher.BeginInvoke(() => BoardConnected = state);
            };
        }

        _currentTab = Lesson;
    }

    /// <summary>Показывать ли кружок состояния платы (нет в режиме «телефон преподавателя»).</summary>
    public bool ShowBoardIndicator { get; }

    private bool _boardConnected;
    /// <summary>Зелёный кружок — плата подключена по USB и отвечает; красный — нет.</summary>
    public bool BoardConnected
    {
        get => _boardConnected;
        private set => Set(ref _boardConnected, value);
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
