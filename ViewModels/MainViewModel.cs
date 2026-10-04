using System.Windows;
using Visits11.Services;

namespace Visits11.ViewModels;

/// <summary>
/// Корневая модель: вкладки, переключение темы, модальные окна, тосты.
/// </summary>
public sealed class MainViewModel : ObservableObject
{
    private readonly ThemeService _theme;
    private readonly RemoteSyncService _remoteSync;

    public MainViewModel(
        DatabaseService database,
        ToastService toasts,
        ThemeService theme,
        AuthService auth,
        WordService word,
        PhoneServer server,
        CameraLink camera,
        RemoteSyncService remoteSync)
    {
        _theme = theme;
        _remoteSync = remoteSync;
        Toasts = toasts;

        Lesson = new LessonViewModel(database, toasts, server, camera);
        Students = new StudentsViewModel(database, toasts, auth, word, this, remoteSync);
        _remoteSync.ProcessMark = mark =>
        {
            var processed = false;
            Application.Current.Dispatcher.Invoke(() => processed = Lesson.ApplyRemoteMark(mark));
            return processed;
        };
        Journal = new JournalViewModel(database, toasts, word);

        ToggleThemeCommand = new RelayCommand(_ => _theme.Toggle());
        ConfigureServerCommand = new RelayCommand(_ => ConfigureServer());
        _remoteSync.StatusChanged += () => Application.Current.Dispatcher.BeginInvoke(new Action(() => OnPropertyChanged(nameof(ServerStatusText))));
        _theme.ThemeChanged += () => OnPropertyChanged(nameof(IsDarkTheme));

        _currentTab = Lesson;
    }

    public LessonViewModel Lesson { get; }
    public StudentsViewModel Students { get; }
    public JournalViewModel Journal { get; }

    public ToastService Toasts { get; }

    public RelayCommand ToggleThemeCommand { get; }
    public RelayCommand ConfigureServerCommand { get; }

    public string ServerStatusText => _remoteSync.StatusLabel;
    public bool IsDarkTheme => _theme.IsDark;

    private void ConfigureServer()
    {
        ShowModal(new ModalViewModel
        {
            Title = "Подключение к серверу",
            Message = "Для локального теста укажите LAN-адрес из окна серверного EXE. Преподавательский журнал на этом ПК тоже сможет подключиться к нему.",
            ShowInput = true,
            InputLabel = "АДРЕС СЕРВЕРА",
            Placeholder = "http://192.168.1.10:8091",
            InputText = _remoteSync.BaseUrl,
            ShowInput2 = true,
            InputLabel2 = "КЛЮЧ ПРЕПОДАВАТЕЛЯ",
            Placeholder2 = "Скопируйте в серверном EXE",
            InputText2 = _remoteSync.SyncKey,
            ConfirmText = "Сохранить",
            OnConfirm = modal =>
            {
                try
                {
                    _remoteSync.Configure(modal.InputText, modal.InputText2);
                    Toasts.Success("Сервер настроен", "Подключение проверяется в фоне.");
                    return true;
                }
                catch (Exception exception)
                {
                    Toasts.Error("Не удалось сохранить настройки", exception.Message);
                    return false;
                }
            },
        });
    }

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
