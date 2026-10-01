using System.Collections.ObjectModel;
using System.IO;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using QRCoder;
using Visits11.Models;
using Visits11.Services;

namespace Visits11.ViewModels;

public sealed class StudentRow : ObservableObject
{
    public int StudentId { get; init; }
    public int Number { get; init; }
    public string FullName { get; init; } = string.Empty;
    public string? PhoneId { get; set; }

    private bool _isPresent;
    public bool IsPresent
    {
        get => _isPresent;
        set
        {
            if (Set(ref _isPresent, value))
            {
                OnPropertyChanged(nameof(StatusText));
            }
        }
    }

    public string StatusText => IsPresent ? "Есть" : "Нет";

    /// <summary>Момент отметки (для записи в БД).</summary>
    public string? MarkedAt { get; set; }
}

/// <summary>
/// Вкладка «Занятия»: выбор группы, перекличка по QR-коду (отметка приходит с телефона студента),
/// видеопоток с телефона преподавателя, статистика.
/// </summary>
public sealed class LessonViewModel : ObservableObject, ITabViewModel
{
    private const string StateIdle = "Idle";
    private const string StateActive = "Active";
    private const string StateFinished = "Finished";

    private readonly DatabaseService _database;
    private readonly ToastService _toasts;
    private readonly PhoneServer _server;

    private DispatcherTimer? _resetTimer;
    private readonly DispatcherTimer _streamWatchdog;
    private string _lessonStartedAt = string.Empty;

    public LessonViewModel(DatabaseService database, ToastService toasts, PhoneServer server)
    {
        _database = database;
        _toasts = toasts;
        _server = server;

        StartStopCommand = new RelayCommand(_ => StartOrStop(), _ => RollcallState != StateFinished);
        _database.DataChanged += OnDataChanged;

        _streamWatchdog = new DispatcherTimer { Interval = TimeSpan.FromSeconds(2) };
        _streamWatchdog.Tick += (_, _) =>
        {
            _streamWatchdog.Stop();
            HasStream = false;
        };

        // сервер спрашивает нас о перекличке и передаёт отметки/кадры видео
        _server.IsRollcallActive = () => RollcallState == StateActive;
        _server.GetGroupName = () => SelectedGroup?.Name ?? "";
        _server.GetStudents = () => Rows
            .Select(r => new StudentDto(r.StudentId, r.FullName, r.IsPresent))
            .ToList();
        _server.MarkStudent = MarkStudentInternal;
        _server.CheckInByPhone = CheckInByPhoneInternal;
        _server.FrameReceived += OnFrame;

        ReloadGroups();
    }

    // ------------------------------------------------------------------ группа

    public ObservableCollection<Group> Groups { get; } = new();

    private Group? _selectedGroup;
    public Group? SelectedGroup
    {
        get => _selectedGroup;
        set
        {
            if (Set(ref _selectedGroup, value) && RollcallState != StateActive)
            {
                LoadStudents();
            }
        }
    }

    public bool IsGroupSelectorEnabled => RollcallState != StateActive;

    // ---------------------------------------------------------------- студентам

    public ObservableCollection<StudentRow> Rows { get; } = new();

    private int _presentCount;
    public int PresentCount
    {
        get => _presentCount;
        private set => Set(ref _presentCount, value);
    }

    private int _absentCount;
    public int AbsentCount
    {
        get => _absentCount;
        private set => Set(ref _absentCount, value);
    }

    private double _percent;
    public double Percent
    {
        get => _percent;
        private set
        {
            if (Set(ref _percent, value))
            {
                OnPropertyChanged(nameof(PercentText));
                OnPropertyChanged(nameof(RatioText));
            }
        }
    }

    public string PercentText => Rows.Count == 0 ? "0%" : $"{(int)Math.Round(Percent * 100)}%";
    public string RatioText => $"{PresentCount}/{Rows.Count}";

    // -------------------------------------------------------------- видеопоток

    private bool _hasStream;
    public bool HasStream
    {
        get => _hasStream;
        private set => Set(ref _hasStream, value);
    }

    private ImageSource? _streamFrame;
    public ImageSource? StreamFrame
    {
        get => _streamFrame;
        private set => Set(ref _streamFrame, value);
    }

    private void OnFrame(byte[] jpeg, int rotation)
    {
        StreamFrame = DecodeImage(jpeg, rotation);
        if (!HasStream) HasStream = true;
        _streamWatchdog.Stop();
        _streamWatchdog.Start();
    }

    // ---------------------------------------------------------------- QR-код

    private ImageSource? _qrImage;
    public ImageSource? QrImage
    {
        get => _qrImage;
        private set => Set(ref _qrImage, value);
    }

    private string _qrUrl = string.Empty;
    public string QrUrl
    {
        get => _qrUrl;
        private set => Set(ref _qrUrl, value);
    }

    // --------------------------------------------------------------- перекличка

    private string _rollcallState = StateIdle;
    public string RollcallState
    {
        get => _rollcallState;
        private set
        {
            if (Set(ref _rollcallState, value))
            {
                OnPropertyChanged(nameof(IsGroupSelectorEnabled));
                OnPropertyChanged(nameof(ButtonText));
            }
        }
    }

    public string ButtonText => RollcallState switch
    {
        StateActive => "Завершить перекличку",
        StateFinished => "Завершено",
        _ => "Начать перекличку",
    };

    public RelayCommand StartStopCommand { get; }

