package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.entity.EbookImage;
import com.ebookwriter.SaaS.entity.EbookImagePlacement;
import org.springframework.stereotype.Component;

import java.util.List;

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

        String title = orDefault(ebook.getTitle(), ebook.getTopic());
        String subtitle = ebook.getSubtitle();
        EbookImage cover = coverImage(images);

        StringBuilder html = new StringBuilder(64 * 1024);
        html.append("<html><head><meta charset=\"UTF-8\"/><style>")
                .append(css)
                .append("</style></head><body>");

        appendCover(html, title, subtitle, ebook.getTopic(), cover);
        appendTableOfContents(html, chapters);
        appendChapters(html, chapters);

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
    private void appendCover(StringBuilder html, String title, String subtitle,
                             String topic, EbookImage cover) {
        html.append("<div class=\"cover")
                .append(cover != null ? " cover--with-image" : " cover--typographic")
                .append("\">");
        if (cover != null) {
            html.append("<img class=\"cover-bg\" src=\"")
                    .append(cover.markdownRef())
                    .append("\" alt=\"\"/>");
        }
        html.append("<div class=\"cover-overlay\">");
        html.append("<div class=\"cover-eyebrow\">").append(escape(eyebrow(topic))).append("</div>");
        html.append("<div class=\"book-title\">").append(escape(title)).append("</div>");
        if (isNotBlank(subtitle)) {
            html.append("<div class=\"book-subtitle\">").append(escape(subtitle)).append("</div>");
        }
        html.append("</div>");
        html.append("</div>");
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

    private void appendChapters(StringBuilder html, List<EbookChapter> chapters) {
        int displayNum = 1;
        for (EbookChapter c : chapters) {
            if (isBlank(c.getContent())) {
                continue;
            }
            String anchor = "chapter-" + c.getChapterNumber();
            html.append("<div class=\"chapter\" id=\"").append(anchor).append("\">")
                    .append("<div class=\"chapter-opener\">")
                    .append("<div class=\"chapter-num\">Chapter ").append(displayNum++).append("</div>")
                    .append("<h1 class=\"chapter-title\">")
                    .append(escape(orDefault(c.getTitle(), ""))).append("</h1>");
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
    }

    // ---- Helpers ------------------------------------------------------------

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
