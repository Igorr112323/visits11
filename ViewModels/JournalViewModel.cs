using System.Collections.ObjectModel;
using System.IO;
using Microsoft.Win32;
using Visits11.Models;
using Visits11.Services;

namespace Visits11.ViewModels;

public sealed class LessonItemViewModel
{
    public int Id { get; init; }
    public string DateText { get; init; } = string.Empty;
    public string TimeText { get; init; } = string.Empty;
    public string RatioText { get; init; } = string.Empty;
    public string PercentText { get; init; } = string.Empty;
    public double Percent { get; init; }
}

public sealed class JournalRow
{
    public int Number { get; init; }
    public string FullName { get; init; } = string.Empty;
    public bool Present { get; init; }
    public string StatusText => Present ? "Был" : "Не был";
}

/// <summary>
/// Вкладка «Журнал»: список занятий группы и детали выбранного занятия.
/// </summary>
public sealed class JournalViewModel : ObservableObject, ITabViewModel
{
    private readonly DatabaseService _database;
    private readonly ToastService _toasts;
    private readonly WordService _word;

    public JournalViewModel(DatabaseService database, ToastService toasts, WordService word)
    {
        _database = database;
        _toasts = toasts;
        _word = word;

        ExportCommand = new RelayCommand(_ => ExportSelectedLesson(), _ => SelectedLesson is not null);
        SelectLessonCommand = new RelayCommand(param =>
        {
            if (param is LessonItemViewModel lesson) SelectedLesson = lesson;
        });
        _database.DataChanged += OnDataChanged;
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
            if (Set(ref _selectedGroup, value))
            {
                LoadLessons();
            }
        }
    }

    // ---------------------------------------------------------------- занятия

    public ObservableCollection<LessonItemViewModel> Lessons { get; } = new();

    private LessonItemViewModel? _selectedLesson;
    public LessonItemViewModel? SelectedLesson
    {
        get => _selectedLesson;
        private set
        {
            if (Set(ref _selectedLesson, value))
            {
                OnPropertyChanged(nameof(HasLesson));
                LoadDetails();
            }
        }
    }

    public bool HasLesson => SelectedLesson is not null;

    public RelayCommand ExportCommand { get; }
    public RelayCommand SelectLessonCommand { get; }

    // ---------------------------------------------------------------- детали

    public ObservableCollection<JournalRow> Rows { get; } = new();

    private string _totalText = "0";
    public string TotalText
    {
        get => _totalText;
        private set => Set(ref _totalText, value);
    }

    private string _presentText = "0";
    public string PresentText
    {
        get => _presentText;
        private set => Set(ref _presentText, value);
    }

    private string _absentText = "0";
    public string AbsentText
    {
        get => _absentText;
        private set => Set(ref _absentText, value);
    }

    public string HeaderTitle => SelectedGroup?.Name ?? "";

    // ---------------------------------------------------------------- команды

    private void ExportSelectedLesson()
    {
        if (SelectedLesson is null || SelectedGroup is null)
        {
            _toasts.Error("Выберите занятие", "Сначала выберите занятие в списке слева.");
            return;
        }

        var lesson = _database.GetLessons(SelectedGroup.Id).FirstOrDefault(l => l.Id == SelectedLesson.Id);
        if (lesson is null) return;

        var details = _database.GetLessonDetails(lesson.Id);
        var date = LessonDate(lesson.StartedAt);

        var dialog = new SaveFileDialog
        {
            Title = "Экспорт журнала",
            Filter = "Документы Word (*.docx)|*.docx",
            FileName = $"Журнал {SelectedGroup.Name} {date}.docx",
        };
        if (dialog.ShowDialog() != true) return;

        try
        {
            var title = $"{SelectedGroup.Name} — {date} {LessonTime(lesson.StartedAt)} · " +
                        $"{lesson.Present}/{lesson.Total}";
            _word.ExportLesson(dialog.FileName, title, details);
            _toasts.Success("Файл сохранён", dialog.FileName);
        }
        catch (Exception exception)
        {
            _toasts.Error("Не удалось сохранить файл", exception.Message);
        }
    }

    // ------------------------------------------------------------- загрузка

    public void OnActivated()
    {
        ReloadGroups();
    }

    private void OnDataChanged()
    {
        ReloadGroups();
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

        SelectedGroup = selectedId is int id ? Groups.FirstOrDefault(g => g.Id == id) : Groups.FirstOrDefault();
    }

    private void LoadLessons()
    {
        var selectedId = SelectedLesson?.Id;
        Lessons.Clear();

        if (SelectedGroup is not null)
        {
            foreach (var lesson in _database.GetLessons(SelectedGroup.Id))
            {
                Lessons.Add(new LessonItemViewModel
                {
                    Id = lesson.Id,
                    DateText = LessonDate(lesson.StartedAt),
                    TimeText = LessonTime(lesson.StartedAt),
                    RatioText = $"{lesson.Present}/{lesson.Total}",
                    PercentText = lesson.Total == 0 ? "0%" : $"{(int)Math.Round(lesson.Present * 100.0 / lesson.Total)}%",
                    Percent = lesson.Total == 0 ? 0 : (double)lesson.Present / lesson.Total,
                });
            }
        }

        SelectedLesson = selectedId is int id ? Lessons.FirstOrDefault(l => l.Id == id) : Lessons.FirstOrDefault();
        if (SelectedLesson is null)
        {
            OnPropertyChanged(nameof(HasLesson));
            LoadDetails();
        }
    }

    private void LoadDetails()
    {
        Rows.Clear();
        if (SelectedLesson is null || SelectedGroup is null)
        {
            TotalText = PresentText = AbsentText = "0";
            return;
        }

        var details = _database.GetLessonDetails(SelectedLesson.Id);
        var number = 1;
        foreach (var detail in details)
        {
            Rows.Add(new JournalRow
            {
                Number = number++,
                FullName = detail.FullName,
                Present = detail.Present,
            });
        }

        TotalText = details.Count.ToString();
        PresentText = details.Count(d => d.Present).ToString();
        AbsentText = details.Count(d => !d.Present).ToString();
    }

    private static string LessonDate(string startedAt)
        => DateTime.TryParse(startedAt, out var date) ? date.ToString("dd.MM.yyyy") : startedAt;

    private static string LessonTime(string startedAt)
        => DateTime.TryParse(startedAt, out var date) ? date.ToString("HH:mm") : string.Empty;
}
