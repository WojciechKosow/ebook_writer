package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.entity.EbookImage;
import com.ebookwriter.SaaS.entity.EbookImagePlacement;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Assembles the final book HTML: an editorial cover, a real table of contents
 * (with dotted leaders and cross-referenced page numbers), and each chapter as
 * an intentionally-composed opening page followed by its body. The result is fed
 * to {@link PdfGenerationService} for rendering and to
 * {@link EbookPreviewService} for the in-editor preview — the <b>same</b> HTML
 * and CSS drive both, so the preview matches the download.
 *
 * <p>Chapter bodies are turned into styled HTML by {@link EbookContentRenderer},
 * which understands the book's semantic component system (key ideas, action
 * plans, diagrams, pull quotes, completion criteria, …) on top of plain
 * Markdown. This class owns only the book-level scaffolding around those bodies.
 */
@Component
public class EbookHtmlBuilder {

    /**
     * Pages the book always spends on front matter regardless of content: the
     * cover and the table of contents (each forces a page break). The user pays
     * for these, so content is budgeted as {@code pageBudget - FRONT_MATTER_PAGES}.
     */
    public static final int FRONT_MATTER_PAGES = 2;

    private final EbookContentRenderer contentRenderer;

    public EbookHtmlBuilder() {
        this.contentRenderer = new EbookContentRenderer();
    }

    public String build(Ebook ebook, List<EbookChapter> chapters, String css) {
        return build(ebook, chapters, css, List.of());
    }

    /**
     * Assemble the book HTML. Inline images are referenced in chapter Markdown
     * with an {@code ebook-image:<id>} URL; those references are left as-is here
     * and rewritten to a streamable storage URL during rendering
     * ({@link PdfGenerationService}). The cover image, if any, is emitted the
     * same way so it flows through the same rewrite.
     */
    public String build(Ebook ebook, List<EbookChapter> chapters, String css,
                        List<EbookImage> images) {

        EbookImage cover = coverImage(images);
        List<DocumentComposer.ChapterLayout> layouts = DocumentComposer.compose(ebook, chapters);
        Map<Integer, DocumentComposer.ChapterLayout> layoutByNumber = new HashMap<>();
        for (DocumentComposer.ChapterLayout l : layouts) {
            layoutByNumber.put(l.chapterNumber(), l);
        }

        StringBuilder html = new StringBuilder(64 * 1024);
        html.append("<html><head><meta charset=\"UTF-8\"/><style>")
                .append(css)
                .append("</style></head><body>");

        appendCover(html, ebook, cover);
        appendTableOfContents(html, chapters);
        appendChapters(html, chapters, layoutByNumber);

        html.append("</body></html>");
        return html.toString();
    }

    // ---- Cover --------------------------------------------------------------

    /**
     * The cover — the first page. With a placed cover image it fills the whole
     * page (full-bleed background) with the title/subtitle overlaid on a
     * legibility scrim. Without one it is a composed typographic cover: an eyebrow
     * rule, a strong title, the subtitle, and a foot rule — deliberately minimal
     * and editorial rather than a bare centred title. The image is emitted as an
     * {@code ebook-image:} reference so it flows through the same rewrite (PDF) /
     * inline (preview) as chapter images.
     */
    private void appendCover(StringBuilder html, Ebook ebook, EbookImage cover) {
        String title = orDefault(ebook.getTitle(), ebook.getTopic());
        String subtitle = ebook.getSubtitle();
        String author = ebook.getAuthorName();

        html.append("<div class=\"cover")
                .append(cover != null ? " cover--with-image" : " cover--typographic")
                .append("\">");
        if (cover != null) {
            html.append("<img class=\"cover-bg\" src=\"")
                    .append(cover.markdownRef())
                    .append("\" alt=\"\"/>");
        } else {
            // An art-directed visual concept derived from the book's structure —
            // a large program numeral for a multi-day/step book, otherwise the
            // title's initial as a ghosted monogram. Deterministic, on-brand, and
            // never a generic AI stock image.
            appendCoverVisual(html, ebook);
        }
        html.append("<div class=\"cover-overlay\">");
        html.append("<div class=\"cover-eyebrow\">").append(escape(eyebrow(ebook.getTopic()))).append("</div>");
        html.append("<div class=\"book-title\">").append(escape(title)).append("</div>");
        if (isNotBlank(subtitle)) {
            html.append("<div class=\"book-subtitle\">").append(escape(subtitle)).append("</div>");
        }
        if (isNotBlank(author)) {
            html.append("<div class=\"cover-byline\">by ").append(escape(author.strip())).append("</div>");
        }
        html.append("</div>");
        // Subtle publisher imprint — small, letter-spaced, at the foot.
        html.append("<div class=\"cover-imprint\">SCRIVETTE</div>");
        html.append("</div>");
    }

