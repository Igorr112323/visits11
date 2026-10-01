using DocumentFormat.OpenXml;
using DocumentFormat.OpenXml.Packaging;
using DocumentFormat.OpenXml.Wordprocessing;
using Visits11.Models;

namespace Visits11.Services;

/// <summary>
/// Импорт и экспорт списков студентов / журнала в формате Word (.docx).
/// </summary>
public sealed class WordService
{
    // ------------------------------------------------------------------ импорт

    /// <summary>
    /// Берёт из первой таблицы документа колонки № и ФИО.
    /// Строки без числа в первой колонке (заголовки) пропускаются.
    /// </summary>
    public List<string> ImportStudents(string path)
    {
        var names = new List<string>();
        using var document = WordprocessingDocument.Open(path, false);
        var table = document.MainDocumentPart?.Document?.Body?.Descendants<Table>().FirstOrDefault()
                    ?? throw new InvalidDataException("В документе нет таблицы.");

        foreach (var row in table.Elements<TableRow>())
        {
            var cells = row.Elements<TableCell>().ToList();
            if (cells.Count < 2) continue;

            var number = cells[0].InnerText.Trim();
            var name = string.Join(' ', cells[1].InnerText.Split(' ', StringSplitOptions.RemoveEmptyEntries));
            if (name.Length == 0) continue;
            if (!int.TryParse(number, out _)) continue; // пропускаем шапку и служебные строки

            if (!names.Contains(name, StringComparer.OrdinalIgnoreCase))
                names.Add(name);
        }
        return names;
    }

    // ----------------------------------------------------------------- экспорт

    public void ExportStudents(string path, IReadOnlyList<Student> students)
    {
        using var document = WordprocessingDocument.Create(path, WordprocessingDocumentType.Document);
        var mainPart = document.AddMainPart();
        var doc = new Document();
        var body = new Body();
        doc.Append(body);
        mainPart.Document = doc;

        var table = CreateTable("№", "ФИО", "Логин", "Пароль");
        for (var i = 0; i < students.Count; i++)
        {
            table.Append(CreateRow(
                new[] { (i + 1).ToString(), students[i].FullName, students[i].Login, students[i].Password },
                new[] { 2, 3 }));
        }
        body.Append(table);
        document.Save();
    }

    public void ExportLesson(string path, string title, IReadOnlyList<LessonDetail> rows)
    {
        using var document = WordprocessingDocument.Create(path, WordprocessingDocumentType.Document);
        var mainPart = document.AddMainPart();
        var doc = new Document();
        var body = new Body();
        doc.Append(body);
        mainPart.Document = doc;

        body.Append(CreateTitleParagraph(title));

        var table = CreateTable("№", "ФИО", "Статус");
        for (var i = 0; i < rows.Count; i++)
        {
            table.Append(CreateRow(
                new[] { (i + 1).ToString(), rows[i].FullName, rows[i].Present ? "Присутствовал" : "Отсутствовал" },
                Array.Empty<int>()));
        }
        body.Append(table);
        document.Save();
    }

    // -------------------------------------------------------------- построение

    private static Paragraph CreateTitleParagraph(string title)
    {
        var run = new Run(
            new RunProperties(new Bold(), new FontSize { Val = "28" }), // 14pt
            new Text(title));
        return new Paragraph(run);
    }

    private static Table CreateTable(params string[] headers)
    {
        var table = new Table();

        var borders = new TableBorders(
            new TopBorder { Val = BorderValues.Single, Size = 4, Color = "AAAAAA" },
            new BottomBorder { Val = BorderValues.Single, Size = 4, Color = "AAAAAA" },
            new LeftBorder { Val = BorderValues.Single, Size = 4, Color = "AAAAAA" },
            new RightBorder { Val = BorderValues.Single, Size = 4, Color = "AAAAAA" },
            new InsideHorizontalBorder { Val = BorderValues.Single, Size = 4, Color = "AAAAAA" },
            new InsideVerticalBorder { Val = BorderValues.Single, Size = 4, Color = "AAAAAA" });

        var properties = new TableProperties(
            new TableWidth { Type = TableWidthUnitValues.Pct, Width = "5000" }, // 100%
            borders);
        table.AppendChild(properties);

        var headerRow = new TableRow();
        foreach (var header in headers)
        {
            var run = new Run(
                new RunProperties(new Bold(), new Color { Val = "FFFFFF" }, new FontSize { Val = "22" }),
                new Text(header));
            var cell = new TableCell(
                new TableCellProperties(new Shading { Val = ShadingPatternValues.Clear, Fill = "6366F1" }),
                new Paragraph(run));
            headerRow.AppendChild(cell);
        }
        headerRow.TableHeader = new TableHeader();
        table.AppendChild(headerRow);
        return table;
    }

    private static TableRow CreateRow(string[] values, int[] monoIndexes)
    {
        var row = new TableRow();
        for (var i = 0; i < values.Length; i++)
        {
            var runProperties = new RunProperties(new FontSize { Val = "22" }); // 11pt
            if (Array.IndexOf(monoIndexes, i) >= 0)
            {
                runProperties.Append(new RunFonts { Ascii = "Consolas", HighAnsi = "Consolas" });
            }

            var run = new Run(runProperties, new Text(values[i]));
            row.AppendChild(new TableCell(new Paragraph(run)));
        }
        return row;
    }
}
