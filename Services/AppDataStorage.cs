using System.IO;
using Microsoft.Data.Sqlite;

namespace Visits11.Services;

/// <summary>Writable user data lives outside the single-file executable's directory.</summary>
public static class AppDataStorage
{
#if PHONE_TERMINAL
    private const string Terminal = "Phone";
#else
    private const string Terminal = "ESP32";
#endif
    public static string DirectoryPath { get; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Visits11", Terminal);

    public static void Prepare(string executableDirectory, string dataDirectory)
    {
        Directory.CreateDirectory(dataDirectory);
        string destination = Path.Combine(dataDirectory, "attendance.db");
        string legacy = Path.Combine(executableDirectory, "attendance.db");
        if (!File.Exists(destination) && File.Exists(legacy))
        {
            // SQLite backup includes committed WAL data; File.Copy of just .db can lose attendance.
            // Publish atomically, never overwrite an existing user database and never delete the original.
            string staging = Path.Combine(dataDirectory, $"migration-{Guid.NewGuid():N}.db");
            try
            {
                using (var source = new SqliteConnection(new SqliteConnectionStringBuilder {
                    DataSource = legacy, Mode = SqliteOpenMode.ReadOnly, Pooling = false
                }.ToString()))
                using (var target = new SqliteConnection(new SqliteConnectionStringBuilder {
                    DataSource = staging, Mode = SqliteOpenMode.ReadWriteCreate, Pooling = false
                }.ToString()))
                {
                    source.Open();
                    target.Open();
                    source.BackupDatabase(target);
                    using var check = target.CreateCommand();
                    check.CommandText = "PRAGMA quick_check;";
                    if (!string.Equals(check.ExecuteScalar()?.ToString(), "ok", StringComparison.Ordinal))
                        throw new IOException("Старая база повреждена. Автоматический перенос остановлен.");
                    // Ensure all copied data is inside the staging DB before the atomic rename.
                    using var checkpoint = target.CreateCommand();
                    checkpoint.CommandText = "PRAGMA journal_mode=DELETE;";
                    checkpoint.ExecuteScalar();
                }
                File.Move(staging, destination); // overwrite is intentionally false
            }
            finally
            {
                foreach (string suffix in new[] { "", "-wal", "-shm", "-journal" })
                    if (File.Exists(staging + suffix)) File.Delete(staging + suffix);
            }
        }
        foreach (string filename in new[] { "settings.json", "bluetooth-power.txt" })
        {
            string oldPath = Path.Combine(executableDirectory, filename);
            string newPath = Path.Combine(dataDirectory, filename);
            if (!File.Exists(newPath) && File.Exists(oldPath)) File.Copy(oldPath, newPath);
        }
    }
}
