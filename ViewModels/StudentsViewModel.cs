using System.Collections.ObjectModel;
using System.IO;
using System.Windows;
using Microsoft.Win32;
using Visits11.Models;
using Visits11.Services;

namespace Visits11.ViewModels;

public sealed class GroupItemViewModel : ObservableObject
{
    public int Id { get; init; }
    public string Name { get; init; } = string.Empty;

    private int _count;
    public int Count
    {
        get => _count;
        set => Set(ref _count, value);
    }
}

public sealed class StudentTableItem
{
    public int Number { get; init; }
    public string FullName { get; init; } = string.Empty;
    public string Login { get; init; } = string.Empty;
    public string Password { get; init; } = string.Empty;
    public string? PhoneId { get; init; }
}

/// <summary>
/// Вкладка «Студенты»: группы, таблица студентов, импорт/экспорт Word.
/// </summary>
public sealed class StudentsViewModel : ObservableObject, ITabViewModel
{
    private readonly DatabaseService _database;
    private readonly ToastService _toasts;
    private readonly AuthService _auth;
    private readonly WordService _word;
    private readonly MainViewModel _main;

    public StudentsViewModel(DatabaseService database, ToastService toasts, AuthService auth, WordService word, MainViewModel main)
    {
        _database = database;
        _toasts = toasts;
        _auth = auth;
        _word = word;
        _main = main;

        NewGroupCommand = new RelayCommand(_ => CreateGroupDialog());
        AddStudentCommand = new RelayCommand(_ => AddStudentDialog(), _ => SelectedGroup is not null);
        ImportWordCommand = new RelayCommand(_ => ImportFromWord());
        ExportWordCommand = new RelayCommand(_ => ExportToWord(), _ => SelectedGroup is not null && Students.Count > 0);
        CopyTextCommand = new RelayCommand(param => CopyToClipboard(param as string));
        SelectGroupCommand = new RelayCommand(param =>
        {
            if (param is GroupItemViewModel group) SelectedGroup = group;
        });

        _database.DataChanged += OnDataChanged;
        ReloadAll();
    }

    // ------------------------------------------------------------------ группы

    public ObservableCollection<GroupItemViewModel> Groups { get; } = new();

    private GroupItemViewModel? _selectedGroup;
    public GroupItemViewModel? SelectedGroup
    {
        get => _selectedGroup;
        private set
        {
            if (Set(ref _selectedGroup, value))
            {
                OnPropertyChanged(nameof(HeaderTitle));
                OnPropertyChanged(nameof(HeaderCount));
                LoadStudents();
            }
        }
    }

    public string HeaderTitle => SelectedGroup?.Name ?? "";

    public string HeaderCount => SelectedGroup is null
        ? ""
        : $" {Pluralize(Students.Count, "студент", "студента", "студентов")}";

    public RelayCommand NewGroupCommand { get; }
    public RelayCommand AddStudentCommand { get; }
    public RelayCommand ImportWordCommand { get; }
    public RelayCommand ExportWordCommand { get; }
    public RelayCommand CopyTextCommand { get; }
    public RelayCommand SelectGroupCommand { get; }

    // --------------------------------------------------------------- студенты

    public ObservableCollection<StudentTableItem> Students { get; } = new();

    // ---------------------------------------------------------------- команды

    private void CreateGroupDialog()
    {
        _main.ShowModal(new ModalViewModel
        {
            Title = "Создание новой группы",
            ShowInput = true,
            InputLabel = "НАЗВАНИЕ ГРУППЫ",
            Placeholder = "Например, ИСП-31",
            ConfirmText = "Создать",
            OnConfirm = modal =>
            {
                var name = modal.InputText.Trim();
                if (name.Length == 0)
                {
                    _toasts.Error("Введите название", "Название группы не может быть пустым.");
                    return false;
                }
                if (_database.GroupNameExists(name))
                {
                    _toasts.Error("Группа уже существует", $"Группа «{name}» уже есть в списке.");
                    return false;
                }
                _database.AddGroup(name);
                _toasts.Success("Группа создана", name);
                return true;
            },
        });
    }

    private void AddStudentDialog()
    {
        if (SelectedGroup is null) return;

        _main.ShowModal(new ModalViewModel
        {
            Title = "Добавить студента",
            ShowInput = true,
            InputLabel = "ФИО СТУДЕНТА",
            Placeholder = "Фамилия Имя Отчество",
            ConfirmText = "Добавить",
            OnConfirm = modal =>
            {
                var fullName = string.Join(' ', modal.InputText.Split(' ', StringSplitOptions.RemoveEmptyEntries));
                if (fullName.Split(' ').Length < 2)
                {
                    _toasts.Error("Некорректное ФИО", "Введите минимум два слова: фамилию и имя.");
                    return false;
                }

                var login = _auth.GenerateLogin(fullName, _database.LoginExists);
                _database.AddStudent(new Student
                {
                    GroupId = SelectedGroup.Id,
                    FullName = fullName,
                    Login = login,
                    Password = AuthService.GeneratePassword(),
                });
                _toasts.Success("Студент добавлен", $"{fullName} · {login}");
                return true;
            },
        });
    }

