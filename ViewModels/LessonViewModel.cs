using System.Collections.ObjectModel;
using System.IO;
using System.Security.Cryptography;
using System.Windows;
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
    private readonly CameraLink _camera;

    private DispatcherTimer? _resetTimer;
    private string _lessonStartedAt = string.Empty;

    // ------------------------------------------------------------- QR-коды студентов

    /// <summary>Магия-версия полезной нагрузки QR (внутри шифрования).</summary>
    private static readonly byte[] QrMagic = { 0x56, 0x31, 0x31, 0x41 }; // "V11A"

    /// <summary>Ключ шифрования QR — новый на каждую перекличку.</summary>
    private byte[] _qrKey = RandomNumberGenerator.GetBytes(32);

    /// <summary>Идентификатор переклички — QR с прошлого занятия не сработает.</summary>
    private byte[] _rollcallId = RandomNumberGenerator.GetBytes(8);

    private volatile bool _qrDecoding;
    private long _lastQrDecodeAt;

    public LessonViewModel(DatabaseService database, ToastService toasts, PhoneServer server, CameraLink camera)
    {
        _database = database;
        _toasts = toasts;
        _server = server;
        _camera = camera;

        StartStopCommand = new RelayCommand(_ => StartOrStop(), _ => RollcallState != StateFinished);
        TogglePresentCommand = new RelayCommand(
            row => TogglePresent((StudentRow)row!), _ => RollcallState == StateActive);
        _database.DataChanged += OnDataChanged;

        // сервер спрашивает нас про вход студентов и просит свежий QR для показа
        _server.Login = LoginInternal;
        _server.MarkStudent = MarkStudentInternal;
        _server.GetQrPng = BuildQrPng;
        _camera.FrameReceived += OnFrame;

        ReloadGroups();
    }

    /// <summary>Вход студента в приложении (логин/пароль из базы).</summary>
    private (bool Ok, bool DeviceBlocked, string Name, int StudentId) LoginInternal(
        string login, string password, string device)
    {
        var student = _database.FindByLogin(login);
        if (student is null || student.Password != password)
        {
            return (false, false, string.Empty, 0);
        }

        // первый вход с телефона — привязываем аккаунт к этому устройству;
        // вход с чужого телефона отклоняем
        if (device.Length > 0)
        {
            var bound = _database.GetDeviceId(student.Id);
            if (bound is null)
            {
                _database.SetDeviceId(student.Id, device);
            }
            else if (bound != device)
            {
                return (false, true, string.Empty, 0);
            }
        }

        return (true, false, student.FullName, student.Id);
    }

    /// <summary>PNG текущего QR студента; null — перекличка не активна.</summary>
    private byte[]? BuildQrPng(int studentId)
    {
        if (RollcallState != StateActive) return null;

        var payload = BuildQrPayload(studentId);
        var encrypted = EncryptQrPayload(payload);

        var generator = new QRCodeGenerator();
        var data = generator.CreateQrCode(encrypted, QRCodeGenerator.ECCLevel.M);
        var qr = new PngByteQRCode(data);
        return qr.GetGraphic(12);
    }

    /// <summary>Полезная нагрузка: магия + id переклички + студент + 5-секундное окно + нонс.</summary>
    private byte[] BuildQrPayload(int studentId)
    {
        var payload = new byte[28];
        QrMagic.CopyTo(payload, 0);
        _rollcallId.CopyTo(payload, 4);
        BitConverter.GetBytes(studentId).CopyTo(payload, 12);
        BitConverter.GetBytes(CurrentQrWindow()).CopyTo(payload, 16);
        RandomNumberGenerator.Fill(payload.AsSpan(24, 4));
        return payload;
    }

    private static long CurrentQrWindow()
        => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() / 5000;

    /// <summary>AES-CBC: [IV 16 байт][шифротекст]; снаружи нечитаемо.</summary>
    private byte[] EncryptQrPayload(byte[] payload)
    {
        using var aes = Aes.Create();
        aes.Key = _qrKey;
        aes.Mode = CipherMode.CBC;
        aes.GenerateIV();
        using var encryptor = aes.CreateEncryptor();
        var cipher = encryptor.TransformFinalBlock(payload, 0, payload.Length);
        var result = new byte[16 + cipher.Length];
        aes.IV.CopyTo(result, 0);
        cipher.CopyTo(result, 16);
        return result;
    }

    /// <summary>Расшифровка и проверка отсканированного QR: id студента или null.</summary>
    private int? DecryptQrPayload(byte[] raw)
    {
        try
        {
            if (raw.Length != 48) return null; // 16 (IV) + 32 (данные)
            using var aes = Aes.Create();
            aes.Key = _qrKey;
            aes.IV = raw[..16];
            using var decryptor = aes.CreateDecryptor();
            var payload = decryptor.TransformFinalBlock(raw, 16, raw.Length - 16);
            if (payload.Length != 28) return null;
            for (var i = 0; i < 4; i++)
            {
                if (payload[i] != QrMagic[i]) return null;
            }
            for (var i = 0; i < 8; i++)
            {
                if (payload[4 + i] != _rollcallId[i]) return null;
            }
            var studentId = BitConverter.ToInt32(payload, 12);
            var window = BitConverter.ToInt64(payload, 16);
            var current = CurrentQrWindow();
            // QR живёт ~10 секунд: текущее или предыдущее окно
            if (window < current - 1 || window > current + 1) return null;
            return studentId;
        }
        catch
        {
            return null;
        }
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

    /// <summary>Последний кадр с телефона (для распознавания QR; видео на экране больше не показываем).</summary>
    private ImageSource? _lastFrame;

    private void OnFrame(byte[] jpeg, int rotation)
    {
        _lastFrame = DecodeImage(jpeg, rotation);
        TryScanQr();
    }

    /// <summary>
    /// Ищет QR студента в кадре (не чаще 4 раз/с, вне потока UI).
    /// Нашли валидный — отмечаем студента.
    /// </summary>
    private void TryScanQr()
    {
        if (RollcallState != StateActive || _qrDecoding) return;
        if (_lastFrame is not BitmapSource frame) return;

        var now = Environment.TickCount64;
        if (now - _lastQrDecodeAt < 250) return;
        _lastQrDecodeAt = now;
        _qrDecoding = true;

        Task.Run(() =>
        {
            try
            {
                var raw = QrScanner.Scan(frame);
                if (raw is null) return;

                var studentId = DecryptQrPayload(raw);
                if (studentId is int id)
                {
                    Application.Current?.Dispatcher.BeginInvoke(() => MarkScanned(id));
                }
            }
            finally
            {
                _qrDecoding = false;
            }
        });
    }

    /// <summary>Отметка студента, чей QR попал в камеру преподавателя.</summary>
    private void MarkScanned(int studentId)
    {
        MarkStudentInternal(studentId, string.Empty);
    }

    /// <summary>Отметка студента по id (сканирование камерой или NFC «телефон к телефону»).</summary>
    private (bool Ok, bool DeviceBlocked, string Name) MarkStudentInternal(int studentId, string device)
    {
        if (RollcallState != StateActive) return (false, false, string.Empty);
        var row = Rows.FirstOrDefault(r => r.StudentId == studentId);
        if (row is null) return (false, false, string.Empty);

        // отметка с чужого телефона не проходит
        if (device.Length > 0)
        {
            var bound = _database.GetDeviceId(studentId);
            if (bound is null)
            {
                _database.SetDeviceId(studentId, device);
            }
            else if (bound != device)
            {
                return (false, true, string.Empty);
            }
        }

        if (!row.IsPresent)
        {
            MarkRow(row);
            _toasts.Success("Отмечен", row.FullName);
        }
        return (true, false, row.FullName);
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

    /// <summary>Клик по строке списка — ручная отметка/снятие (например, студенты с iPhone).</summary>
    public RelayCommand TogglePresentCommand { get; }

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

        // новый ключ шифрования и ID переклички: QR прошлых занятий не сработают
        _qrKey = RandomNumberGenerator.GetBytes(32);
        _rollcallId = RandomNumberGenerator.GetBytes(8);

        RecalcStats();
        RollcallState = StateActive;
    }

    /// <summary>Ручная отметка кликом по строке: ставит или снимает «Есть».</summary>
    private void TogglePresent(StudentRow row)
    {
        if (RollcallState != StateActive) return;

        if (row.IsPresent)
        {
            row.IsPresent = false;
            row.MarkedAt = null;
            RecalcStats();
        }
        else
        {
            MarkRow(row);
        }
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

}
