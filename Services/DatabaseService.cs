using Microsoft.Data.Sqlite;
using Visits11.Models;

namespace Visits11.Services;

/// <summary>
/// Локальная SQLite-база в папке данных пользователя (%LOCALAPPDATA%/Visits11).
/// </summary>
public sealed class DatabaseService
{
    private readonly string _connectionString;

    /// <summary>Вызывается после любого изменения данных (группы/студенты/занятия).</summary>
    public event Action? DataChanged;

    public DatabaseService(string dbPath)
    {
        _connectionString = new SqliteConnectionStringBuilder
        {
            DataSource = dbPath,
            Mode = SqliteOpenMode.ReadWriteCreate,
        }.ToString();
    }

    private SqliteConnection Open()
    {
        var connection = new SqliteConnection(_connectionString);
        connection.Open();
        using var command = connection.CreateCommand();
        command.CommandText = "PRAGMA foreign_keys=ON;";
        command.ExecuteNonQuery();
        return connection;
    }

    public void Initialize()
    {
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = """
            CREATE TABLE IF NOT EXISTS Groups (
              Id INTEGER PRIMARY KEY AUTOINCREMENT,
              Name TEXT NOT NULL UNIQUE,
              CreatedAt TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS Students (
              Id INTEGER PRIMARY KEY AUTOINCREMENT,
              GroupId INTEGER NOT NULL,
              FullName TEXT NOT NULL,
              QrToken TEXT NOT NULL UNIQUE,
              DeviceId TEXT NULL,
              FOREIGN KEY (GroupId) REFERENCES Groups(Id) ON DELETE CASCADE
            );

            CREATE TABLE IF NOT EXISTS Lessons (
              Id INTEGER PRIMARY KEY AUTOINCREMENT,
              GroupId INTEGER NOT NULL,
              StartedAt TEXT NOT NULL,
              FinishedAt TEXT NULL,
              FOREIGN KEY (GroupId) REFERENCES Groups(Id) ON DELETE CASCADE
            );

            CREATE TABLE IF NOT EXISTS Attendance (
              Id INTEGER PRIMARY KEY AUTOINCREMENT,
              LessonId INTEGER NOT NULL,
              StudentId INTEGER NOT NULL,
              Status TEXT NOT NULL,
              MarkedAt TEXT NOT NULL,
              FOREIGN KEY (LessonId) REFERENCES Lessons(Id) ON DELETE CASCADE,
              FOREIGN KEY (StudentId) REFERENCES Students(Id) ON DELETE CASCADE
            );

            """;
        command.ExecuteNonQuery();

        // Таблица сессий осталась от версии с логинами и паролями — она больше не нужна
        using (var drop = connection.CreateCommand())
        {
            drop.CommandText = "DROP TABLE IF EXISTS Sessions;";
            drop.ExecuteNonQuery();
        }

        MigrateStudentsWithoutCredentials(connection);
    }

