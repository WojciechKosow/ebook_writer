package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.service.ebook.LayoutIssue.Type;
import com.ebookwriter.SaaS.service.ebook.LayoutSnapshot.Block;
import com.ebookwriter.SaaS.service.ebook.LayoutSnapshot.Cell;
import com.ebookwriter.SaaS.service.ebook.LayoutSnapshot.Fragment;
import com.ebookwriter.SaaS.service.ebook.LayoutSnapshot.Line;
import com.ebookwriter.SaaS.service.ebook.LayoutSnapshot.Page;
import com.ebookwriter.SaaS.service.ebook.LayoutSnapshot.PageKind;
import com.ebookwriter.SaaS.service.ebook.LayoutSnapshot.Rect;
import com.ebookwriter.SaaS.service.ebook.LayoutSnapshot.Row;
import com.ebookwriter.SaaS.service.ebook.LayoutSnapshot.Table;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every rule of the layout check on hand-built geometry: the exact case that
 * must fail, and its legitimate twin that must pass.
 */
class LayoutValidatorTest {

    /** Pages 324 × 530 pt of content, stacked; page n's content runs from (n-1)·530. */
    private static final float W = 324;
    private static final float H = 530;

    /** A small builder for a laid-out document. */
    private static final class Doc {
        final List<Page> pages = new ArrayList<>();
        final List<Block> blocks = new ArrayList<>();
        final List<Line> lines = new ArrayList<>();
        final List<Table> tables = new ArrayList<>();
        final Map<Integer, String> texts = new HashMap<>();
        final Map<String, String> textElements = new java.util.LinkedHashMap<>();
        int nodes;

        Doc(PageKind... kinds) {
            for (int i = 0; i < kinds.length; i++) {
                float top = i * H;
                pages.add(new Page(i + 1, new Rect(0, top, W, top + H), top, top + H, kinds[i]));
            }
        }

        static float y(int page, float offset) {
            return (page - 1) * H + offset;
        }

        Block block(String id, String tag, String classes, int page, float top, float bottom) {
            Block b = new Block(id, tag, classes, "", new Rect(0, y(page, top), W, y(page, bottom)), "chapter-1",
                    false, false);
            blocks.add(b);
            return b;
        }

        /** A line of {@code text} in block {@code id}, its own text node (or a slice of {@code node}). */
        Line line(String id, String text, int page, float top, float left, float right) {
            int node = nodes++;
            texts.put(node, text);
            textElements.put(id, "p");
            return line(id, node, 0, text.length(), text, page, top, left, right);
        }

        Line line(String id, int node, int start, int end, String text, int page, float top, float left, float right) {
            Rect r = new Rect(left, y(page, top), right, y(page, top + 16));
            Line l = new Line(id, r, r, List.of(new Fragment(node, start, end, text, r, id)));
            lines.add(l);
            return l;
        }

        LayoutValidationResult validate() {
            return LayoutValidator.validate(new LayoutSnapshot(pages, blocks, lines, tables, texts, textElements));
        }
    }

    private static List<Type> types(LayoutValidationResult r) {
        return r.issues().stream().map(LayoutIssue::type).toList();
    }

    private static List<Type> errorTypes(LayoutValidationResult r) {
        return r.errors().stream().map(LayoutIssue::type).toList();
    }

    // ---- overflow and footer ------------------------------------------------------------

    @Test
    void aLineEndingExactlyAtTheContentFootIsValid() {
        Doc d = new Doc(PageKind.BODY, PageKind.BODY);
        d.block("p1", "p", "", 1, 498, 514);
        d.line("p1", "The last line of the page.", 1, H - 16, 0, 200); // bottom == content bottom
        d.block("p2", "p", "", 2, 0, 16);
        d.line("p2", "Overleaf.", 2, 0, 0, 80);

        assertEquals(LayoutValidationResult.Status.PASS, d.validate().status());
    }

    @Test
    void aLineRunningIntoTheFooterIsAnError() {
        Doc d = new Doc(PageKind.BODY);
        d.block("p1", "p", "", 1, 500, 530);
        d.line("p1", "Drawn over the page number.", 1, H - 8, 0, 200); // bottom 8pt past the content foot

        LayoutValidationResult r = d.validate();
        assertEquals(List.of(Type.FOOTER_OVERLAP), types(r));
        LayoutIssue issue = r.errors().get(0);
        assertEquals(1, issue.page());
        assertEquals("p1", issue.element());
        assertEquals(LayoutIssue.Repair.BREAK_BEFORE, issue.repair());
        assertEquals(LayoutValidationResult.Status.REPAIRABLE, r.status());
    }

