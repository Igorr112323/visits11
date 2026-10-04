namespace Visits11.Models;

public sealed class Student
{
    public int Id { get; set; }
    public int GroupId { get; set; }
    public string FullName { get; set; } = string.Empty;
    public string Login { get; set; } = string.Empty;
    public string Password { get; set; } = string.Empty;
    public string? PhoneId { get; set; }
    public string? DeviceId { get; set; }
    public string QrId { get; set; } = string.Empty;
}
