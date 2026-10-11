namespace Visits11.Models;

public sealed class Student
{
    public int Id { get; set; }
    public int GroupId { get; set; }
    public string FullName { get; set; } = string.Empty;

    /// <summary>
    /// Постоянный код студента из его QR-кода — заменяет логин и пароль:
    /// приложение студента получает его, отсканировав QR (камерой или из галереи).
    /// </summary>
    public string QrToken { get; set; } = string.Empty;

    /// <summary>Телефон, к которому привязан студент (null — ещё не отмеaчался ни с одного).</summary>
    public string? DeviceId { get; set; }
}
