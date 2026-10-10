package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.service.ebook.QualityReport.Category;
import com.ebookwriter.SaaS.service.ebook.QualityReport.Finding;
import com.ebookwriter.SaaS.service.ebook.QualityReport.Severity;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Points 2–7 on real renders of three books of different genres — a technical
 * book (code in two languages, terminal output), a cookbook (numbered steps
 * with boxes inside them, line-based ingredient lists) and a poetry collection
 * (line breaks and indentation as content). Every assertion is the same for
 * all three; nothing under test is told the genre.
 */
class BookQualityAcrossGenresTest {

    static List<GenreFixtures.Book> books() {
        return GenreFixtures.all();
    }

    private static final PdfGenerationService PDF =
            new PdfGenerationService(null, null, null, new EbookHtmlBuilder(), null, null);

    record Rendered(PdfGenerationService.BookRender render, QualityReport report, int pages) {
        Document doc() {
            return render.doc();
        }

        List<Finding> findings(Category category) {
            return report.of(category);
        }
    }

    private static Rendered render(GenreFixtures.Book book) {
        PdfGenerationService.BookRender r = PDF.renderBook(book.ebook(), book.chapters(), List.of());
        int pages;
        try (PDDocument d = PDDocument.load(r.rendered().pdf())) {
            pages = d.getNumberOfPages();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        QualityReport report = BookQualityInspector.inspect(book.ebook(), book.chapters(), r.context(), r.doc(),
                r.rendered().layout(), pages);
        return new Rendered(r, report, pages);
    }

    private static final Pattern DIRECTIVE = Pattern.compile("^[ \\t]*:::[ \\t]*([a-zA-Z][\\w-]*)[ \\t]*(.*)$",
            Pattern.MULTILINE);

    // ---- Point 2: structural blocks ---------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("books")
    void everyBoxIsPrintedOnceWhereItWasWritten_noOrphanNumbers(GenreFixtures.Book fixture) {
        GenreFixtures.Book book = fixture.copy();
        Rendered r = render(book);
        Element body = r.doc().body();

        assertFalse(body.text().contains("CMPBLOCKPLACEHOLDER"), "no internal marker is printed");
        for (Element p : body.select(".chapter-body p")) {
            assertFalse(p.text().strip().matches("\\d{1,3}[.)]?"), "no number standing alone: " + p.outerHtml());
        }
        assertTrue(body.select("ol[start]").isEmpty(), "no numbered list was split and restarted");

        // Every titled box written in the book is printed exactly once.
        int boxes = 0;
        for (EbookChapter c : book.chapters()) {
            Matcher m = DIRECTIVE.matcher(c.getContent());
            while (m.find()) {
                if (EbookContentRenderer.VERBATIM_TYPES.contains(m.group(1))) {
                    continue;
                }
                boxes++;
                String title = m.group(2).strip();
                if (!title.isEmpty()) {
                    long printed = body.select(".cmp-title").stream().filter(t -> t.text().equals(title)).count();
                    assertEquals(1, printed, "box \"" + title + "\" printed once");
                }
            }
        }
        // The one box written twice (in two chapters) is printed once; all others are there.
        long duplicatesRemoved = r.render().context().notes().stream()
                .filter(n -> n.type() == RenderContext.NoteType.DUPLICATE_REMOVED).count();
        assertEquals(1, duplicatesRemoved);
        assertEquals(boxes - 1, body.select("div.cmp").size());
        String shared = EbookContentRenderer.fingerprint(book.sharedBox().replaceAll(":::\\w*", ""));
        long sharedPrinted = body.select("div.cmp").stream()
                .filter(c -> EbookContentRenderer.fingerprint(c.text()).contains(shared)).count();
        assertEquals(1, sharedPrinted, "the shared box is in the book once");
        assertTrue(r.findings(Category.DUPLICATE_BLOCK).stream()
                .anyMatch(f -> f.severity() == Severity.WARNING && f.chapter() == 2 && f.page() != null));

        // A box written inside a numbered step stays inside that step.
        boolean nestedInSource = book.chapters().stream()
                .anyMatch(c -> Pattern.compile("^ {2,}:::\\w", Pattern.MULTILINE).matcher(c.getContent()).find());
        if (nestedInSource) {
            Element li = body.selectFirst("li:has(div.cmp)");
            assertNotNull(li, "the nested box is inside its list item");
            assertTrue(li.parent().children().size() >= 3, "the steps around the box stay one list");
            assertTrue(li.nextElementSibling() != null, "and the list continues after it");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("books")
    void aHeadingWithNothingUnderItIsNotPrintedAndIsReported(GenreFixtures.Book fixture) {
        GenreFixtures.Book book = fixture.copy();
        EbookChapter first = book.chapter(1);
        // An empty section, directly followed by the next section.
        first.setContent(first.getContent()
                + "\n\n## Nothing Here Yet\n\n## Coda\n\nOne more line closes this part of the book.");
        Rendered r = render(book);

        assertFalse(r.doc().body().text().contains("Nothing Here Yet"));
        assertTrue(r.findings(Category.EMPTY_HEADING).stream()
                .anyMatch(f -> f.chapter() == 1 && "Nothing Here Yet".equals(f.excerpt()) && f.page() != null));
        assertTrue(r.findings(Category.EMPTY_HEADING).stream().noneMatch(f -> f.severity() == Severity.ERROR),
                "nothing empty reaches the PDF");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("books")
    void recurringSectionsAreInEveryChapterOrInNone(GenreFixtures.Book fixture) {
        GenreFixtures.Book clean = fixture.copy();
        assertTrue(render(clean).findings(Category.TEMPLATE_MISMATCH).isEmpty(), "the clean book follows its template");

        // A section the template does not have, in two of the three chapters: random, not a template.
        GenreFixtures.Book random = fixture.copy();
        for (int n : new int[]{1, 2}) {
            EbookChapter c = random.chapter(n);
            c.setContent(c.getContent() + "\n\n## Review Questions\n\nWhat did this chapter change for you?");
        }
        assertTrue(render(random).findings(Category.TEMPLATE_MISMATCH).stream()
                .anyMatch(f -> "Review Questions".equals(f.excerpt()) && f.message().contains("[3]")));

        // A template section missing from one chapter.
        BookTemplate template = BookTemplate.fromJson(fixture.ebook().getChapterTemplateJson());
        if (!template.isEmpty()) {
            GenreFixtures.Book missing = fixture.copy();
            String heading = "## " + template.sections().get(0).heading();
            EbookChapter c = missing.chapter(2);
            c.setContent(c.getContent().replace(heading, "## Something Else"));
            assertTrue(render(missing).findings(Category.TEMPLATE_MISMATCH).stream()
                    .anyMatch(f -> f.chapter() != null && f.chapter() == 2 && f.page() != null));
        }
    }

    // ---- Point 3: internal fields never reach the reader ---------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("books")
    void onlyReaderFieldsArePrinted(GenreFixtures.Book fixture) {
        GenreFixtures.Book book = fixture.copy();
        Rendered r = render(book);
        String printed = EbookContentRenderer.fingerprint(r.doc().body().text());
        for (EbookChapter c : book.chapters()) {
            String brief = EbookContentRenderer.fingerprint(c.getDescription());
            assertFalse(printed.contains(brief.substring(0, 40)), "brief of chapter " + c.getChapterNumber() + " printed");
            assertFalse(printed.contains(EbookContentRenderer.fingerprint(c.getSummary())), "summary printed");
        }
        List<String> intros = r.doc().select(".chapter-intro, .opener-statement").eachText();
        assertEquals(book.chapters().stream().map(EbookChapter::getReaderSubtitle).toList(), intros);
        assertTrue(r.findings(Category.INTERNAL_LEAK).isEmpty());
        assertTrue(r.findings(Category.TRUNCATED_READER_TEXT).isEmpty());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("books")
    void leakedOrCutReaderTextIsReported_andTooLongTextIsDroppedNotCut(GenreFixtures.Book fixture) {
        GenreFixtures.Book book = fixture.copy();
        book.chapter(1).setReaderSubtitle(book.chapter(1).getDescription()); // a brief pasted as a subtitle
        book.chapter(2).setReaderSubtitle("What you will learn about the way this works…");
        book.chapter(3).setReaderSubtitle("x".repeat(DocumentComposer.MAX_STATEMENT_CHARS + 1));
        Rendered r = render(book);

        assertTrue(r.findings(Category.INTERNAL_LEAK).stream()
                .anyMatch(f -> f.severity() == Severity.ERROR && f.chapter() == 1 && f.page() != null));
        assertTrue(r.findings(Category.TRUNCATED_READER_TEXT).stream().anyMatch(f -> f.chapter() == 2));
        assertFalse(r.doc().body().text().contains("xxxxxxxx"), "an over-long subtitle is not printed");
        assertTrue(r.doc().select(".chapter-intro, .opener-statement").eachText().stream()
                .noneMatch(t -> t.startsWith("xxx")), "and never printed shortened");
    }

    @Test
    void subtitlesThatAreBriefsCutOffOrTooLongAreRejected() {
        EbookChapter c = GenreFixtures.technical().chapter(1);
        assertNull(BookTemplateService.acceptable(c.getDescription(), c));
        assertNull(BookTemplateService.acceptable(c.getDescription().substring(0, 60), c));
        assertNull(BookTemplateService.acceptable("Why every release needs…", c));
        assertNull(BookTemplateService.acceptable("y".repeat(DocumentComposer.MAX_STATEMENT_CHARS + 1), c));
        assertNull(BookTemplateService.acceptable(c.getTitle(), c));
        assertEquals("Why every release needs a way back",
                BookTemplateService.acceptable("  Why every release needs a way back ", c));
    }

    // ---- Point 4: verbatim blocks ------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("books")
    void everyVerbatimLineFitsTheColumnAndNoCharacterIsLost(GenreFixtures.Book fixture) throws IOException {
        GenreFixtures.Book book = fixture.copy();
        Rendered r = render(book);
        float column = PageGeometry.book().contentWidthPt();
        List<Element> blocks = r.doc().select(".chapter-body pre");
        assertFalse(blocks.isEmpty());
        boolean longLineFound = false;
        for (Element pre : blocks) {
            VerbatimLayout.Style style = VerbatimLayout.style(pre);
            float size = VerbatimLayout.fontSize(pre);
            assertTrue(size >= style.minPt(), "never below the readable minimum");
            double room = EbookContentRenderer.insetWidth(pre, column) - style.inset(size);
            for (String line : pre.wholeText().split("\n")) {
                assertTrue(TableLayout.textWidth(line, style.face(), size) <= room,
                        "printed line fits: \"" + line + "\"");
            }
            // Remove the visible carry-overs: the source lines come back unchanged.
            String restored = restore(pre);
            if (restored.contains(book.longLine().strip())) {
                longLineFound = true;
                assertFalse(pre.select("." + VerbatimLayout.CONTINUATION).isEmpty(), "carried over visibly");
            }
        }
        assertTrue(longLineFound, "the long line is printed whole, across a visible carry-over");
        assertTrue(r.findings(Category.VERBATIM_OVERFLOW).stream()
                .anyMatch(f -> f.severity() == Severity.WARNING && f.page() != null));

        // The PDF itself: the carry-over mark is drawn, and short lines are lines of their own.
        String text;
        try (PDDocument d = PDDocument.load(r.render().rendered().pdf())) {
            text = new PDFTextStripper().getText(d);
        }
        assertTrue(text.contains(VerbatimLayout.MARKER));
        String firstShort = shortVerbatimLine(book);
        String expectedLine = firstShort.replaceAll("\\s+", " ");
        assertTrue(text.lines().anyMatch(l -> l.strip().replaceAll("\\s+", " ").equals(expectedLine)),
                "line copied intact: " + firstShort);
    }

    /** The block's text with each carry-over (newline + mark) removed. */
    private static String restore(Element pre) {
        Element copy = pre.clone();
        for (Element mark : copy.select("." + VerbatimLayout.CONTINUATION)) {
            if (mark.previousSibling() instanceof TextNode t && t.getWholeText().endsWith("\n")) {
                t.text(t.getWholeText().substring(0, t.getWholeText().length() - 1));
            }
            mark.remove();
        }
        return copy.wholeText();
    }

    private static String shortVerbatimLine(GenreFixtures.Book book) {
        Pattern start = Pattern.compile("^(?:```\\w*|:::verbatim.*)\\n(.+)$", Pattern.MULTILINE);
        Matcher m = start.matcher(book.chapter(1).getContent());
        assertTrue(m.find());
        return m.group(1).strip();
    }

    // ---- Point 5: repetition -----------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("books")
    void repeatedTopicsAndPhrasesAreReported(GenreFixtures.Book fixture) {
        GenreFixtures.Book book = fixture.copy();
        String phrase = "the quiet discipline of doing the same small thing every single day";
        for (EbookChapter c : book.chapters()) {
            c.setContent(c.getContent() + "\n\nIt all comes back to " + phrase + " in chapter " + c.getChapterNumber() + ".");
        }
        String topic = book.chapter(1).getCoveredTopics().split("\n")[0];
        book.chapter(3).setCoveredTopics(book.chapter(3).getCoveredTopics() + "\n" + topic);
        Rendered r = render(book);

        assertTrue(r.report().repeatedPhrases().stream()
                .anyMatch(p -> phrase.contains(p.phrase()) && p.chapters().equals(List.of(1, 2, 3))),
                () -> r.report().repeatedPhrases().toString());
        String topicName = topic.replaceFirst("^- ", "").split(" — ")[0];
        assertTrue(r.findings(Category.REPETITION).stream()
                .anyMatch(f -> topicName.equals(f.excerpt()) && f.message().contains("refer back to chapter 1")));
    }

    @Test
    void theRegistryGivesEachChapterWhatEarlierChaptersExplained() {
        for (GenreFixtures.Book book : GenreFixtures.all()) {
            String forThird = TopicRegistry.forChapter(book.chapters(), 3);
            assertTrue(forThird.contains("Chapter 1: "), book.genre());
            assertTrue(forThird.contains("Chapter 2: "), book.genre());
            assertFalse(forThird.contains("Chapter 3: "), book.genre());
            assertEquals("", TopicRegistry.forChapter(book.chapters(), 1), "the first chapter starts fresh");
            // The boxes a chapter printed are registered, so they are not given again.
            String entries = TopicRegistry.entries("- A topic — its gist", book.chapter(1).getContent());
            assertTrue(entries.contains("A topic — its gist"));
            assertTrue(entries.contains(TopicRegistry.BOX_PREFIX), book.genre());
        }
    }

    // ---- Point 6: length against the budget ---------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("books")
    void lengthIsMeasuredAgainstThePlanAndOverrunsAreReportedWithNumbers(GenreFixtures.Book fixture) {
        GenreFixtures.Book book = fixture.copy();
        Rendered clean = render(book);
        assertTrue(clean.findings(Category.LENGTH_BUDGET).isEmpty(), () -> clean.report().lengths().toString());
        for (QualityReport.ChapterLength l : clean.report().lengths()) {
            // The page measure the writer is paced with tracks the real layout.
            double measured = LengthMeter.pages(book.chapter(l.chapter()).getContent());
            assertTrue(Math.abs(measured - l.actualPages()) <= 1.5,
                    "chapter " + l.chapter() + ": measured " + measured + ", rendered " + l.actualPages());
        }

        // The first chapter runs far past its budget.
        EbookChapter first = book.chapter(1);
        StringBuilder longer = new StringBuilder(first.getContent());
        for (int i = 0; i < 40; i++) {
            longer.append("\n\nAn additional paragraph number ").append(i)
                    .append(" that keeps going past the planned length of this chapter, which the plan never allowed for.");
        }
        first.setContent(longer.toString());
        Rendered over = render(book);
        Finding chapterOverrun = over.findings(Category.LENGTH_BUDGET).stream()
                .filter(f -> f.chapter() != null && f.chapter() == 1).findFirst().orElseThrow();
        assertTrue(chapterOverrun.message().matches("Chapter planned at \\d+ pages, rendered at \\d+ \\(\\+\\d+%\\)"),
                chapterOverrun.message());
        assertTrue(over.findings(Category.LENGTH_BUDGET).stream()
                .anyMatch(f -> f.chapter() == null && f.message().startsWith("Book planned at "
                        + book.ebook().getPlannedPages() + " pages, rendered at " + over.pages())));
    }

    // ---- Point 7: the report -------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("books")
    void aCleanBookHasNoErrors(GenreFixtures.Book fixture) {
        QualityReport report = render(fixture.copy()).report();
        assertEquals(0, report.errors(), () -> report.findings().toString());
        assertTrue(report.readyToPublish());
        assertEquals(report, QualityReport.fromJson(report.toJson()), "the report survives storage");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("books")
    void aBrokenBookIsReportedWithChapterAndPage(GenreFixtures.Book fixture) {
        GenreFixtures.Book book = fixture.copy();
        EbookChapter second = book.chapter(2);
        // Chapter 2 stops after announcing something, and its brief is printed as its subtitle.
        second.setContent(second.getContent() + "\n\nThe finished version looks like this:");
        second.setReaderSubtitle(second.getDescription());
        Rendered r = render(book);

        assertFalse(r.report().readyToPublish());
        List<Finding> incomplete = r.findings(Category.INCOMPLETE_UNIT);
        assertEquals(1, incomplete.size(), incomplete.toString());
        assertEquals(2, incomplete.get(0).chapter());
        assertNotNull(incomplete.get(0).page());
        assertEquals(book.chapter(2).getTitle(), incomplete.get(0).chapterTitle());
        assertTrue(r.findings(Category.INTERNAL_LEAK).stream().anyMatch(f -> f.chapter() == 2 && f.page() != null));

        List<Finding> errors = new ArrayList<>(r.report().findings().stream()
                .filter(f -> f.severity() == Severity.ERROR).toList());
        assertEquals(r.report().errors(), errors.size());
        assertEquals(Severity.ERROR, r.report().findings().get(0).severity(), "most severe first");
    }
}
