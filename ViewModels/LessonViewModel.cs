using System.Collections.ObjectModel;
using System.Text.Json;
using System.Windows.Threading;
using Visits11.Models;
using Visits11.Services;

namespace Visits11.ViewModels;

public sealed class StudentRow : ObservableObject
{
    public int StudentId { get; init; }
    public int Number { get; init; }
    public string FullName { get; init; } = string.Empty;

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

    private bool _suspicious;

    /// <summary>Телефон этого студента пытались использовать для отметки другого.</summary>
    public bool Suspicious
    {
        get => _suspicious;
        set => Set(ref _suspicious, value);
    }

    /// <summary>Момент отметки (для записи в БД).</summary>
    public string? MarkedAt { get; set; }

    public bool IsSherShnev { get; init; }

    private bool _hasCrown;
    public bool HasCrown
    {
        get => _hasCrown;
        set
        {
            if (Set(ref _hasCrown, value))
            {
                OnPropertyChanged(nameof(CrownText));
            }
        }
    }

    public string CrownText => HasCrown ? "👑" : string.Empty;
}

/// <summary>
/// Вкладка «Занятия»: выбор группы, перекличка, статистика.
/// Отметка приходит с телефона преподавателя по USB: студент прикладывает
/// телефон (BLE), ПК проверяет код из его QR-кода и привязку устройства.
/// </summary>
public sealed class LessonViewModel : ObservableObject, ITabViewModel
{
    private const string StateIdle = "Idle";
    private const string StateActive = "Active";
    private const string StateFinished = "Finished";

    // Коды ответа студенту (телефон показывает по ним результат касания)
    private const int ReplyMarked = 0;        // отмечен, дальше имя студента
    private const int ReplyUnknownQr = 2;     // такого QR-кода нет в журнале
    private const int ReplyUnavailable = 4;   // перекличка не идёт / студент не из этой группы
    private const int ReplyDeviceBlocked = 5; // телефон уже привязан к другому студенту

    private readonly DatabaseService _database;
    private readonly ToastService _toasts;
    private readonly PhoneLink _phone;

    private DispatcherTimer? _resetTimer;
    private DateTime _lastFinishedAt = DateTime.MinValue;
    private string _lessonStartedAt = string.Empty;
    private bool _savingLesson;
    /// <summary>Id занятия в базе, пока перекличка идёт (живёт между перезапусками EXE).</summary>
    private int? _activeLessonId;

    public LessonViewModel(DatabaseService database, ToastService toasts, PhoneLink phone)
    {
        _database = database;
        _toasts = toasts;
        _phone = phone;

        StartStopCommand = new RelayCommand(_ => StartOrStop());
        TogglePresentCommand = new RelayCommand(
            row => TogglePresent((StudentRow)row!), _ => RollcallState == StateActive);
        _database.DataChanged += OnDataChanged;

        // касания с телефона преподавателя обрабатывает сама программа
        _phone.MarkRelay += HandleMarkRequest;

        ReloadGroups();
        ResumePendingLesson();
    }

    public static bool IsSherShnevName(string fullName)
    {
        if (string.IsNullOrWhiteSpace(fullName)) return false;
        var lower = fullName.ToLowerInvariant().Replace('ё', 'е');
        return lower.Contains("шершнев");
    }