    private void ImportFromWord()
    {
        var dialog = new OpenFileDialog
        {
            Title = "Импорт из Word",
            Filter = "Документы Word (*.docx)|*.docx|Все файлы (*.*)|*.*",
        };
        if (dialog.ShowDialog() != true) return;

        List<string> names;
        try
        {
            names = _word.ImportStudents(dialog.FileName);
        }
        catch (Exception exception)
        {
            _toasts.Error("Не удалось прочитать файл", exception.Message);
            return;
        }

        if (names.Count == 0)
        {
            _toasts.Error("Нет данных", "В документе не найден список студентов.");
            return;
        }

        var suggestedName = Path.GetFileNameWithoutExtension(dialog.FileName);
        if (_database.GroupNameExists(suggestedName))
        {
            ConfirmAddToGroup(suggestedName, names);
        }
        else
        {
            CreateGroupWithStudents(suggestedName, names);
        }
    }

    private void ConfirmAddToGroup(string groupName, List<string> names)
    {
        _main.ShowModal(new ModalViewModel
        {
            Title = "Группа уже существует",
            Message = $"Группа «{groupName}» уже есть в базе. Добавить {names.Count} " +
                      $"{Pluralize(names.Count, "нового студента", "новых студента", "новых студентов")}?",
            ConfirmText = "Да, добавить",
            OnConfirm = _ =>
            {
                AddStudentsToGroup(groupName, names);
                return true;
            },
        });
    }

    private void CreateGroupWithStudents(string prefill, List<string> names)
    {
        _main.ShowModal(new ModalViewModel
        {
            Title = "Создание новой группы",
            ShowInput = true,
            InputLabel = "НАЗВАНИЕ ГРУППЫ",
            Placeholder = prefill,
            InputText = prefill,
            ConfirmText = "Создать",
            OnConfirm = modal =>
            {
                var name = modal.InputText.Trim();
                if (name.Length == 0)
                {
                    _toasts.Error("Введите название", "Название группы не может быть пустым.");
                    return false;
                }
                if (_database.GroupNameExists(name))
                {
                    ConfirmAddToGroup(name, names);
                    return true;
                }
                _database.AddGroup(name);
                AddStudentsToGroup(name, names);
                return true;
            },
        });
    }

    private void AddStudentsToGroup(string groupName, List<string> names)
    {
        var group = _database.GetGroups().FirstOrDefault(g => g.Name == groupName);
        if (group is null) return;

        var existing = _database.GetStudents(group.Id)
            .Select(s => s.FullName)
            .ToHashSet(StringComparer.OrdinalIgnoreCase);

        var fresh = names.Where(n => !existing.Contains(n)).ToList();
        if (fresh.Count == 0)
        {
            _toasts.Info("Нечего добавлять", "Все студенты из файла уже есть в группе.");
            return;
        }

        _database.AddStudents(fresh.Select(name => new Student
        {
            GroupId = group.Id,
            FullName = name,
            Login = _auth.GenerateLogin(name, _database.LoginExists),
            Password = AuthService.GeneratePassword(),
        }));

        _toasts.Success("Импорт завершён",
            $"Добавлено {fresh.Count} {Pluralize(fresh.Count, "студент", "студента", "студентов")} в группу «{groupName}».");
    }

    private void ExportToWord()
    {
        if (SelectedGroup is null || Students.Count == 0) return;

        var dialog = new SaveFileDialog
        {
            Title = "Экспорт в Word",
            Filter = "Документы Word (*.docx)|*.docx",
            FileName = $"{SelectedGroup.Name}.docx",
        };
        if (dialog.ShowDialog() != true) return;

        try
        {
            _word.ExportStudents(dialog.FileName, Students.Select(s => new Student
            {
                FullName = s.FullName,
                Login = s.Login,
                Password = s.Password,
            }).ToList());
            _toasts.Success("Файл сохранён", dialog.FileName);
        }
        catch (Exception exception)
        {
            _toasts.Error("Не удалось сохранить файл", exception.Message);
        }
    }

    private void CopyToClipboard(string? text)
    {
        if (string.IsNullOrEmpty(text)) return;
        try
        {
            Clipboard.SetText(text);
            _toasts.Info("Скопировано", text);
        }
        catch
        {
            _toasts.Error("Не удалось скопировать", "Буфер обмена занят другой программой.");
        }
    }

    // ------------------------------------------------------------- загрузка

    public void OnActivated() => ReloadAll();

    private void OnDataChanged() => ReloadAll();

    private void ReloadAll()
    {
        var selectedId = SelectedGroup?.Id;
        var groups = _database.GetGroups();

        Groups.Clear();
        foreach (var group in groups)
        {
            Groups.Add(new GroupItemViewModel
            {
                Id = group.Id,
                Name = group.Name,
                Count = _database.CountStudents(group.Id),
            });
        }

        SelectedGroup = selectedId is int id ? Groups.FirstOrDefault(g => g.Id == id) : Groups.FirstOrDefault();
    }

    private void LoadStudents()
    {
        Students.Clear();
        if (SelectedGroup is null)
        {
            OnPropertyChanged(nameof(HeaderCount));
            return;
        }

        var number = 1;
        foreach (var student in _database.GetStudents(SelectedGroup.Id))
        {
            Students.Add(new StudentTableItem
            {
                Number = number++,
                FullName = student.FullName,
                Login = student.Login,
                Password = student.Password,
                PhoneId = student.PhoneId,
            });
        }
        OnPropertyChanged(nameof(HeaderCount));
    }

    internal static string Pluralize(int n, string one, string few, string many)
    {
        n = Math.Abs(n) % 100;
        if (n is >= 11 and <= 14) return many;
        var last = n % 10;
        return last switch
        {
            1 => one,
            >= 2 and <= 4 => few,
            _ => many,
        };
    }
}
