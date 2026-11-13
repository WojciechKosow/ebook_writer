package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.entity.Ebook;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The book's own type, chosen in the editor's Style tab: a body font, a heading
 * font (or the body font again), the line height and the paragraph spacing. It
 * styles the book — the page, the preview and the PDF — never the app.
 *
 * <p>Every font is an open-licence Google font bundled under
 * {@code resources/fonts/book} and embedded (subset) in the PDF. Georgia is a
 * Microsoft font that cannot be embedded on the server, so the PDF sets it in
 * Gelasio, its metric-compatible open-licence twin.
 *
 * <p>A book that never chose its type keeps the classic PDF fonts.
 */
public record BookTypography(String body, String heading, String lineHeight, String paragraphSpacing) {

    /** Font key → the family name in the bundled files (and on Google Fonts). */
    public static final Map<String, String> FAMILIES = Map.of(
            "news", "Newsreader", "bodoni", "Bodoni Moda", "lora", "Lora", "merri", "Merriweather",
            "playfair", "Playfair Display", "bask", "Libre Baskerville", "georgia", "Gelasio",
            "source", "Source Sans 3", "inter", "Inter", "mont", "Montserrat");

    /** Family names the PDF registers the chosen fonts under. */
    public static final String BODY_FAMILY = "Book Body";
    public static final String HEADING_FAMILY = "Book Heading";

    /** The editor's defaults: Newsreader body, Bodoni Moda headings, comfortable spacing. */
    public static BookTypography defaults() {
        return new BookTypography("news", "bodoni", "normal", "normal");
    }

    /** The book's chosen type, or null when it never chose one. */
    public static BookTypography of(Ebook ebook) {
        if (ebook == null || ebook.getBookBodyFont() == null) {
            return null;
        }
        BookTypography d = defaults();
        return new BookTypography(
                FAMILIES.containsKey(ebook.getBookBodyFont()) ? ebook.getBookBodyFont() : d.body(),
                ebook.getBookHeadingFont() != null
                        && (FAMILIES.containsKey(ebook.getBookHeadingFont()) || "same".equals(ebook.getBookHeadingFont()))
                        ? ebook.getBookHeadingFont() : d.heading(),
                ebook.getBookLineHeight() == null ? d.lineHeight() : ebook.getBookLineHeight(),
                ebook.getBookParagraphSpacing() == null ? d.paragraphSpacing() : ebook.getBookParagraphSpacing());
    }

    /** The font key headings are set in ({@code same} resolves to the body font). */
    public String headingKey() {
        return "same".equals(heading) ? body : heading;
    }

    /** {@code key:family} pairs to register, as written on the document for the renderer. */
    public String fontSpec() {
        Set<String> out = new LinkedHashSet<>();
        out.add(body + ":" + BODY_FAMILY);
        out.add(headingKey() + ":" + HEADING_FAMILY);
        return String.join(" ", out);
    }

    /** The PDF stylesheet additions that set the book in its chosen type. */
    public String css() {
        String lh = switch (lineHeight == null ? "normal" : lineHeight) {
            case "compact" -> "1.42";
            case "airy" -> "1.72";
            default -> null;
        };
        String ps = switch (paragraphSpacing == null ? "normal" : paragraphSpacing) {
            case "tight" -> "0.3em";
            case "open" -> "1.15em";
            default -> null;
        };
        StringBuilder sb = new StringBuilder("\n/* Book typography (editor Style tab) */\n");
        sb.append("body, p, li, td, th, blockquote, .cmp-body, .toc-title, .figure--editor figcaption, .check-item")
                .append(" { font-family: \"").append(BODY_FAMILY).append("\"; }\n");
        sb.append("h1, h2, h3, .chapter-title, .opener-title, .toc-heading, .book-title, .book-subtitle,")
                .append(" .dropcap-letter, .cmp--pullquote .cmp-body, .cmp--pullquote .cmp-body p")
                .append(" { font-family: \"").append(HEADING_FAMILY).append("\"; }\n");
        sb.append("h1, h2, .chapter-title, .opener-title, .toc-heading, .book-title { font-weight: 500; }\n");
        if (lh != null) {
            sb.append("body, p, li { line-height: ").append(lh).append("; }\n");
        }
        if (ps != null) {
            sb.append("p { margin-bottom: ").append(ps).append("; }\n");
        }
        return sb.toString();
    }
}
