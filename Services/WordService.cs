using System.Globalization;
using System.IO;
using System.Text;
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
    /// Читает список студентов из таблицы документа: в первой колонке — номер,
    /// во второй — ФИО, остальные колонки не нужны. Название группы над таблицей,
    /// шапка, итоги и пустые строки пропускаются: студент — это строка, у которой
    /// в первой колонке номер. Если таблиц несколько (например, отдельная таблица-шапка
    /// с группой и таблица со списком), берётся та, где нашлось больше студентов.
    /// </summary>
    public List<string> ImportStudents(string path)
    {
        // FileShare.ReadWrite — чтобы файл читался и тогда, когда он сейчас открыт в Word
        using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
        using var document = WordprocessingDocument.Open(stream, false);

        var mainPart = document.MainDocumentPart;
        var tables = mainPart?.Document?.Body?.Descendants<Table>().ToList();
        if (tables is null || tables.Count == 0)
            throw new InvalidDataException("В документе нет таблицы.");

        var students = new List<string>();
        foreach (var table in tables)
        {
            var names = ReadStudents(table, mainPart);
            if (names.Count > students.Count) students = names;
        }
        return students;
    }

    /// <summary>Студенты одной таблицы: номер в 1-й колонке, ФИО во 2-й.</summary>
    private static List<string> ReadStudents(Table table, MainDocumentPart? mainPart)
    {
        var names = new List<string>();
        foreach (var row in table.Descendants<TableRow>())
        {
            // строки вложенных таблиц разберём, когда дойдём до самой вложенной таблицы
            if (row.Ancestors<Table>().FirstOrDefault() != table) continue;

            var cells = row.Descendants<TableCell>()
                .Where(cell => cell.Ancestors<TableRow>().FirstOrDefault() == row)
                .ToList();
            if (cells.Count < 2) continue;

            // во 2-й колонке ФИО — в нём есть буквы (строка «1 | 2 | 3» с номерами колонок не студент)
            var name = CellText(cells[1]);
            if (!name.Any(char.IsLetter)) continue;

            // в 1-й колонке номер — так отсеиваются шапка, название группы, «Итого» и пустые строки
            if (!HasNumber(cells[0], mainPart)) continue;

            if (!names.Contains(name, StringComparer.OrdinalIgnoreCase))
                names.Add(name);
        }
        return names;
    }

    /// <summary>В ячейке номер строки: «1», «1.», «1)», «№ 1» или номер, который ставит сам Word.</summary>
    private static bool HasNumber(TableCell cell, MainDocumentPart? mainPart)
    {
        var text = CellText(cell);
        if (text.Length > 0)
        {
            var digits = text.TrimStart('№').Trim().TrimEnd('.', ')').Trim();
            return digits.Length > 0 && digits.All(char.IsDigit);
        }

        // текста нет — номер мог поставить сам Word (автонумерация списка в ячейке)
        return OwnParagraphs(cell).Any(paragraph => IsAutoNumbered(paragraph, mainPart));
    }

    /// <summary>Абзац — пункт нумерованного списка (нумерация задана в самом абзаце или в его стиле).</summary>
    private static bool IsAutoNumbered(Paragraph paragraph, MainDocumentPart? mainPart)
    {
        var numbering = paragraph.ParagraphProperties?.NumberingProperties;
        if (numbering is not null)
            return numbering.NumberingId?.Val?.Value != 0; // numId = 0 — нумерация отключена

        var styles = mainPart?.StyleDefinitionsPart?.Styles;
        var styleId = paragraph.ParagraphProperties?.ParagraphStyleId?.Val?.Value;
        for (var depth = 0; styles is not null && styleId is not null && depth < 10; depth++)
        {
            var style = styles.Elements<Style>().FirstOrDefault(s => s.StyleId?.Value == styleId);
            if (style is null) break;

            var styleNumbering = style.StyleParagraphProperties?.NumberingProperties;
            if (styleNumbering is not null)
                return styleNumbering.NumberingId?.Val?.Value != 0;

            styleId = style.BasedOn?.Val?.Value; // нумерацию стиль может унаследовать от родительского
        }
        return false;
    }

    /// <summary>Абзацы самой ячейки (без абзацев вложенных в неё таблиц).</summary>
    private static IEnumerable<Paragraph> OwnParagraphs(TableCell cell)
        => cell.Descendants<Paragraph>().Where(paragraph => paragraph.Ancestors<TableCell>().FirstOrDefault() == cell);

    /// <summary>
    /// Видимый текст ячейки одной строкой: абзацы, переносы строк и табуляции дают пробел,
    /// неразрывные пробелы и невидимые символы приводятся к обычному виду. Коды полей
    /// и удалённые правки (то, чего Word не показывает) в текст не попадают.
    /// </summary>
    private static string CellText(TableCell cell)
    {
        var raw = new StringBuilder();
        foreach (var paragraph in OwnParagraphs(cell))
        {
            foreach (var element in paragraph.Descendants())
            {
                switch (element)
                {
                    case Text part:
                        raw.Append(part.Text);
                        break;
                    case TabChar:
                    case Break:
                    case CarriageReturn:
                        raw.Append(' ');
                        break;
                    case NoBreakHyphen:
                        raw.Append('-');
                        break;
                }
            }
            raw.Append(' ');
        }

        var text = new StringBuilder(raw.Length);
        var space = false;
        foreach (var ch in raw.ToString())
        {
            if (char.IsWhiteSpace(ch) || ch == '\u200B') // обычные, неразрывные и нулевой ширины пробелы
            {
                space = text.Length > 0;
                continue;
            }

            var category = char.GetUnicodeCategory(ch);
            if (category is UnicodeCategory.Format or UnicodeCategory.Control) continue; // мягкие переносы и т.п.

            if (space) text.Append(' ');
            space = false;
            text.Append(ch);
        }
        return text.ToString();
    }

    // ----------------------------------------------------------------- экспорт

    public void ExportStudents(string path, IReadOnlyList<Student> students)
    {
        using var document = WordprocessingDocument.Create(path, WordprocessingDocumentType.Document);
        var mainPart = document.AddMainDocumentPart();
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
        var mainPart = document.AddMainDocumentPart();
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
        headerRow.AppendChild(new TableHeader());
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
