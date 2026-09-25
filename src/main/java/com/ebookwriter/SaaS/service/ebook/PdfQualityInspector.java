package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.service.ebook.EbookValidationService.Issue;
import com.ebookwriter.SaaS.service.ebook.EbookValidationService.Severity;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.action.PDAction;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.PDFTextStripperByArea;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;

import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Production QA over the <b>rendered PDF bytes</b> — what the reader actually
 * receives — run before a book is published as READY. It complements the
 * manuscript-level checks in {@link EbookValidationService#inspect}.
 *
 * <p>Checks (all read-only, PDFBox):
 * <ul>
 *   <li><b>readability</b> — the PDF parses and has pages (FATAL otherwise);</li>
 *   <li><b>page dimensions</b> — every page is the book's 6×9" trim size;</li>
 *   <li><b>empty pages</b> — a page with no text and no image;</li>
 *   <li><b>final-page anomaly</b> — the last page holds only a stray line or two
 *       (a sentence that spilled onto a fresh page);</li>
 *   <li><b>stray artifacts</b> — a page whose only content is a lone character or
 *       number (other than its folio);</li>
 *   <li><b>fonts</b> — every font is embedded (no substitution on the reader's
 *       device);</li>
 *   <li><b>images</b> — the expected number of images was drawn (none dropped or
 *       broken), none is drawn distorted (non-proportional scale), none is drawn at
 *       too low a resolution, and none is extremely compressed;</li>
 *   <li><b>overflow</b> — no glyph lies outside the page (clipped text);</li>
 *   <li><b>table of contents</b> — each printed TOC page number equals the page
 *       its entry links to.</li>
 * </ul>
 * Only an unreadable/empty document is FATAL — everything else is a WARNING, so
 * a legitimately-designed book (opener pages are intentionally sparse) is never
 * failed on a heuristic.
 */
public final class PdfQualityInspector {

    private PdfQualityInspector() {
    }

    /** The 6×9" trim size in PDF points. */
    static final float PAGE_WIDTH_PT = 6 * 72f;
    static final float PAGE_HEIGHT_PT = 9 * 72f;
    private static final float DIMENSION_TOLERANCE_PT = 2f;

    /** A final page with fewer words than this (and no image) is a spill-over anomaly. */
    static final int SPARSE_FINAL_PAGE_WORDS = 30;

    /** Drawn/intrinsic aspect drift above this is a distorted (stretched) image. */
    static final double DISTORTION_TOLERANCE = 0.03;

    /** Images drawn below this effective resolution look soft in print/zoom. */
    static final double MIN_EFFECTIVE_DPI = 110;

    /** A JPEG below this many bytes per pixel is extremely (visibly) compressed. */
    static final double MIN_JPEG_BYTES_PER_PIXEL = 0.03;

    /** Per-page facts gathered once and shared by the checks. */
    record PageFacts(int index, String text, int words, int images) {
    }

    /** What the inspector measured, for logging and the page-level fixes. */
    public record Result(List<Issue> issues, int pageCount, int lastPageWords, boolean lastPageHasImage) {
        public boolean lastPageSparse() {
            return pageCount > 3 && !lastPageHasImage && lastPageWords < SPARSE_FINAL_PAGE_WORDS;
        }
    }

    /**
     * Inspect rendered PDF bytes.
     *
     * @param pdf            the rendered PDF
     * @param expectedImages how many images the HTML placed (cover + inline); -1 to skip
     */
    public static Result inspect(byte[] pdf, int expectedImages) {
        List<Issue> issues = new ArrayList<>();
        if (pdf == null || pdf.length == 0) {
            issues.add(new Issue(Severity.FATAL, "rendered PDF is empty"));
            return new Result(issues, 0, 0, false);
        }
        try (PDDocument doc = PDDocument.load(pdf)) {
            int pages = doc.getNumberOfPages();
            if (pages == 0) {
                issues.add(new Issue(Severity.FATAL, "rendered PDF has no pages"));
                return new Result(issues, 0, 0, false);
            }

            List<PageFacts> facts = new ArrayList<>(pages);
            int drawnImages = 0;
            for (int i = 0; i < pages; i++) {
                PDPage page = doc.getPage(i);
                checkDimensions(page, i, issues);
                String text = pageText(doc, i);
                ImageProbe probe = new ImageProbe(page);
                probe.processPage(page);
                drawnImages += probe.drawn;
                issues.addAll(probe.issues);
                facts.add(new PageFacts(i, text, contentWords(text), probe.drawn));
                checkOverflow(doc, i, page, issues);
            }

            checkEmptyAndStrayPages(facts, issues);
            checkFonts(doc, issues);
            checkToc(doc, issues);

            if (expectedImages >= 0 && drawnImages < expectedImages) {
                issues.add(new Issue(Severity.WARNING, String.format(
                        "%d image(s) placed but only %d drawn — an image is missing or failed to load",
                        expectedImages, drawnImages)));
            }

            PageFacts last = facts.get(pages - 1);
            Result result = new Result(issues, pages, last.words(), last.images() > 0);
            if (result.lastPageSparse()) {
                issues.add(new Issue(Severity.WARNING, String.format(
                        "final page holds only %d word(s) — content spilled onto an otherwise empty page",
                        last.words())));
            }
            return result;
        } catch (IOException e) {
            issues.add(new Issue(Severity.FATAL, "rendered PDF could not be read: " + e.getMessage()));
            return new Result(issues, 0, 0, false);
        }
    }

    /**
     * A cheap check of only the last page, used by the renderer to decide whether
     * to reflow a spill-over: true when the book's final page carries just a few
     * words and no image. Never throws — an unreadable PDF is simply "not sparse".
     */
    public static boolean lastPageSparse(byte[] pdf) {
        if (pdf == null || pdf.length == 0) {
            return false;
        }
        try (PDDocument doc = PDDocument.load(pdf)) {
            int pages = doc.getNumberOfPages();
            if (pages <= 3) {
                return false;
            }
            PDPage last = doc.getPage(pages - 1);
            ImageProbe probe = new ImageProbe(last);
            probe.processPage(last);
            int words = contentWords(pageText(doc, pages - 1));
            return new Result(List.of(), pages, words, probe.drawn > 0).lastPageSparse();
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    // ---- checks ---------------------------------------------------------------

    private static void checkDimensions(PDPage page, int index, List<Issue> issues) {
        PDRectangle box = page.getMediaBox();
        if (Math.abs(box.getWidth() - PAGE_WIDTH_PT) > DIMENSION_TOLERANCE_PT
                || Math.abs(box.getHeight() - PAGE_HEIGHT_PT) > DIMENSION_TOLERANCE_PT) {
            issues.add(new Issue(Severity.WARNING, String.format(
                    "page %d is %.0fx%.0fpt, expected the 6x9in trim (%.0fx%.0fpt)",
                    index + 1, box.getWidth(), box.getHeight(), PAGE_WIDTH_PT, PAGE_HEIGHT_PT)));
        }
    }

    /**
     * Pages with no content at all, and pages whose only content is a stray
     * token. The cover (page 1) is skipped — a full-bleed image cover legitimately
     * has little extractable text.
     */
    static void checkEmptyAndStrayPages(List<PageFacts> facts, List<Issue> issues) {
        for (PageFacts f : facts) {
            if (f.index() == 0) {
                continue;
            }
            String stripped = withoutFolio(f.text());
            if (stripped.isEmpty() && f.images() == 0) {
                issues.add(new Issue(Severity.WARNING, "page " + (f.index() + 1) + " is empty"));
            } else if (f.images() == 0 && stripped.length() <= 3 && !stripped.isEmpty()) {
                issues.add(new Issue(Severity.WARNING, "page " + (f.index() + 1)
                        + " contains only a stray artifact '" + stripped + "'"));
            }
        }
    }

    private static void checkFonts(PDDocument doc, List<Issue> issues) {
        Set<String> reported = new HashSet<>();
        for (PDPage page : doc.getPages()) {
            PDResources res = page.getResources();
            if (res == null) {
                continue;
            }
            for (COSName name : res.getFontNames()) {
                try {
                    PDFont font = res.getFont(name);
                    if (font != null && !font.isEmbedded() && reported.add(font.getName())) {
                        issues.add(new Issue(Severity.WARNING, "font '" + font.getName() + "' is not embedded"));
                    }
                } catch (IOException e) {
                    if (reported.add(name.getName())) {
                        issues.add(new Issue(Severity.WARNING, "font " + name.getName() + " could not be loaded"));
                    }
                }
            }
        }
    }

    /** Glyphs placed outside the page box are clipped — text overflowed its container. */
    private static void checkOverflow(PDDocument doc, int index, PDPage page, List<Issue> issues)
            throws IOException {
        PDRectangle box = page.getMediaBox();
        int[] outside = {0};
        PDFTextStripper stripper = new PDFTextStripper() {
            @Override
            protected void writeString(String text, List<TextPosition> positions) {
                for (TextPosition p : positions) {
                    float x = p.getXDirAdj();
                    float y = p.getYDirAdj();
                    if (x < -1 || y < -1 || x + p.getWidthDirAdj() > box.getWidth() + 1
                            || y > box.getHeight() + 1) {
                        outside[0]++;
                    }
                }
            }
        };
        stripper.setStartPage(index + 1);
        stripper.setEndPage(index + 1);
        stripper.getText(doc);
        if (outside[0] > 0) {
            issues.add(new Issue(Severity.WARNING, "page " + (index + 1) + " has " + outside[0]
                    + " glyph(s) outside the page — clipped/overflowing text"));
        }
    }

    /**
     * The contents page's numbers are generated by the renderer from the same
     * anchors the TOC links point to. Verify it: for every TOC link whose visible
     * text is a page number, that number must equal the page the link targets.
     * Only the first few pages (the front matter) are examined.
     */
    static void checkToc(PDDocument doc, List<Issue> issues) throws IOException {
        int scan = Math.min(doc.getNumberOfPages(), 4);
        int checked = 0;
        for (int i = 1; i < scan; i++) {
            PDPage page = doc.getPage(i);
            List<PDAnnotation> annotations = page.getAnnotations();
            PDFTextStripperByArea area = new PDFTextStripperByArea();
            List<PDAnnotationLink> links = new ArrayList<>();
            for (PDAnnotation a : annotations) {
                if (a instanceof PDAnnotationLink link) {
                    PDRectangle r = link.getRectangle();
                    if (r == null) {
                        continue;
                    }
                    float height = page.getMediaBox().getHeight();
                    area.addRegion("l" + links.size(), new Rectangle2D.Float(
                            r.getLowerLeftX() - 1, height - r.getUpperRightY() - 1,
                            r.getWidth() + 2, r.getHeight() + 2));
                    links.add(link);
                }
            }
            if (links.isEmpty()) {
                continue;
            }
            area.extractRegions(page);
            for (int k = 0; k < links.size(); k++) {
                String label = area.getTextForRegion("l" + k).strip();
                if (!label.matches("\\d{1,4}")) {
                    continue;
                }
                int target = destinationPage(links.get(k));
                if (target < 0) {
                    continue;
                }
                checked++;
                int printed = Integer.parseInt(label);
                if (printed != target + 1) {
                    issues.add(new Issue(Severity.WARNING, String.format(
                            "contents lists page %d but the entry links to page %d", printed, target + 1)));
                }
            }
        }
        if (checked == 0 && doc.getNumberOfPages() > 3) {
            issues.add(new Issue(Severity.WARNING, "table of contents page numbers could not be verified"));
        }
    }

    private static int destinationPage(PDAnnotationLink link) throws IOException {
        PDDestination dest = link.getDestination();
        if (dest == null) {
            PDAction action = link.getAction();
            if (action instanceof PDActionGoTo goTo) {
                dest = goTo.getDestination();
            }
        }
        if (dest instanceof PDPageDestination pageDest) {
            int n = pageDest.retrievePageNumber();
            return n;
        }
        return -1;
    }

    // ---- helpers --------------------------------------------------------------

    private static String pageText(PDDocument doc, int index) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(index + 1);
        stripper.setEndPage(index + 1);
        return stripper.getText(doc);
    }

    /** Words on a page, not counting a bare folio number. */
    static int contentWords(String text) {
        String t = withoutFolio(text);
        return t.isEmpty() ? 0 : t.split("\\s+").length;
    }

    /** The page text with a trailing (or lone) page number removed. */
    static String withoutFolio(String text) {
        if (text == null) {
            return "";
        }
        return text.strip().replaceFirst("(^|\\s)\\d{1,4}$", "").strip();
    }

    /**
     * Walks a page's content stream and inspects every image as it is drawn: its
     * drawn size comes from the current transformation matrix, so a
     * non-proportional scale (a stretched image) or an under-resolved image is
     * caught exactly as the reader will see it.
     */
    private static final class ImageProbe extends PDFGraphicsStreamEngine {
        int drawn;
        final List<Issue> issues = new ArrayList<>();

        ImageProbe(PDPage page) {
            super(page);
        }

        @Override
        public void drawImage(PDImage image) {
            drawn++;
            Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
            double drawnW = Math.abs(ctm.getScalingFactorX());
            double drawnH = Math.abs(ctm.getScalingFactorY());
            int w = image.getWidth();
            int h = image.getHeight();
            if (w <= 0 || h <= 0 || drawnW <= 0 || drawnH <= 0) {
                return;
            }
            double intrinsic = (double) w / h;
            double actual = drawnW / drawnH;
            if (Math.abs(actual - intrinsic) / intrinsic > DISTORTION_TOLERANCE) {
                issues.add(new Issue(Severity.WARNING, String.format(
                        "an image (%dx%d) is drawn distorted: aspect %.2f vs %.2f", w, h, actual, intrinsic)));
            }
            double dpi = w / (drawnW / 72.0);
            if (dpi < MIN_EFFECTIVE_DPI) {
                issues.add(new Issue(Severity.WARNING, String.format(
                        "an image (%dx%d) is drawn at only %.0f dpi — it will look soft", w, h, dpi)));
            }
            if (image instanceof PDImageXObject xo) {
                try {
                    List<COSName> filters = xo.getStream().getFilters();
                    if (filters != null && filters.contains(COSName.DCT_DECODE)) {
                        long bytes = xo.getCOSObject().getLength();
                        double bpp = (double) bytes / ((long) w * h);
                        if (bytes > 0 && bpp < MIN_JPEG_BYTES_PER_PIXEL) {
                            issues.add(new Issue(Severity.WARNING, String.format(
                                    "an image (%dx%d) is heavily compressed (%.3f bytes/pixel)", w, h, bpp)));
                        }
                    }
                } catch (RuntimeException e) {
                    // compression metadata is best-effort
                }
            }
        }

        @Override public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) { }
        @Override public void clip(int windingRule) { }
        @Override public void moveTo(float x, float y) { }
        @Override public void lineTo(float x, float y) { }
        @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) { }
        @Override public Point2D getCurrentPoint() { return new Point2D.Float(0, 0); }
        @Override public void closePath() { }
        @Override public void endPath() { }
        @Override public void strokePath() { }
        @Override public void fillPath(int windingRule) { }
        @Override public void fillAndStrokePath(int windingRule) { }
        @Override public void shadingFill(COSName shadingName) { }
    }
}