    /// <summary>
    /// Базы прошлых версий хранили логин и пароль — теперь вместо них постоянный
    /// код QR. Таблицу Students пересобираем: Id, ФИО и привязки к телефонам
    /// сохраняются, каждому студенту выдаётся код, отметки не теряются.
    /// </summary>
    private static void MigrateStudentsWithoutCredentials(SqliteConnection connection)
    {
        if (!ColumnExists(connection, "Login")) return; // колонки логина нет — база уже новой версии

        // старые базы могли не иметь привязки к телефону
        EnsureColumn(connection, "DeviceId", "TEXT NULL");

        var preserved = new List<(long Id, long GroupId, string FullName, string? DeviceId)>();
        using (var read = connection.CreateCommand())
        {
            read.CommandText = "SELECT Id, GroupId, FullName, DeviceId FROM Students";
            using var reader = read.ExecuteReader();
            while (reader.Read())
            {
                preserved.Add((
                    reader.GetInt64(0),
                    reader.GetInt64(1),
                    reader.GetString(2),
                    reader.IsDBNull(3) ? null : reader.GetString(3)));
            }
        }

        // !!! С выключенными внешними ключами: иначе DROP TABLE Students
        // каскадом удалил бы все отметки из Attendance
        using (var pragma = connection.CreateCommand())
        {
            pragma.CommandText = "PRAGMA foreign_keys=OFF";
            pragma.ExecuteNonQuery();
        }

        using (var transaction = connection.BeginTransaction())
        {
            using (var create = connection.CreateCommand())
            {
                create.CommandText = """
                    CREATE TABLE Students_new (
                      Id INTEGER PRIMARY KEY AUTOINCREMENT,
                      GroupId INTEGER NOT NULL,
                      FullName TEXT NOT NULL,
                      QrToken TEXT NOT NULL UNIQUE,
                      DeviceId TEXT NULL,
                      FOREIGN KEY (GroupId) REFERENCES Groups(Id) ON DELETE CASCADE
                    );
                    """;
                create.ExecuteNonQuery();
            }

            foreach (var student in preserved)
            {
                using var insert = connection.CreateCommand();
                insert.Transaction = transaction;
                insert.CommandText = """
                    INSERT INTO Students_new (Id, GroupId, FullName, QrToken, DeviceId)
                    VALUES (@id, @groupId, @fullName, @token, @deviceId);
                    """;
                insert.Parameters.AddWithValue("@id", student.Id);
                insert.Parameters.AddWithValue("@groupId", student.GroupId);
                insert.Parameters.AddWithValue("@fullName", student.FullName);
                insert.Parameters.AddWithValue("@token", AuthService.GenerateQrToken());
                insert.Parameters.AddWithValue("@deviceId", (object?)student.DeviceId ?? DBNull.Value);
                insert.ExecuteNonQuery();
            }

            using (var swap = connection.CreateCommand())
            {
                swap.Transaction = transaction;
                swap.CommandText = "DROP TABLE Students; ALTER TABLE Students_new RENAME TO Students;";
                swap.ExecuteNonQuery();
            }

            transaction.Commit();
        }

        using var back = connection.CreateCommand();
        back.CommandText = "PRAGMA foreign_keys=ON";
        back.ExecuteNonQuery();
    }

    /// <summary>Есть ли колонка в таблице Students.</summary>
    private static bool ColumnExists(SqliteConnection connection, string column)
    {
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT COUNT(*) FROM pragma_table_info('Students') WHERE name = @name";
        command.Parameters.AddWithValue("@name", column);
        return Convert.ToInt64(command.ExecuteScalar()) > 0;
    }

    /// <summary>Добавляет колонку, если её ещё нет (старые базы).</summary>
    private static void EnsureColumn(SqliteConnection connection, string column, string definition)
    {
        if (ColumnExists(connection, column)) return;
        using var command = connection.CreateCommand();
        command.CommandText = $"ALTER TABLE Students ADD COLUMN {column} {definition}";
        command.ExecuteNonQuery();
    }

    /// <summary>Устройство, к которому привязан аккаунт студента (null — ещё не привязан).</summary>
    public string? GetDeviceId(int studentId)
    {
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT DeviceId FROM Students WHERE Id = @id";
        command.Parameters.AddWithValue("@id", studentId);
        return command.ExecuteScalar() as string;
    }

    /// <summary>Привязывает аккаунт студента к устройству.</summary>
    public void SetDeviceId(int studentId, string deviceId)
    {
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = "UPDATE Students SET DeviceId = @device WHERE Id = @id";
        command.Parameters.AddWithValue("@device", deviceId);
        command.Parameters.AddWithValue("@id", studentId);
        command.ExecuteNonQuery();
        RaiseDataChanged();
    }

    /// <summary>Сбрасывает привязку телефона у студента (чтобы он мог войти с нового устройства).</summary>
    public void ResetDeviceId(int studentId)
    {
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = "UPDATE Students SET DeviceId = NULL WHERE Id = @id";
        command.Parameters.AddWithValue("@id", studentId);
        command.ExecuteNonQuery();
        RaiseDataChanged();
    }

    /// <summary>Студент, к чьему аккаунту привязано устройство; null — устройство неизвестно.</summary>
    public int? FindStudentIdByDevice(string device)
    {
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT Id FROM Students WHERE DeviceId = @device LIMIT 1";
        command.Parameters.AddWithValue("@device", device);
        return command.ExecuteScalar() is long value ? (int)value : null;
    }

    /// <summary>Удаляет студента вместе с его отметками.</summary>
    public void DeleteStudent(int studentId)
    {
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = "DELETE FROM Students WHERE Id = @id";
        command.Parameters.AddWithValue("@id", studentId);
        command.ExecuteNonQuery();
        RaiseDataChanged();
    }