    /// <summary>
    /// Перекличка не была завершена в прошлый запуск (EXE закрыли или он упал) —
    /// продолжаем её автоматически: группа, уже сделанные отметки и состояние «идёт»
    /// восстанавливаются из базы, поэтому телефоны продолжают работать сразу.
    /// </summary>
    private void ResumePendingLesson()
    {
        var pending = _database.GetActiveLesson();
        if (pending is null) return;
        var pendingLesson = pending.Value;

        if (!DateTime.TryParse(pendingLesson.StartedAt, out var started) ||
            (DateTime.Now - started).TotalHours > 12)
        {
            // слишком старая перекличка: закрываем, чтобы не путать с текущей
            _database.CloseStaleLesson(pendingLesson.Id);
            return;
        }

        var group = Groups.FirstOrDefault(g => g.Id == pendingLesson.GroupId);
        if (group is null)
        {
            _database.CloseStaleLesson(pendingLesson.Id);
            return;
        }

        _lessonStartedAt = pendingLesson.StartedAt;
        _activeLessonId = pendingLesson.Id;
        SelectedGroup = group; // при смене группы LoadStudents() сработает сам

        foreach (var (studentId, markedAt, hasCrown) in _database.GetLessonPresentsWithCrown(pendingLesson.Id))
        {
            var row = Rows.FirstOrDefault(r => r.StudentId == studentId);
            if (row is not null)
            {
                row.IsPresent = true;
                row.MarkedAt = markedAt;
                row.HasCrown = hasCrown;
            }
        }
        // Шершнев всегда должен быть отмечен, если он в группе и перекличка идёт
        foreach (var row in Rows.Where(r => r.IsSherShnev))
        {
            if (!row.IsPresent)
            {
                row.IsPresent = true;
                row.MarkedAt ??= _lessonStartedAt;
                try
                {
                    if (row.HasCrown)
                        _database.SaveMarkWithCrown(_activeLessonId.Value, row.StudentId, row.MarkedAt!, row.HasCrown);
                    else
                        _database.SaveMark(_activeLessonId.Value, row.StudentId, row.MarkedAt!);
                }
                catch { }
            }
        }
        RecalcStats();
        RollcallState = StateActive;
        _toasts.Success("Перекличка восстановлена",
            $"Группа {group.Name}: отмечено {PresentCount} из {Rows.Count}.");
    }

    // ------------------------------------------------------------- BLE-касания

    /// <summary>
    /// Запрос с телефона преподавателя: {"id":N,"token":"...","device":"..."}.
    /// Ответ: {"id":N,"code":C,"value":"..."} — его телефон отдаёт студенту.
    /// </summary>
    private string HandleMarkRequest(string json)
    {
        long id = 0;
        var token = string.Empty;
        var device = string.Empty;
        try
        {
            using var document = JsonDocument.Parse(json);
            var root = document.RootElement;
            if (root.TryGetProperty("id", out var idValue) && idValue.ValueKind == JsonValueKind.Number)
            {
                id = idValue.GetInt64();
            }
            if (root.TryGetProperty("token", out var tokenValue) && tokenValue.ValueKind == JsonValueKind.String)
            {
                token = tokenValue.GetString() ?? string.Empty;
            }
            if (root.TryGetProperty("device", out var deviceValue) && deviceValue.ValueKind == JsonValueKind.String)
            {
                device = deviceValue.GetString() ?? string.Empty;
            }
        }
        catch (JsonException)
        {
            return Reply(id, ReplyUnavailable, string.Empty);
        }

        try
        {
            var dispatcher = System.Windows.Application.Current?.Dispatcher;
            if (dispatcher is not null && !dispatcher.CheckAccess())
            {
                return dispatcher.Invoke(() => ProcessBleTap(id, token, device));
            }
            return ProcessBleTap(id, token, device);
        }
        catch
        {
            return Reply(id, ReplyUnavailable, string.Empty);
        }
    }

    private string ProcessBleTap(long id, string token, string device)
    {
        if (string.IsNullOrWhiteSpace(token) || string.IsNullOrWhiteSpace(device))
            return Reply(id, ReplyUnavailable, string.Empty);
        var student = _database.FindByQrToken(token);
        if (student is null)
        {
            _toasts.Error("Касание: неизвестный QR-код",
                "Такого кода нет в журнале — студенту нужно войти заново (сканер или картинка).");
            return Reply(id, ReplyUnknownQr, string.Empty);
        }

        var (ok, deviceBlocked, name) = MarkStudentInternal(student.Id, device);
        if (deviceBlocked) return Reply(id, ReplyDeviceBlocked, string.Empty);
        if (!ok) return Reply(id, ReplyUnavailable, string.Empty);
        return Reply(id, ReplyMarked, name);
    }

    /// <summary>Собирает ответ для телефона преподавателя.</summary>
    private static string Reply(long id, int code, string value)
    {
        var escaped = value.Replace("\\", "\\\\").Replace("\"", "\\\"");
        return $"{{\"id\":{id},\"code\":{code},\"value\":\"{escaped}\"}}";
    }

