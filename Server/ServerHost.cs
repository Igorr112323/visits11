using System.IO;
using System.Security.Cryptography;
using System.Text;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Hosting;
using Microsoft.Data.Sqlite;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;

namespace Visits11.Server;

public sealed record MarkSubmission(string Id, string StudentKey, string DeviceId, string OccurredAt);
public sealed record PendingMark(string Id, string StudentKey, string DeviceId, string OccurredAt);
public sealed record MarkAck(string Id);

public sealed class ServerHost : IAsyncDisposable
{
    private readonly string _databasePath;
    private readonly string _syncKeyPath;
    private readonly int _port;
    private WebApplication? _app;
    private string _syncKey = string.Empty;

    public ServerHost(string directory, int port = 8091)
    {
        Directory.CreateDirectory(directory);
        _databasePath = Path.Combine(directory, "server.db");
        _syncKeyPath = Path.Combine(directory, "server.key");
        _port = port;
    }

    public string SyncKey => _syncKey;
    public string DatabasePath => _databasePath;
    public int Port => _port;
    public bool IsRunning => _app is not null;

    public async Task StartAsync(CancellationToken cancellationToken = default)
    {
        if (_app is not null) return;
        _syncKey = LoadOrCreateKey();
        InitializeDatabase();
        var builder = WebApplication.CreateBuilder(new WebApplicationOptions { Args = Array.Empty<string>() });
        builder.WebHost.UseUrls($"http://0.0.0.0:{_port}");
        builder.Logging.ClearProviders();
        builder.Services.ConfigureHttpJsonOptions(options => options.SerializerOptions.PropertyNameCaseInsensitive = true);
        var app = builder.Build();

        app.MapGet("/api/health", () => Results.Json(new { ok = true, app = "kubgau-attendance-server" }));
        app.MapPost("/api/marks", async (HttpContext context) =>
        {
            MarkSubmission? mark;
            try
            {
                mark = await context.Request.ReadFromJsonAsync<MarkSubmission>(cancellationToken: context.RequestAborted);
            }
            catch
            {
                return Results.BadRequest(new { ok = false, error = "invalid-json" });
            }
            if (mark is null
                || !Guid.TryParse(mark.Id, out _)
                || !Guid.TryParse(mark.StudentKey, out _)
                || string.IsNullOrWhiteSpace(mark.DeviceId)
                || mark.DeviceId.Length > 256
                || !DateTimeOffset.TryParse(mark.OccurredAt, out var occurredAt))
            {
                return Results.BadRequest(new { ok = false, error = "invalid-mark" });
            }
            var inserted = InsertMark(mark with { OccurredAt = occurredAt.ToUniversalTime().ToString("O") });
            return Results.Json(new { ok = true, duplicate = !inserted });
        });
        app.MapGet("/api/marks/pending", (HttpContext context) =>
        {
            if (!IsAuthorized(context)) return Results.Unauthorized();
            return Results.Json(GetPending());
        });
        app.MapPost("/api/marks/ack", async (HttpContext context) =>
        {
            if (!IsAuthorized(context)) return Results.Unauthorized();
            MarkAck? ack;
            try
            {
                ack = await context.Request.ReadFromJsonAsync<MarkAck>(cancellationToken: context.RequestAborted);
            }
            catch
            {
                return Results.BadRequest(new { ok = false });
            }
            if (ack is null || !Guid.TryParse(ack.Id, out _)) return Results.BadRequest(new { ok = false });
            Acknowledge(ack.Id);
            return Results.Json(new { ok = true });
        });

        _app = app;
        try
        {
            await app.StartAsync(cancellationToken);
        }
        catch
        {
            _app = null;
            await app.DisposeAsync();
            throw;
        }
    }

    public int GetPendingCount()
    {
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT COUNT(*) FROM Marks WHERE Acknowledged = 0";
        return Convert.ToInt32(command.ExecuteScalar());
    }

    public async Task StopAsync()
    {
        var app = _app;
        _app = null;
        if (app is null) return;
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(3));
        try
        {
            await app.StopAsync(timeout.Token);
        }
        catch (OperationCanceledException)
        {
        }
        await app.DisposeAsync();
    }

    public ValueTask DisposeAsync() => new(StopAsync());

    private string LoadOrCreateKey()
    {
        if (File.Exists(_syncKeyPath))
        {
            var value = File.ReadAllText(_syncKeyPath).Trim();
            if (value.Length >= 32) return value;
        }
        var key = Convert.ToHexString(RandomNumberGenerator.GetBytes(32));
        File.WriteAllText(_syncKeyPath, key, Encoding.ASCII);
        return key;
    }

    private void InitializeDatabase()
    {
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = "PRAGMA journal_mode=WAL; CREATE TABLE IF NOT EXISTS Marks (Id TEXT PRIMARY KEY, StudentKey TEXT NOT NULL, DeviceId TEXT NOT NULL, OccurredAt TEXT NOT NULL, ReceivedAt TEXT NOT NULL, Acknowledged INTEGER NOT NULL DEFAULT 0); CREATE INDEX IF NOT EXISTS IX_Marks_Acknowledged_ReceivedAt ON Marks(Acknowledged, ReceivedAt);";
        command.ExecuteNonQuery();
    }

    private SqliteConnection Open()
    {
        var connection = new SqliteConnection(new SqliteConnectionStringBuilder
        {
            DataSource = _databasePath,
            Mode = SqliteOpenMode.ReadWriteCreate,
            Pooling = true
        }.ToString());
        connection.Open();
        return connection;
    }

    private bool InsertMark(MarkSubmission mark)
    {
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = "INSERT OR IGNORE INTO Marks (Id, StudentKey, DeviceId, OccurredAt, ReceivedAt, Acknowledged) VALUES (@id, @studentKey, @deviceId, @occurredAt, @receivedAt, 0)";
        command.Parameters.AddWithValue("@id", mark.Id);
        command.Parameters.AddWithValue("@studentKey", mark.StudentKey);
        command.Parameters.AddWithValue("@deviceId", mark.DeviceId);
        command.Parameters.AddWithValue("@occurredAt", mark.OccurredAt);
        command.Parameters.AddWithValue("@receivedAt", DateTimeOffset.UtcNow.ToString("O"));
        return command.ExecuteNonQuery() == 1;
    }

    private List<PendingMark> GetPending()
    {
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT Id, StudentKey, DeviceId, OccurredAt FROM Marks WHERE Acknowledged = 0 ORDER BY ReceivedAt LIMIT 500";
        using var reader = command.ExecuteReader();
        var marks = new List<PendingMark>();
        while (reader.Read()) marks.Add(new PendingMark(reader.GetString(0), reader.GetString(1), reader.GetString(2), reader.GetString(3)));
        return marks;
    }

    private void Acknowledge(string id)
    {
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = "UPDATE Marks SET Acknowledged = 1 WHERE Id = @id";
        command.Parameters.AddWithValue("@id", id);
        command.ExecuteNonQuery();
    }

    private bool IsAuthorized(HttpContext context)
    {
        if (!context.Request.Headers.TryGetValue("X-Teacher-Key", out var header)) return false;
        var incoming = Encoding.UTF8.GetBytes(header.ToString());
        var expected = Encoding.UTF8.GetBytes(_syncKey);
        return incoming.Length == expected.Length && CryptographicOperations.FixedTimeEquals(incoming, expected);
    }
}