    /// <summary>Удаляет группу вместе со студентами, занятиями и отметками.</summary>
    public void DeleteGroup(int groupId)
    {
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText =
            "DELETE FROM Groups WHERE Id = @id";
        command.Parameters.AddWithValue("@id", groupId);
        command.ExecuteNonQuery();
        RaiseDataChanged();
    }

    /// <summary>Имя студента по id (для подсказок).</summary>
    public string? GetStudentName(int studentId)
    {
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT FullName FROM Students WHERE Id = @id";
        command.Parameters.AddWithValue("@id", studentId);
        return command.ExecuteScalar() as string;
    }

    private void RaiseDataChanged()
    {
        var dispatcher = System.Windows.Application.Current?.Dispatcher;
        if (dispatcher is not null && !dispatcher.CheckAccess())
        {
            dispatcher.Invoke(() => DataChanged?.Invoke());
            return;
        }
        DataChanged?.Invoke();
    }

    // ------------------------------------------------------------------ группы

    public List<Group> GetGroups()
    {
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT Id, Name, CreatedAt FROM Groups ORDER BY CreatedAt, Id";
        using var reader = command.ExecuteReader();
        var list = new List<Group>();
        while (reader.Read())
        {
            list.Add(new Group
            {
                Id = reader.GetInt32(0),
                Name = reader.GetString(1),
                CreatedAt = reader.GetString(2),
            });
        }
        return list;
    }

    public bool GroupNameExists(string name)
    {
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT COUNT(*) FROM Groups WHERE Name = @name";
        command.Parameters.AddWithValue("@name", name);
        return Convert.ToInt64(command.ExecuteScalar()) > 0;
    }

