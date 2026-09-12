package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.entity.EbookImage;
import com.ebookwriter.SaaS.entity.EbookImagePlacement;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.Extension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Assembles the final book HTML: cover page, table of contents, and each
 * chapter (Markdown converted to HTML). The result is fed to
 * {@link PdfGenerationService} for rendering.
 */
@Component
public class EbookHtmlBuilder {

    /**
     * Pages the book always spends on front matter regardless of content: the
     * cover and the table of contents (each forces a page break). The user pays
     * for these, so content is budgeted as {@code pageBudget - FRONT_MATTER_PAGES}.
     */
    public static final int FRONT_MATTER_PAGES = 2;

    private final Parser markdownParser;
    private final HtmlRenderer markdownRenderer;

    public EbookHtmlBuilder() {
        List<Extension> extensions = List.of(TablesExtension.create());
        this.markdownParser = Parser.builder().extensions(extensions).build();
        this.markdownRenderer = HtmlRenderer.builder().extensions(extensions).build();
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

        // Cover
        html.append("<div class=\"cover")
                .append(cover != null ? " cover--with-image" : "")
                .append("\">");
        if (cover != null) {
            html.append("<img class=\"cover-image\" src=\"")
                    .append(cover.markdownRef())
                    .append("\" alt=\"\"/>");
        }
        html.append("<div class=\"book-title\">").append(escape(title)).append("</div>");
        if (isNotBlank(subtitle)) {
            html.append("<div class=\"book-subtitle\">").append(escape(subtitle)).append("</div>");
        }
        html.append("</div>");

        // Table of contents
        html.append("<div class=\"toc\"><h1>Contents</h1><ol>");
        int tocNum = 1;
        for (EbookChapter c : chapters) {
            if (isBlank(c.getContent())) continue;
            html.append("<li><span class=\"toc-num\">")
                    .append(tocNum++).append(". </span>")
                    .append(escape(orDefault(c.getTitle(), "Chapter " + c.getChapterNumber())))
                    .append("</li>");
        }
        html.append("</ol></div>");

        // Chapters
        int displayNum = 1;
        for (EbookChapter c : chapters) {
            if (isBlank(c.getContent())) continue;
            html.append("<div class=\"chapter\">")
                    .append("<div class=\"chapter-heading\">")
                    .append("<span class=\"chapter-num\">Chapter ").append(displayNum++).append("</span>")
                    .append("<span class=\"chapter-title\">")
                    .append(escape(orDefault(c.getTitle(), ""))).append("</span>")
                    .append("</div>")
                    .append("<div class=\"chapter-body\">")
                    .append(markdownToHtml(c.getContent()))
                    .append("</div></div>");
        }

        html.append("</body></html>");
        return html.toString();
    }

    private String markdownToHtml(String markdown) {
        return markdownRenderer.render(markdownParser.parse(markdown));
    }

    /** The book's cover image, or null if none is placed as the cover. */
    private static EbookImage coverImage(List<EbookImage> images) {
        if (images == null) return null;
        return images.stream()
                .filter(i -> i.getPlacement() == EbookImagePlacement.COVER)
                .findFirst()
                .orElse(null);
    }

    private static String escape(String s) {
        if (s == null) return "";
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