    /** The typographic cover's visual concept (large numeral or monogram). */
    private void appendCoverVisual(StringBuilder html, Ebook ebook) {
        Optional<StructureRequirement> structure = StructureRequirement.detect(ebook);
        if (structure.isPresent()) {
            StructureRequirement s = structure.get();
            html.append("<div class=\"cover-visual cover-visual--numeral\">")
                    .append("<div class=\"cover-numeral\">").append(s.count()).append("</div>")
                    .append("<div class=\"cover-numeral-label\">")
                    .append(escape(s.unit().toUpperCase(java.util.Locale.ROOT)))
                    .append(s.count() == 1 ? "" : "S").append("</div>")
                    .append("</div>");
        } else {
            String monogram = monogram(orDefault(ebook.getTitle(), ebook.getTopic()));
            if (!monogram.isBlank()) {
                html.append("<div class=\"cover-visual cover-visual--monogram\">")
                        .append("<div class=\"cover-monogram\">").append(escape(monogram)).append("</div>")
                        .append("</div>");
            }
        }
    }

    /** The first letter of the book's title, uppercased — its cover monogram. */
    private static String monogram(String title) {
        if (title == null) {
            return "";
        }
        for (int i = 0; i < title.length(); i++) {
            char c = title.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                return String.valueOf(Character.toUpperCase(c));
            }
        }
        return "";
    }

    /**
     * A short, uppercased kicker shown above the title on the typographic cover.
     * Derived from the topic (never fabricated) so it reflects the book without
     * hardcoding any subject.
     */
    private static final java.util.Set<String> KICKER_STOPWORDS = java.util.Set.of(
            "a", "an", "the", "and", "or", "of", "to", "for", "with", "on", "in",
            "at", "by", "from", "your", "you", "how", "why", "what", "is", "are",
            "be", "get", "into", "about", "that", "this");

    private static String eyebrow(String topic) {
        if (isBlank(topic)) {
            return "A PRACTICAL GUIDE";
        }
        String t = topic.strip();
        // Keep it a tight kicker: first clause, at most five words, and never
        // ending on a dangling connective word (so it never reads as a truncated
        // sentence). Falls back to a neutral kicker when nothing clean remains.
        int cut = firstIndexOf(t, '.', ',', ':', '\n');
        if (cut > 0) {
            t = t.substring(0, cut).strip();
        }
        java.util.List<String> words = new java.util.ArrayList<>(
                java.util.List.of(t.split("\\s+")));
        if (words.size() > 5) {
            words = new java.util.ArrayList<>(words.subList(0, 5));
        }
        while (!words.isEmpty()
                && KICKER_STOPWORDS.contains(words.get(words.size() - 1).toLowerCase().replaceAll("[^a-z]", ""))) {
            words.remove(words.size() - 1);
        }
        String kicker = String.join(" ", words).strip();
        return kicker.length() < 3 ? "A PRACTICAL GUIDE" : kicker;
    }

    private static int firstIndexOf(String s, char... chars) {
        int best = -1;
        for (char c : chars) {
            int idx = s.indexOf(c);
            if (idx >= 0 && (best < 0 || idx < best)) {
                best = idx;
            }
        }
        return best;
    }

    // ---- Table of contents --------------------------------------------------

    /**
     * A real book contents page: a two-digit index, the chapter title, a dotted
     * leader, and the chapter's actual page number. The page number is produced by
     * the renderer via a cross-reference to the chapter anchor
     * ({@code target-counter}), so it is the true printed page, not an estimate.
     */
    private void appendTableOfContents(StringBuilder html, List<EbookChapter> chapters) {
        html.append("<div class=\"toc\"><div class=\"toc-heading\">Contents</div>")
                .append("<table class=\"toc-list\">");
        int num = 1;
        for (EbookChapter c : chapters) {
            if (isBlank(c.getContent())) {
                continue;
            }
            String anchor = "chapter-" + c.getChapterNumber();
            String href = "#" + anchor;
            html.append("<tr class=\"toc-item\">")
                    .append("<td class=\"toc-entry\">")
                    .append("<a class=\"toc-link\" href=\"").append(href).append("\">")
                    .append("<span class=\"toc-num\">").append(String.format("%02d", num++)).append("</span>")
                    .append("<span class=\"toc-title\">")
                    .append(escape(orDefault(c.getTitle(), "Chapter " + c.getChapterNumber())))
                    .append("</span></a></td>")
                    .append("<td class=\"toc-pagecell\">")
                    .append("<a class=\"toc-pagelink\" href=\"").append(href).append("\"></a>")
                    .append("</td>")
                    .append("</tr>");
        }
        html.append("</table></div>");
    }

    // ---- Chapters -----------------------------------------------------------

    private void appendChapters(StringBuilder html, List<EbookChapter> chapters,
                                Map<Integer, DocumentComposer.ChapterLayout> layouts) {
        int displayNum = 0;
        for (EbookChapter c : chapters) {
            if (isBlank(c.getContent())) {
                continue;
            }
            displayNum++;
            DocumentComposer.ChapterLayout layout = layouts.get(c.getChapterNumber());
            if (layout != null && layout.style() == DocumentComposer.OpenerStyle.FULL_PAGE) {
                appendFullPageOpener(html, c, layout);
            } else {
                appendBandChapter(html, c, layout, displayNum);
            }
        }
    }

    /**
     * A dedicated opener page (label, big numeral, title, statement), followed by
     * the chapter body on the next page. The TOC anchor lives on the opener so the
     * contents page points at the divider — the reader's entry to the section.
     */
    private void appendFullPageOpener(StringBuilder html, EbookChapter c,
                                      DocumentComposer.ChapterLayout layout) {
        String anchor = "chapter-" + c.getChapterNumber();
        html.append("<div class=\"opener")
                .append(layout.unit() ? " opener--unit" : " opener--chapter")
                .append("\" id=\"").append(anchor).append("\">")
                .append("<div class=\"opener-numeral\">").append(escape(layout.numeral())).append("</div>")
                .append("<div class=\"opener-label\">").append(escape(layout.label())).append("</div>")
                .append("<h1 class=\"opener-title\">")
                .append(escape(displayTitle(c.getTitle(), layout.unit()))).append("</h1>");
        if (layout.meta() != null) {
            html.append("<div class=\"opener-meta\">").append(escape(layout.meta())).append("</div>");
        }
        if (layout.statement() != null) {
            html.append("<div class=\"opener-statement\">").append(escape(layout.statement())).append("</div>");
        }
        html.append("</div>");
        // Body begins on the page after the opener (no page-break-before of its own).
        html.append("<div class=\"chapter-continued\">")
                .append("<div class=\"chapter-body\">")
                .append(contentRenderer.toHtml(c.getContent()))
                .append("</div></div>");
    }

    /** A strong opener band at the top of the content page (the compact default). */
    private void appendBandChapter(StringBuilder html, EbookChapter c,
                                   DocumentComposer.ChapterLayout layout, int displayNum) {
        String anchor = "chapter-" + c.getChapterNumber();
        boolean unit = layout != null && layout.unit();
        String label = layout != null ? layout.label() : "Chapter " + displayNum;
        html.append("<div class=\"chapter")
                .append(unit ? " chapter--unit" : "")
                .append("\" id=\"").append(anchor).append("\">")
                .append("<div class=\"chapter-opener\">")
                .append("<div class=\"chapter-num\">").append(escape(label)).append("</div>")
                .append("<h1 class=\"chapter-title\">")
                .append(escape(displayTitle(c.getTitle(), unit))).append("</h1>");
        if (layout != null && layout.meta() != null) {
            html.append("<div class=\"chapter-meta\">").append(escape(layout.meta())).append("</div>");
        }
        if (isNotBlank(c.getDescription())) {
            html.append("<div class=\"chapter-intro\">")
                    .append(escape(c.getDescription().strip()))
                    .append("</div>");
        }
        html.append("<div class=\"chapter-rule\"></div>")
                .append("</div>")
                .append("<div class=\"chapter-body\">")
                .append(contentRenderer.toHtml(c.getContent()))
                .append("</div></div>");
    }

    // ---- Helpers ------------------------------------------------------------

    /**
     * The title as shown on an opener. For a program-unit chapter the opener's
     * label already says "DAY 01", so a leading "Day 1 —" in the title is stripped
     * to avoid the redundancy; if stripping would empty the title, the original is
     * kept. Non-unit titles are returned unchanged.
     */
    static String displayTitle(String title, boolean unit) {
        String t = orDefault(title, "");
        if (!unit || t.isBlank()) {
            return t;
        }
        String stripped = t.replaceFirst(
                "(?i)^\\s*(?:the\\s+)?(?:day|week|month|step|part|stage|phase|module|lesson|session)"
                        + "\\s+(?:\\d{1,3}|[ivxlcdm]{1,7})\\s*[-–—:.)]*\\s*", "").strip();
        return stripped.isBlank() ? t : stripped;
    }

    /** The book's cover image, or null if none is placed as the cover. */
    private static EbookImage coverImage(List<EbookImage> images) {
        if (images == null) {
            return null;
        }
        return images.stream()
                .filter(i -> i.getPlacement() == EbookImagePlacement.COVER)
                .findFirst()
                .orElse(null);
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static String orDefault(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean isNotBlank(String s) {
        return !isBlank(s);
    }
}
