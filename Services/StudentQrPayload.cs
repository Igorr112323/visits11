using System.Text.Json;

namespace Visits11.Services;

public sealed record StudentQrPayload(int Version, string StudentKey, string FullName, string ServerUrl)
{
    public static string Create(string studentKey, string fullName, string serverUrl)
        => JsonSerializer.Serialize(new StudentQrPayload(1, studentKey, fullName, serverUrl), new JsonSerializerOptions(JsonSerializerDefaults.Web));

    public static bool TryParse(string? value, out StudentQrPayload? payload)
    {
        payload = null;
        if (string.IsNullOrWhiteSpace(value) || value.Length > 4096) return false;
        try
        {
            payload = JsonSerializer.Deserialize<StudentQrPayload>(value, new JsonSerializerOptions(JsonSerializerDefaults.Web));
            return payload is { Version: 1 }
                && Guid.TryParseExact(payload.StudentKey, "N", out _)
                && Uri.TryCreate(payload.ServerUrl, UriKind.Absolute, out var uri)
                && (uri.Scheme == Uri.UriSchemeHttp || uri.Scheme == Uri.UriSchemeHttps)
                && !string.IsNullOrWhiteSpace(payload.FullName);
        }
        catch (JsonException)
        {
            return false;
        }
    }
}