    private void StartOrStop()
    {
        if (RollcallState == StateFinished) return;

        if (RollcallState == StateActive)
        {
            Finish();
            return;
        }

        if (SelectedGroup is null)
        {
            _toasts.Error("Выберите группу", "Сначала выберите группу из списка.");
            return;
        }
        if (Rows.Count == 0)
        {
            _toasts.Error("В группе нет студентов", "Добавьте студентов на вкладке «Студенты».");
            return;
        }

        foreach (var row in Rows)
        {
            row.IsPresent = false;
            row.MarkedAt = null;
        }
        _lessonStartedAt = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss");

        // QR-код со ссылкой для отметки
        QrUrl = _server.Url;
        QrImage = _server.IsRunning ? MakeQr(QrUrl) : null;

        RecalcStats();
        RollcallState = StateActive;
    }

    /// <summary>Отметка студента со страницы (по выбору имени). Приходит с телефона.</summary>
    private MarkResult MarkStudentInternal(int studentId)
    {
        if (RollcallState != StateActive) return MarkResult.Fail;
        var row = Rows.FirstOrDefault(r => r.StudentId == studentId);
        if (row is null) return MarkResult.Fail;

        if (!row.IsPresent) MarkRow(row);
        return new MarkResult(true, row.FullName, row.PhoneId);
    }

    /// <summary>Автоматическая отметка по сохранённому на телефоне ID.</summary>
    private MarkResult CheckInByPhoneInternal(string phoneId)
    {
        if (RollcallState != StateActive || string.IsNullOrEmpty(phoneId)) return MarkResult.Fail;
        var row = Rows.FirstOrDefault(r => r.PhoneId == phoneId);
        if (row is null) return MarkResult.Fail;

        if (!row.IsPresent) MarkRow(row);
        return new MarkResult(true, row.FullName, row.PhoneId);
    }

    private void MarkRow(StudentRow row)
    {
        row.IsPresent = true;
        row.MarkedAt = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss");

        if (row.PhoneId is null)
        {
            row.PhoneId = AuthService.GeneratePhoneId();
            _database.UpdateStudentPhoneId(row.StudentId, row.PhoneId);
        }

        RecalcStats();

        if (Rows.All(r => r.IsPresent))
        {
            Finish();
        }
    }

    private void Finish()
    {
        var now = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss");
        if (SelectedGroup is not null)
        {
            var lessonId = _database.AddLesson(SelectedGroup.Id, _lessonStartedAt, now);
            _database.AddAttendance(lessonId, Rows.Select(r =>
                (r.StudentId, r.IsPresent, r.MarkedAt ?? now)));
        }

        RollcallState = StateFinished;
        _toasts.Success("Перекличка завершена", $"Присутствуют {PresentCount} из {Rows.Count}.");

        _resetTimer?.Stop();
        _resetTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(2.5) };
        _resetTimer.Tick += (_, _) =>
        {
            _resetTimer?.Stop();
            _resetTimer = null;
            RollcallState = StateIdle;
        };
        _resetTimer.Start();
    }

    private void RecalcStats()
    {
        var present = Rows.Count(r => r.IsPresent);
        PresentCount = present;
        AbsentCount = Rows.Count - present;
        Percent = Rows.Count == 0 ? 0 : (double)present / Rows.Count;
        OnPropertyChanged(nameof(PercentText));
        OnPropertyChanged(nameof(RatioText));
    }

    // ---------------------------------------------------------------- загрузка

    public void OnActivated()
    {
        ReloadGroups();
        if (RollcallState != StateActive)
        {
            LoadStudents();
        }
    }

    private void OnDataChanged()
    {
        // снимок отметок ДО перезагрузки (ReloadGroups сам сбрасывает список)
        var marked = Rows.Where(r => r.IsPresent).ToDictionary(r => r.StudentId, r => r.MarkedAt);

        ReloadGroups();

        if (RollcallState is StateActive or StateFinished)
        {
            LoadStudents();
            foreach (var row in Rows)
            {
                if (marked.TryGetValue(row.StudentId, out var markedAt))
                {
                    row.IsPresent = true;
                    row.MarkedAt = markedAt;
                }
            }
            RecalcStats();
        }
    }

    private void ReloadGroups()
    {
        var selectedId = SelectedGroup?.Id;
        var groups = _database.GetGroups();

        Groups.Clear();
        foreach (var group in groups)
        {
            Groups.Add(group);
        }

        if (selectedId is int id)
        {
            SelectedGroup = Groups.FirstOrDefault(g => g.Id == id);
        }
        SelectedGroup ??= Groups.FirstOrDefault();
        OnPropertyChanged(nameof(SelectedGroup));
    }

    private void LoadStudents()
    {
        Rows.Clear();
        if (SelectedGroup is null)
        {
            RecalcStats();
            return;
        }

        var number = 1;
        foreach (var student in _database.GetStudents(SelectedGroup.Id))
        {
            Rows.Add(new StudentRow
            {
                StudentId = student.Id,
                Number = number++,
                FullName = student.FullName,
                PhoneId = student.PhoneId,
            });
        }
        RecalcStats();
    }

    // ---------------------------------------------------------------- картинки

    private static ImageSource DecodeImage(byte[] data, int rotation)
    {
        var image = new BitmapImage();
        using (var stream = new MemoryStream(data))
        {
            image.BeginInit();
            image.CacheOption = BitmapCacheOption.OnLoad;
            image.StreamSource = stream;
            image.Rotation = rotation switch
            {
                90 => Rotation.Rotate90,
                180 => Rotation.Rotate180,
                270 => Rotation.Rotate270,
                _ => Rotation.Rotate0,
            };
            image.EndInit();
        }
        image.Freeze();
        return image;
    }

    private static ImageSource MakeQr(string content)
    {
        var generator = new QRCodeGenerator();
        var data = generator.CreateQrCode(content, QRCodeGenerator.ECCLevel.M);
        var qr = new PngByteQRCode(data);
        var png = qr.GetGraphic(12);
        return DecodeImage(png, 0);
    }
}