    public Group AddGroup(string name)
    {
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = "INSERT INTO Groups (Name, CreatedAt) VALUES (@name, @createdAt); SELECT last_insert_rowid();";
        command.Parameters.AddWithValue("@name", name);
        command.Parameters.AddWithValue("@createdAt", DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss"));
        var id = Convert.ToInt32(command.ExecuteScalar());
        RaiseDataChanged();
        return new Group { Id = id, Name = name, CreatedAt = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss") };
    }

    public int CountStudents(int groupId)
    {
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT COUNT(*) FROM Students WHERE GroupId = @id";
        command.Parameters.AddWithValue("@id", groupId);
        return Convert.ToInt32(command.ExecuteScalar());
    }

    // ---------------------------------------------------------------- студенты

    public List<Student> GetStudents(int groupId)
    {
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT Id, GroupId, FullName, QrToken, DeviceId FROM Students WHERE GroupId = @id ORDER BY Id";
        command.Parameters.AddWithValue("@id", groupId);
        using var reader = command.ExecuteReader();
        var list = new List<Student>();
        while (reader.Read())
        {
            list.Add(new Student
            {
                Id = reader.GetInt32(0),
                GroupId = reader.GetInt32(1),
                FullName = reader.GetString(2),
                QrToken = reader.GetString(3),
                DeviceId = reader.IsDBNull(4) ? null : reader.GetString(4),
            });
        }
        return list;
    }

    /// <summary>Студент по коду из QR-кода (единственный вход в приложении) или null.</summary>
    public Student? FindByQrToken(string token)
    {
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT Id, GroupId, FullName, QrToken, DeviceId FROM Students WHERE QrToken = @token LIMIT 1";
        command.Parameters.AddWithValue("@token", token);
        using var reader = command.ExecuteReader();
        if (!reader.Read()) return null;
        return new Student
        {
            Id = reader.GetInt32(0),
            GroupId = reader.GetInt32(1),
            FullName = reader.GetString(2),
            QrToken = reader.GetString(3),
            DeviceId = reader.IsDBNull(4) ? null : reader.GetString(4),
        };
    }

    public void AddStudent(Student student)
    {
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = """
            INSERT INTO Students (GroupId, FullName, QrToken)
            VALUES (@groupId, @fullName, @token);
            SELECT last_insert_rowid();
            """;
        command.Parameters.AddWithValue("@groupId", student.GroupId);
        command.Parameters.AddWithValue("@fullName", student.FullName);
        command.Parameters.AddWithValue("@token", student.QrToken);
        student.Id = Convert.ToInt32(command.ExecuteScalar());
        RaiseDataChanged();
    }

    public void AddStudents(IEnumerable<Student> students)
    {
        using var connection = Open();
        using var transaction = connection.BeginTransaction();
        using var command = connection.CreateCommand();
        command.CommandText = """
            INSERT INTO Students (GroupId, FullName, QrToken)
            VALUES (@groupId, @fullName, @token);
            """;
        var pGroupId = command.Parameters.Add("@groupId", SqliteType.Integer);
        var pFullName = command.Parameters.Add("@fullName", SqliteType.Text);
        var pToken = command.Parameters.Add("@token", SqliteType.Text);

        foreach (var student in students)
        {
            pGroupId.Value = student.GroupId;
            pFullName.Value = student.FullName;
            pToken.Value = student.QrToken;
            command.ExecuteNonQuery();
        }
        transaction.Commit();
        RaiseDataChanged();
    }

    // ---------------------------------------------------------------- занятия

    public int AddLesson(int groupId, string startedAt, string finishedAt)
    {
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = """
            INSERT INTO Lessons (GroupId, StartedAt, FinishedAt)
            VALUES (@groupId, @startedAt, @finishedAt);
            SELECT last_insert_rowid();
            """;
        command.Parameters.AddWithValue("@groupId", groupId);
        command.Parameters.AddWithValue("@startedAt", startedAt);
        command.Parameters.AddWithValue("@finishedAt", finishedAt);
        var id = Convert.ToInt32(command.ExecuteScalar());
        RaiseDataChanged();
        return id;
    }

    public List<Lesson> GetLessons(int groupId)
    {
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = """
            SELECT l.Id, l.GroupId, l.StartedAt, l.FinishedAt,
              (SELECT COUNT(*) FROM Attendance a WHERE a.LessonId = l.Id AND a.Status = 'present') AS Present,
              (SELECT COUNT(*) FROM Students s WHERE s.GroupId = l.GroupId) AS Total
            FROM Lessons l
            WHERE l.GroupId = @id
            ORDER BY l.StartedAt DESC, l.Id DESC
            """;
        command.Parameters.AddWithValue("@id", groupId);
        using var reader = command.ExecuteReader();
        var list = new List<Lesson>();
        while (reader.Read())
        {
            list.Add(new Lesson
            {
                Id = reader.GetInt32(0),
                GroupId = reader.GetInt32(1),
                StartedAt = reader.GetString(2),
                FinishedAt = reader.IsDBNull(3) ? null : reader.GetString(3),
                Present = Convert.ToInt32(reader.GetInt64(4)),
                Total = Convert.ToInt32(reader.GetInt64(5)),
            });
        }
        return list;
    }

    public List<LessonDetail> GetLessonDetails(int lessonId)
    {
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = """
            SELECT s.Id, s.FullName, a.Status
            FROM Students s
            LEFT JOIN Attendance a ON a.StudentId = s.Id AND a.LessonId = @lessonId
            WHERE s.GroupId = (SELECT GroupId FROM Lessons WHERE Id = @lessonId)
            ORDER BY s.Id
            """;
        command.Parameters.AddWithValue("@lessonId", lessonId);
        using var reader = command.ExecuteReader();
        var list = new List<LessonDetail>();
        while (reader.Read())
        {
            list.Add(new LessonDetail
            {
                StudentId = reader.GetInt32(0),
                FullName = reader.GetString(1),
                Present = !reader.IsDBNull(2) && reader.GetString(2) == "present",
            });
        }
        return list;
    }

    public void AddAttendance(int lessonId, IEnumerable<(int StudentId, bool Present, string MarkedAt)> rows)
    {
        using var connection = Open();
        using var transaction = connection.BeginTransaction();
        using var command = connection.CreateCommand();
        command.CommandText = """
            INSERT INTO Attendance (LessonId, StudentId, Status, MarkedAt)
            VALUES (@lessonId, @studentId, @status, @markedAt);
            """;
        var pLessonId = command.Parameters.Add("@lessonId", SqliteType.Integer);
        var pStudentId = command.Parameters.Add("@studentId", SqliteType.Integer);
        var pStatus = command.Parameters.Add("@status", SqliteType.Text);
        var pMarkedAt = command.Parameters.Add("@markedAt", SqliteType.Text);

        foreach (var (studentId, present, markedAt) in rows)
        {
            pLessonId.Value = lessonId;
            pStudentId.Value = studentId;
            pStatus.Value = present ? "present" : "absent";
            pMarkedAt.Value = markedAt;
            command.ExecuteNonQuery();
        }
        transaction.Commit();
        RaiseDataChanged();
    }
}