    /// <summary>Отметка студента по id (BLE «телефон к телефону») или ручным кликом.</summary>
    private (bool Ok, bool DeviceBlocked, string Name) MarkStudentInternal(int studentId, string device)
    {
        var row = Rows.FirstOrDefault(r => r.StudentId == studentId);
        if (row is null) return (false, false, string.Empty);

        // если последний студент только что закрыл перекличку и его телефон ещё не убрали —
        // повторно подтверждаем его же отметку
        if ((RollcallState == StateFinished || (DateTime.UtcNow - _lastFinishedAt).TotalSeconds < 4) && row.IsPresent)
        {
            // Если это Шершнев, даже при повторном подтверждении ставим корону
            if (row.IsSherShnev && !row.HasCrown)
            {
                row.HasCrown = true;
                if (_activeLessonId is { } lid && row.MarkedAt != null)
                {
                    try { _database.SaveMarkWithCrown(lid, row.StudentId, row.MarkedAt, true); }
                    catch { }
                    try { _database.SaveMark(lid, row.StudentId, row.MarkedAt); } catch { }
                }
            }
            return (true, false, row.FullName);
        }
        if (RollcallState != StateActive) return (false, false, string.Empty);

        // отметка с чужого телефона не проходит
        string? bound = null;
        if (device.Length > 0)
        {
            // телефон уже принадлежит другому студенту — подсвечиваем владельца
            var owner = _database.FindStudentIdByDevice(device);
            if (owner is int ownerId && ownerId != studentId)
            {
                FlagSuspicious(ownerId, row.FullName);
                return (false, true, string.Empty);
            }

            bound = _database.GetDeviceId(studentId);
            if (bound != null && bound != device)
            {
                return (false, true, string.Empty);
            }
        }

        var fullName = row.FullName;
        var wasPresent = row.IsPresent;
        if (!wasPresent)
        {
            _toasts.Success("Отмечен", fullName);
            MarkRow(row, isRealTap: true);
        }
        else
        {
            // Уже был отмечен (например, Шершнев авто-отмечен), но сейчас реальное касание — ставим корону
            if (row.IsSherShnev && !row.HasCrown)
            {
                row.HasCrown = true;
                if (_activeLessonId is { } lid && row.MarkedAt != null)
                {
                    try { _database.SaveMarkWithCrown(lid, row.StudentId, row.MarkedAt, true); }
                    catch { }
                    try { _database.SaveMark(lid, row.StudentId, row.MarkedAt); } catch { }
                }
            }
        }

        // Привязка устройства после отметки, чтобы не вызывать DataChanged до сохранения отметки
        // (иначе первая отметка терялась из-за пересоздания Rows)
        if (device.Length > 0 && bound is null)
        {
            try
            {
                _database.SetDeviceId(studentId, device);
            }
            catch { }
        }

        return (true, false, fullName);
    }

    /// <summary>Телефон студента пытались использовать для отметки другого — подсвечиваем его строку.</summary>
    private void FlagSuspicious(int ownerId, string attemptedName)
    {
        var row = Rows.FirstOrDefault(r => r.StudentId == ownerId);
        if (row is not null)
        {
            row.Suspicious = true;
        }
        var ownerName = row?.FullName ?? _database.GetStudentName(ownerId) ?? "неизвестного студента";
        _toasts.Error("Что-то не так",
            $"Телефон студента {ownerName} использовали для отметки «{attemptedName}»");
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
                System.Windows.Input.CommandManager.InvalidateRequerySuggested();
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

    /// <summary>Клик по строке списка — ручная отметка/снятие (студент забыл телефон).</summary>
    public RelayCommand TogglePresentCommand { get; }

    private void StartOrStop()
    {
        if (RollcallState == StateActive)
        {
            Finish();
            return;
        }

        _resetTimer?.Stop();
        _resetTimer = null;

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
            row.HasCrown = false;
        }
        _lessonStartedAt = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss");
        _activeLessonId = _database.BeginLesson(SelectedGroup.Id, _lessonStartedAt);

        // Шершнев всегда отмечен, если он в группе и началась перекличка
        foreach (var row in Rows.Where(r => r.IsSherShnev))
        {
            row.IsPresent = true;
            row.MarkedAt = _lessonStartedAt;
            row.HasCrown = false;
            try { _database.SaveMarkWithCrown(_activeLessonId.Value, row.StudentId, row.MarkedAt, false); }
            catch { }
        }

        RecalcStats();
        RollcallState = StateActive;
    }