    @Test
    void anImagePastTheBottomOfTheContentAreaOverflows() {
        Doc d = new Doc(PageKind.BODY, PageKind.BODY);
        d.blocks.add(new Block("img1", "img", "", "", new Rect(0, 300, 200, 700), "chapter-1", true, false));

        LayoutValidationResult r = d.validate();
        assertEquals(List.of(Type.CONTENT_OVERFLOW), errorTypes(r));
        assertEquals(LayoutIssue.Repair.BREAK_BEFORE, r.errors().get(0).repair(), "it fits a page: move it");
    }

    @Test
    void textPastTheRightEdgeOfTheColumnIsOutsideThePage() {
        Doc d = new Doc(PageKind.BODY);
        d.block("p1", "p", "", 1, 0, 16);
        d.line("p1", "An unbreakable_identifier_that_runs_off_the_page", 1, 0, 0, W + 40);

        assertEquals(List.of(Type.OUTSIDE_PAGE_BOUNDS), types(d.validate()));
    }

    @Test
    void textOverflowingItsCellIsClipping() {
        Doc d = new Doc(PageKind.BODY);
        d.blocks.add(new Block("td1", "td", "", "", new Rect(0, 0, 100, 30), "chapter-1", false, false));
        d.line("td1", "LongTokenWiderThanItsCell", 1, 4, 6, 140); // past the cell's right edge, inside the page

        assertEquals(List.of(Type.TEXT_CLIPPING), types(d.validate()));
    }

    // ---- orphan headings ----------------------------------------------------------------

    @Test
    void aHeadingAtThePageFootWithItsContentOverleafIsAnOrphan() {
        Doc d = new Doc(PageKind.BODY, PageKind.BODY);
        d.block("h", "h3", "", 1, 500, 520);
        d.line("h", "Authentication", 1, 500, 0, 120);
        d.block("p", "p", "", 2, 0, 50);
        d.line("p", "Authentication allows users to…", 2, 0, 0, 300);

        LayoutValidationResult r = d.validate();
        assertEquals(List.of(Type.ORPHAN_HEADING), types(r));
        assertEquals("h", r.errors().get(0).element());
        assertEquals(LayoutIssue.Repair.BREAK_BEFORE, r.errors().get(0).repair());
    }

    @Test
    void aHeadingWithItsContentOnTheSamePagePasses() {
        Doc d = new Doc(PageKind.BODY, PageKind.BODY);
        d.block("h", "h3", "", 1, 440, 460);
        d.line("h", "Authentication", 1, 440, 0, 120);
        d.block("p", "p", "", 1, 460, 600);
        d.line("p", "Authentication allows users to…", 1, 470, 0, 300);
        d.line("p", "…and continues overleaf.", 2, 0, 0, 300);

        assertEquals(LayoutValidationResult.Status.PASS, d.validate().status());
    }

    @Test
    void aComponentTitleCutOffFromItsBodyIsAnOrphanToo() {
        Doc d = new Doc(PageKind.BODY, PageKind.BODY);
        d.block("t", "div", "cmp-title", 1, 505, 525);
        d.line("t", "Implement Login", 1, 505, 10, 140);
        d.block("b", "p", "", 2, 0, 40);
        d.line("b", "Your task: implement the endpoint.", 2, 0, 10, 300);

        assertEquals(List.of(Type.ORPHAN_HEADING), types(d.validate()));
    }

    // ---- empty pages ---------------------------------------------------------------------

    @Test
    void anAccidentalBlankBodyPageIsSuspicious() {
        Doc d = new Doc(PageKind.BODY, PageKind.BODY, PageKind.BODY);
        d.block("p1", "p", "", 1, 0, 16);
        d.line("p1", "Text.", 1, 0, 0, 40);
        d.block("p3", "p", "", 3, 0, 16);
        d.line("p3", "More.", 3, 0, 0, 40);

        LayoutValidationResult r = d.validate();
        assertEquals(List.of(Type.SUSPICIOUS_EMPTY_PAGE), types(r));
        assertEquals(2, r.issues().get(0).page());
        assertEquals(LayoutIssue.Severity.WARNING, r.issues().get(0).severity());
        assertEquals(LayoutValidationResult.Status.PASS, r.status(), "a warning does not fail the PDF");
    }

    @Test
    void coverContentsAndOpenerPagesAreSparseByDesign() {
        Doc d = new Doc(PageKind.COVER, PageKind.FRONT_MATTER, PageKind.OPENER, PageKind.BODY);
        d.block("p", "p", "", 4, 0, 16);
        d.line("p", "Chapter text.", 4, 0, 0, 80);

        assertEquals(List.of(), d.validate().issues());
    }

