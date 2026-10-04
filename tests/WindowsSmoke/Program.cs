using Visits11.Models;
using Visits11.Services;

var root = Path.Combine(Path.GetTempPath(), "visits11-attendance-tests", Guid.NewGuid().ToString("N"));
Directory.CreateDirectory(root);
try
{
    var database = new DatabaseService(Path.Combine(root, "journal.db"));
    database.Initialize();
    var group = database.AddGroup("Тестовая группа");
    var student = new Student { FullName = "Иванов Иван Иванович", Login = "test-student", Password = "test", GroupId = group.Id };
    database.AddStudent(student);
    var savedStudent = database.GetStudents(group.Id).Single();
    Assert(Guid.TryParseExact(savedStudent.QrId, "N", out _), "QR ID was not saved as a stable identifier");
    Assert(database.FindByQrId(savedStudent.QrId)?.Id == savedStudent.Id, "QR ID lookup did not find the student");

    var payload = StudentQrPayload.Create(savedStudent.QrId, savedStudent.FullName, "http://192.168.1.10:8091");
    Assert(StudentQrPayload.TryParse(payload, out var parsed), "Generated QR payload did not parse");
    Assert(parsed!.StudentKey == savedStudent.QrId, "QR payload contains the wrong student ID");
    Assert(parsed.ServerUrl == "http://192.168.1.10:8091", "QR payload contains the wrong server address");

    database.SetDeviceId(savedStudent.Id, "registered-device");
    Assert(database.FindByQrId(savedStudent.QrId)?.DeviceId == "registered-device", "Phone ID registration failed");
    database.ResetDeviceId(savedStudent.Id);
    Assert(database.FindByQrId(savedStudent.QrId)?.DeviceId is null, "Phone ID reset failed");

    database.SetDeviceId(savedStudent.Id, "registered-device");
    var markedAt = DateTime.Now;
    var startedAt = markedAt.ToString("yyyy-MM-dd HH:mm:ss");
    var finishedAt = markedAt.AddMinutes(10).ToString("yyyy-MM-dd HH:mm:ss");
    var lessonId = database.AddLesson(group.Id, startedAt, finishedAt);
    database.AddAttendance(lessonId, new[] { (savedStudent.Id, true, startedAt, true, "different-device") });
    var detail = database.GetLessonDetails(lessonId).Single();
    Assert(detail.DeviceMismatch, "A mismatched attendance device was not flagged");
    Assert(detail.Present, "A device mismatch incorrectly rejected attendance");
    Assert(database.FindLessonIdAt(markedAt, group.Id) == lessonId, "Active lesson lookup failed");

    Console.WriteLine("PASS: reusable QR, student lookup, device reset, and mismatch-with-attendance");
}
finally
{
    try { Directory.Delete(root, true); } catch { }
}

static void Assert(bool condition, string message)
{
    if (!condition) throw new InvalidOperationException(message);
}