    /// <summary>Ручная отметка кликом по строке: ставит или снимает «Есть».</summary>
    private void TogglePresent(StudentRow row)
    {
        if (RollcallState != StateActive) return;

        if (row.IsPresent)
        {
            // Шершнева нельзя снять ручным кликом — он всегда должен быть
            if (row.IsSherShnev)
            {
                _toasts.Info("Шершнев всегда отмечен", "Этот студент отмечается автоматически.");
                return;
            }
            row.IsPresent = false;
            row.MarkedAt = null;
            row.HasCrown = false;
            RecalcStats();
            if (_activeLessonId is { } lessonId)
            {
                try { _database.RemoveMark(lessonId, row.StudentId); }
                catch { /* снимок восстановится в Finish() */ }
            }
        }
        else
        {
            MarkRow(row, isRealTap: false);
        }
    }

    private void MarkRow(StudentRow row, bool isRealTap = true)
    {
        row.IsPresent = true;
        row.MarkedAt = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss");
        if (row.IsSherShnev && isRealTap)
        {
            row.HasCrown = true;
        }

        RecalcStats();

        // сохраняем сразу: перекличка переживёт перезапуск EXE
        if (_activeLessonId is { } lessonId)
        {
            try
            {
                if (row.HasCrown)
                    _database.SaveMarkWithCrown(lessonId, row.StudentId, row.MarkedAt!, row.HasCrown);
                else
                    _database.SaveMark(lessonId, row.StudentId, row.MarkedAt!);
            }
            catch { /* база недоступна — полный снимок дублируется в Finish() */ }
        }

        if (Rows.All(r => r.IsPresent))
        {
            Finish();
        }
    }

    private void Finish()
    {
        var now = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss");
        _lastFinishedAt = DateTime.UtcNow;

        if (SelectedGroup is not null)
        {
            var snapshot = Rows.Select(r => (r.StudentId, r.IsPresent, r.MarkedAt ?? now, r.HasCrown)).ToList();
            var snapshotOld = Rows.Select(r => (r.StudentId, r.IsPresent, r.MarkedAt ?? now)).ToList();
            _savingLesson = true;
            try
            {
                if (_activeLessonId is { } lessonId)
                {
                    if (snapshot.Any(s => s.HasCrown))
                        _database.FinishLessonWithCrown(lessonId, now, snapshot);
                    else
                        _database.FinishLesson(lessonId, now, snapshotOld);
                }
                else
                {
                    var newLessonId = _database.AddLesson(SelectedGroup.Id, _lessonStartedAt, now);
                    if (snapshot.Any(s => s.HasCrown))
                        _database.AddAttendanceWithCrown(newLessonId, snapshot);
                    else
                        _database.AddAttendance(newLessonId, snapshotOld);
                }
            }
            finally
            {
                _savingLesson = false;
            }
            _activeLessonId = null;
        }

        _toasts.Success("Перекличка завершена", $"Присутствуют {PresentCount} из {Rows.Count}.");

        _resetTimer?.Stop();
        _resetTimer = null;
        RollcallState = StateIdle;
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
        if (_savingLesson) return;

        // снимок отметок ДО перезагрузки (ReloadGroups сам сбрасывает список)
        var marked = Rows.Where(r => r.IsPresent).ToDictionary(r => r.StudentId, r => (r.MarkedAt, r.HasCrown));

        ReloadGroups();

        if (RollcallState is StateActive or StateFinished)
        {
            LoadStudents();
            foreach (var row in Rows)
            {
                if (marked.TryGetValue(row.StudentId, out var data))
                {
                    row.IsPresent = true;
                    row.MarkedAt = data.MarkedAt;
                    row.HasCrown = data.HasCrown;
                }
            }
            // Шершнев должен оставаться отмеченным даже после перезагрузки данных
            foreach (var row in Rows.Where(r => r.IsSherShnev))
            {
                if (!row.IsPresent)
                {
                    row.IsPresent = true;
                    row.MarkedAt ??= _lessonStartedAt;
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
                IsSherShnev = IsSherShnevName(student.FullName),
            });
        }
        RecalcStats();
    }
}
