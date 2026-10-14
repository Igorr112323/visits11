using Microsoft.Data.Sqlite;
using Visits11.Services;

static void Assert(bool condition, string message) { if (!condition) throw new Exception(message); }
static void Execute(SqliteConnection connection, string sql) {
    using var command = connection.CreateCommand(); command.CommandText = sql; command.ExecuteNonQuery();
}
static long Count(string path) {
    using var connection = new SqliteConnection($"Data Source={path};Pooling=False"); connection.Open();
    using var command = connection.CreateCommand(); command.CommandText = "SELECT COUNT(*) FROM Attendance";
    return (long)command.ExecuteScalar()!;
}
var root = Path.Combine(Path.GetTempPath(), "Visits11-storage-" + Guid.NewGuid().ToString("N"));
Directory.CreateDirectory(root);
try {
    var exe = Path.Combine(root, "exe"); Directory.CreateDirectory(exe);
    var data = Path.Combine(root, "user-data");
    AppDataStorage.Prepare(exe, data);
    Assert(!Directory.EnumerateFileSystemEntries(exe).Any(), "Fresh launch writes no files beside EXE");
    Assert(Directory.Exists(data), "User data directory created automatically");
    var legacyDb = Path.Combine(exe, "attendance.db");
    using (var legacy = new SqliteConnection($"Data Source={legacyDb};Pooling=False")) {
        legacy.Open();
        Execute(legacy, "PRAGMA journal_mode=WAL; PRAGMA wal_autocheckpoint=0; CREATE TABLE Attendance(Id INTEGER PRIMARY KEY); INSERT INTO Attendance VALUES (1);");
        File.WriteAllText(Path.Combine(exe, "settings.json"), "{\"Theme\":\"dark\"}");
        File.WriteAllText(Path.Combine(exe, "bluetooth-power.txt"), "2");
        AppDataStorage.Prepare(exe, data);
        Assert(Count(Path.Combine(data, "attendance.db")) == 1, "Migration includes committed WAL rows");
        Assert(File.Exists(legacyDb), "Original retained");
        Assert(File.ReadAllText(Path.Combine(data, "bluetooth-power.txt")) == "2", "Power migrated");
        Assert(File.ReadAllText(Path.Combine(data, "settings.json")).Contains("dark"), "Theme migrated");
        Execute(legacy, "INSERT INTO Attendance VALUES (2);");
        File.WriteAllText(Path.Combine(data, "bluetooth-power.txt"), "3");
        AppDataStorage.Prepare(exe, data);
        Assert(Count(Path.Combine(data, "attendance.db")) == 1, "Existing destination never overwritten");
        Assert(File.ReadAllText(Path.Combine(data, "bluetooth-power.txt")) == "3", "Existing settings preserved");
    }
    var moved = Path.Combine(root, "downloaded-again"); Directory.CreateDirectory(moved);
    AppDataStorage.Prepare(moved, data);
    Assert(Count(Path.Combine(data, "attendance.db")) == 1, "Moving EXE retains data");
    Assert(!Directory.EnumerateFileSystemEntries(moved).Any(), "No sidecars at new EXE path");
    var corrupt = Path.Combine(root, "corrupt"); Directory.CreateDirectory(corrupt);
    File.WriteAllText(Path.Combine(corrupt, "attendance.db"), "not a database");
    var failedData = Path.Combine(root, "failed-data");
    bool failed = false;
    try { AppDataStorage.Prepare(corrupt, failedData); } catch (SqliteException) { failed = true; }
    Assert(failed && !File.Exists(Path.Combine(failedData, "attendance.db")), "Corrupt migration fails closed");
    Assert(!Directory.EnumerateFiles(failedData, "migration-*").Any(), "Staging files cleaned");
    Console.WriteLine("PASS: fresh launch, WAL migration, original retained, settings, no overwrite, moved EXE, corrupt DB");
} finally { Directory.Delete(root, true); }
