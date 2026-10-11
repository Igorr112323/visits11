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
    public int Id { get; init; }
    public int Number { get; init; }
    public string FullName { get; init; } = string.Empty;

    /// <summary>Код из QR-кода студента — он же заменяет логин и пароль.</summary>
    public string QrToken { get; init; } = string.Empty;

    /// <summary>Телефон, к которому привязан студент; null — ещё не привязывался.</summary>
    public string? DeviceId { get; init; }

    /// <summary>Подпись для колонки «Телефон».</summary>
    public string DeviceLabel => DeviceId is null ? "не привязан" : "привязан";
}

/// <summary>
/// Вкладка «Студенты»: группы, таблица студентов с QR-кодами, импорт из Word.
/// Логинов и паролей в системе нет: вход студента выполняет его QR-код.
/// </summary>
public sealed class StudentsViewModel : ObservableObject, ITabViewModel
{
    private readonly DatabaseService _database;
    private readonly ToastService _toasts;
    private readonly WordService _word;
    private readonly QrExportService _qrExport;
    private readonly MainViewModel _main;

    public StudentsViewModel(DatabaseService database, ToastService toasts, WordService word,
        QrExportService qrExport, MainViewModel main)
    {
        _database = database;
        _toasts = toasts;
        _word = word;
        _qrExport = qrExport;
        _main = main;

        NewGroupCommand = new RelayCommand(_ => CreateGroupDialog());
        AddStudentCommand = new RelayCommand(_ => AddStudentDialog(), _ => SelectedGroup is not null);
        ImportWordCommand = new RelayCommand(_ => ImportFromWord());
        ExportQrCommand = new RelayCommand(_ => ExportQrCodes(), _ => SelectedGroup is not null && Students.Count > 0);
        ShowQrCommand = new RelayCommand(param => ShowQrCode(param as StudentTableItem), _ => SelectedGroup is not null);
        ResetDeviceCommand = new RelayCommand(param => ResetDevice(param as StudentTableItem));
        DeleteStudentCommand = new RelayCommand(param => DeleteStudentDialog(param as StudentTableItem));
        DeleteGroupCommand = new RelayCommand(_ => DeleteGroupDialog(), _ => SelectedGroup is not null);
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

    public string HeaderCount => string.Empty;

    public RelayCommand NewGroupCommand { get; }
    public RelayCommand AddStudentCommand { get; }
    public RelayCommand ImportWordCommand { get; }
    public RelayCommand ExportQrCommand { get; }
    public RelayCommand ShowQrCommand { get; }
    public RelayCommand ResetDeviceCommand { get; }
    public RelayCommand SelectGroupCommand { get; }
    public RelayCommand DeleteStudentCommand { get; }
    public RelayCommand DeleteGroupCommand { get; }

    // --------------------------------------------------------------- студенты

    public ObservableCollection<StudentTableItem> Students { get; } = new();

    // ---------------------------------------------------------------- команды

    private void ResetDevice(StudentTableItem? student)
    {
        if (student is null || student.DeviceId is null) return;
        _database.ResetDeviceId(student.Id);
        _toasts.Success("Привязка сброшена", $"«{student.FullName}» может войти и отметиться с нового телефона.");
    }

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

    private void DeleteStudentDialog(StudentTableItem? student)
    {
        if (student is null || SelectedGroup is null) return;

        _main.ShowModal(new ModalViewModel
        {
            Title = "Удалить студента?",
            Message = $"«{student.FullName}» будет удалён вместе со всеми его отметками.",
            ConfirmText = "Удалить",
            OnConfirm = _ =>
            {
                _database.DeleteStudent(student.Id);
                _toasts.Success("Студент удалён", student.FullName);
                return true;
            },
        });
    }

    private void DeleteGroupDialog()
    {
        if (SelectedGroup is null) return;
        var group = SelectedGroup;

        _main.ShowModal(new ModalViewModel
        {
            Title = "Удалить группу?",
            Message = $"Вместе с группой «{group.Name}» будут удалены все её студенты, занятия и отметки.",
            ConfirmText = "Удалить",
            OnConfirm = _ =>
            {
                _database.DeleteGroup(group.Id);
                SelectedGroup = null;
                _toasts.Success("Группа удалена", group.Name);
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

                _database.AddStudent(new Student
                {
                    GroupId = SelectedGroup.Id,
                    FullName = fullName,
                    QrToken = AuthService.GenerateQrToken(),
                });
                _toasts.Success("Студент добавлен", $"{fullName} · QR-код готов (кнопка «QR-код»)»");
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

        var usedTokens = new HashSet<string>(StringComparer.Ordinal);
        var students = new List<Student>();
        foreach (var name in fresh)
        {
            var token = AuthService.GenerateQrToken();
            while (!usedTokens.Add(token))
            {
                token = AuthService.GenerateQrToken(); // совпадение 128-битных кодов — практически невозможно
            }
            students.Add(new Student
            {
                GroupId = group.Id,
                FullName = name,
                QrToken = token,
            });
        }
        _database.AddStudents(students);

        _toasts.Success("Импорт завершён",
            $"Добавлено {fresh.Count} {Pluralize(fresh.Count, "студент", "студента", "студентов")} в группу «{groupName}».");
    }

    /// <summary>
    /// «Показать QR-код» у студента: окно с картинкой кода — студент может
    /// навести камеру прямо на экран или сохранить картинку и прислать её.
    /// </summary>
    private void ShowQrCode(StudentTableItem? student)
    {
        if (student is null || student.QrToken.Length == 0) return;

        var content = StudentQr.Build(student.QrToken, student.FullName);
        var image = StudentQr.RenderImage(content);

        _main.ShowModal(new ModalViewModel
        {
            Title = "QR-код студента",
            Image = image,
            ConfirmText = "Сохранить картинку",
            OnConfirm = _ => SaveQrImage(student, content),
        });
    }

    /// <summary>Сохраняет картинку QR-кода в файл PNG (для отправки студенту).</summary>
    private bool SaveQrImage(StudentTableItem student, string content)
    {
        var dialog = new SaveFileDialog
        {
            Title = "Куда сохранить QR-код",
            Filter = "Картинка PNG (*.png)|*.png",
            FileName = SanitizeFileName(student.FullName) + ".png",
        };
        if (dialog.ShowDialog() != true) return false;

        try
        {
            File.WriteAllBytes(dialog.FileName, StudentQr.RenderPng(content));
            _toasts.Success("QR-код сохранён", dialog.FileName);
            return true;
        }
        catch (Exception exception)
        {
            _toasts.Error("Не удалось сохранить файл", exception.Message);
            return false;
        }
    }

    /// <summary>Имя файла без символов, запрещённых в Windows.</summary>
    private static string SanitizeFileName(string name)
    {
        var builder = new System.Text.StringBuilder(name.Length);
        foreach (var ch in name)
        {
            builder.Append(ch is '<' or '>' or ':' or '"' or '/' or '\\' or '|' or '?' or '*' ? '-' : ch);
        }
        var clean = builder.ToString().Trim().TrimEnd('.');
        return clean.Length == 0 ? "QR-код" : clean;
    }

    /// <summary>
    /// «Экспорт QR-кодов»: в выбранной папке создаётся подпапка «QR-коды &lt;группа&gt;»
    /// с картинкой на каждого студента. Картинку студент сохраняет в галерею и
    /// входит в приложение через «Загрузить из галереи» — сервер для этого не нужен.
    /// </summary>
    private void ExportQrCodes()
    {
        if (SelectedGroup is null || Students.Count == 0) return;

        var dialog = new OpenFolderDialog
        {
            Title = "Куда сохранить QR-коды студентов",
            Multiselect = false,
        };
        if (dialog.ShowDialog() != true) return;

        try
        {
            var students = _database.GetStudents(SelectedGroup.Id);
            var (folder, count) = _qrExport.Export(dialog.FolderName, SelectedGroup.Name, students);
            _toasts.Success("QR-коды готовы",
                $"Папка «{Path.GetFileName(folder)}» · {count} {Pluralize(count, "файл", "файла", "файлов")}. " +
                "Отправьте каждому студенту его картинку.");
        }
        catch (Exception exception)
        {
            _toasts.Error("Не удалось сохранить QR-коды", exception.Message);
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
                Id = student.Id,
                Number = number++,
                FullName = student.FullName,
                QrToken = student.QrToken,
                DeviceId = student.DeviceId,
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