    @Test
    void aChapterOpenerLeftAloneOnItsPageIsSuspicious() {
        Doc d = new Doc(PageKind.BODY, PageKind.BODY);
        d.block("num", "div", "chapter-num", 1, 0, 14);
        d.line("num", "CHAPTER 03", 1, 0, 0, 80);
        d.block("title", "h1", "chapter-title", 1, 14, 50);
        d.line("title", "JWT Authentication", 1, 16, 0, 200);
        d.block("p", "p", "", 2, 0, 16);
        d.line("p", "In this chapter…", 2, 0, 0, 120);

        assertEquals(List.of(Type.SUSPICIOUS_EMPTY_PAGE), types(d.validate()));
    }

    // ---- tables ------------------------------------------------------------------------------

    private static Row row(boolean header, int page, float top, float... columnEdges) {
        List<Cell> cells = new ArrayList<>();
        for (int c = 0; c + 1 < columnEdges.length; c++) {
            cells.add(new Cell(c, new Rect(columnEdges[c], Doc.y(page, top), columnEdges[c + 1], Doc.y(page, top + 20)), "x"));
        }
        return new Row(header, new Rect(columnEdges[0], Doc.y(page, top), columnEdges[columnEdges.length - 1],
                Doc.y(page, top + 20)), cells);
    }

    private static Table table(List<Row> rows, int columns, int sourceRows, boolean repeats) {
        float top = rows.get(0).rect().top();
        float bottom = rows.get(rows.size() - 1).rect().bottom();
        return new Table("t1", new Rect(0, top, W, bottom), columns, sourceRows, repeats, rows);
    }

    @Test
    void aMultiPageTableWithARepeatedHeaderPasses() {
        Doc d = new Doc(PageKind.BODY, PageKind.BODY);
        d.tables.add(table(List.of(row(true, 1, 440, 0, 100, W), row(false, 1, 460, 0, 100, W),
                row(false, 1, 480, 0, 100, W), row(false, 2, 20, 0, 100, W), row(false, 2, 40, 0, 100, W)), 2, 5, true));

        assertEquals(LayoutValidationResult.Status.PASS, d.validate().status());
    }

    @Test
    void aContinuedTableWithoutItsHeaderIsDetected() {
        Doc d = new Doc(PageKind.BODY, PageKind.BODY);
        d.tables.add(table(List.of(row(true, 1, 440, 0, 100, W), row(false, 1, 460, 0, 100, W),
                row(false, 2, 0, 0, 100, W)), 2, 3, false));

        assertEquals(List.of(Type.MISSING_REPEATED_HEADER), types(d.validate()));
    }

    @Test
    void aTableHeaderAloneAtThePageFootIsDetected() {
        Doc d = new Doc(PageKind.BODY, PageKind.BODY);
        d.tables.add(table(List.of(row(true, 1, 505, 0, 100, W), row(false, 2, 20, 0, 100, W)), 2, 2, true));

        LayoutValidationResult r = d.validate();
        assertEquals(List.of(Type.ORPHAN_HEADING), types(r));
        assertEquals(LayoutIssue.Repair.BREAK_BEFORE, r.errors().get(0).repair());
    }

    @Test
    void aTableWiderThanTheColumnOverflows() {
        Doc d = new Doc(PageKind.BODY);
        Table wide = new Table("t1", new Rect(0, 0, W + 120, 40), 2, 1, true, List.of(row(false, 1, 0, 0, 200, W + 120)));
        d.tables.add(wide);
        d.blocks.add(new Block("t1", "table", "", "", wide.rect(), "chapter-1", false, false));

        assertTrue(types(d.validate()).contains(Type.TABLE_OVERFLOW));
    }

    @Test
    void malformedTablesAreDetected() {
        Doc extraCells = new Doc(PageKind.BODY);
        extraCells.tables.add(table(List.of(row(false, 1, 0, 0, 100, 200, W)), 2, 1, true)); // 3 cells, 2 columns
        assertEquals(List.of(Type.MALFORMED_TABLE), types(extraCells.validate()));

        Doc misaligned = new Doc(PageKind.BODY);
        misaligned.tables.add(table(List.of(row(false, 1, 0, 0, 100, W), row(false, 1, 20, 0, 160, W)), 2, 2, true));
        assertTrue(types(misaligned.validate()).contains(Type.MALFORMED_TABLE));

        Doc overlapping = new Doc(PageKind.BODY);
        Row bad = new Row(false, new Rect(0, 0, W, 20), List.of(new Cell(0, new Rect(0, 0, 180, 20), "a"),
                new Cell(1, new Rect(150, 0, W, 20), "b")));
        overlapping.tables.add(table(List.of(bad), 2, 1, true));
        assertTrue(types(overlapping.validate()).contains(Type.MALFORMED_TABLE));

        Doc lostRows = new Doc(PageKind.BODY);
        lostRows.tables.add(table(List.of(row(false, 1, 0, 0, 100, W)), 2, 4, true)); // 4 in the source, 1 laid out
        assertEquals(List.of(Type.BROKEN_CONTINUATION), types(lostRows.validate()));
    }

