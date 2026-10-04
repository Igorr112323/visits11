namespace Visits11.Models;

public sealed class Lesson
{
    public int Id { get; set; }
    public int GroupId { get; set; }
    public string StartedAt { get; set; } = string.Empty;
    public string? FinishedAt { get; set; }
    /// <summary>Количество присутствовавших.</summary>
    public int Present { get; set; }
    /// <summary>Всего студентов в группе на момент занятия.</summary>
    public int Total { get; set; }

    public DateTime StartedAtUtc => DateTime.TryParse(StartedAt, out var d) ? d : DateTime.MinValue;
}

public sealed class LessonDetail
{
    public int StudentId { get; set; }
    public string FullName { get; set; } = string.Empty;
    public bool Present { get; set; }
    public bool DeviceMismatch { get; set; }
}