    // ---- continuations -----------------------------------------------------------------------

    @Test
    void aParagraphOrCodeBlockContinuingOverleafIsValid() {
        Doc d = new Doc(PageKind.BODY, PageKind.BODY);
        String text = "first part of the paragraph second part overleaf";
        d.texts.put(0, text);
        d.textElements.put("p", "p");
        d.block("p", "p", "", 1, 480, 600);
        d.line("p", 0, 0, 27, "first part of the paragraph", 1, 500, 0, 200);
        d.line("p", 0, 28, text.length(), "second part overleaf", 2, 0, 0, 200);

        String code = "int a;\nint b;";
        d.texts.put(1, code);
        d.textElements.put("pre", "p");
        d.block("pre", "pre", "", 2, 40, 100);
        d.line("pre", 1, 0, 6, "int a;", 2, 50, 10, 60);
        d.line("pre", 1, 7, 13, "int b;", 2, 66, 10, 60);

        assertEquals(LayoutValidationResult.Status.PASS, d.validate().status());
    }

    @Test
    void contentLaidOutTwiceIsABrokenContinuation() {
        Doc d = new Doc(PageKind.BODY, PageKind.BODY);
        d.texts.put(0, "public class UserService {");
        d.textElements.put("pre", "p");
        d.block("pre", "pre", "", 1, 480, 600);
        d.line("pre", 0, 0, 26, "public class UserService {", 1, 500, 10, 200);
        d.line("pre", 0, 0, 26, "public class UserService {", 2, 0, 10, 200); // restarted overleaf

        assertEquals(List.of(Type.BROKEN_CONTINUATION), types(d.validate()));
    }

    @Test
    void contentThatDisappearsBetweenPagesIsABrokenContinuation() {
        Doc d = new Doc(PageKind.BODY, PageKind.BODY);
        String code = "line one\nline two\nline three";
        d.texts.put(0, code);
        d.textElements.put("pre", "p");
        d.block("pre", "pre", "", 1, 480, 600);
        d.line("pre", 0, 0, 8, "line one", 1, 500, 10, 100);
        d.line("pre", 0, 18, code.length(), "line three", 2, 0, 10, 100); // "line two" never laid out

        assertEquals(List.of(Type.BROKEN_CONTINUATION), types(d.validate()));
    }

    @Test
    void anElementWhoseTextNeverReachedAPageIsLostContent() {
        Doc d = new Doc(PageKind.BODY);
        d.textElements.put("lost", "p");
        d.block("p", "p", "", 1, 0, 16);
        d.line("p", "Present.", 1, 0, 0, 60);

        LayoutValidationResult r = d.validate();
        assertEquals(List.of(Type.BROKEN_CONTINUATION), types(r));
        assertEquals(LayoutValidationResult.Status.FAILED, r.status(), "no deterministic repair for lost text");
    }

    @Test
    void linesDrawnOverEachOtherAreABrokenContinuation() {
        Doc d = new Doc(PageKind.BODY);
        d.block("p1", "p", "", 1, 100, 116);
        d.line("p1", "One continuation", 1, 100, 0, 200);
        d.block("p2", "p", "", 1, 108, 124);
        d.line("p2", "drawn over another", 1, 108, 0, 200);

        assertEquals(List.of(Type.BROKEN_CONTINUATION), types(d.validate()));
    }

    @Test
    void textOnTheFullBleedCoverIsNotHeldToTheMargins() {
        Doc d = new Doc(PageKind.COVER);
        d.blocks.add(new Block("mono", "div", "cover-monogram", "", new Rect(300, 400, 460, 600), "", false, true));
        Rect r = new Rect(300, 400, 460, 560);
        d.lines.add(new Line("mono", r, r, List.of(new Fragment(-1, 0, 1, "B", r, "mono"))));

        assertEquals(List.of(), d.validate().issues());
    }
}
