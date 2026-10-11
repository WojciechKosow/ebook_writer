package com.ebookwriter.SaaS.service.ebook;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a chapter's Markdown into the book's <b>editorial HTML</b>: not just
 * paragraphs and lists, but a real design system of semantic components.
 *
 * <p>This is what moves the output from "AI text in a PDF" toward "a designed
 * book". On top of ordinary Markdown, the writer can emit lightweight
 * <em>directive blocks</em> that render as distinct, reusable components:
 *
 * <pre>
 *   :::key-idea
 *   Focus is not a personality trait. It is a state created by conditions.
 *   :::
 *
 *   :::steps Day 1
 *   - Audit what pulls you away | 5 min
 *     Watch which apps you open without deciding to.
 *   - Turn off every notification | 15 min
 *     Silence everything that isn't a person messaging you directly.
 *   :::
 *
 *   :::flow
 *   Difficult work
 *   Discomfort
 *   Check phone
 *   Relief
 *   New loop
 *   :::
 * </pre>
 *
 * <p>Because the result is plain HTML styled by {@code pdf/ebook.css}, every
 * component renders identically in the editor preview and the exported PDF — the
 * single layout model is preserved (there is no second renderer). Diagrams for
 * processes/cycles ({@code :::flow}) and action plans ({@code :::steps}) are
 * drawn with our own typography rather than an image model, so their text is
 * crisp, on-brand, and free of AI spelling mistakes.
 *
 * <p>The transform is pure (Markdown in, HTML out) and has no framework or
 * database dependencies, so it is fully unit-testable. Unknown directive names
 * degrade to a generic note, and malformed input never throws — it falls back to
 * ordinary Markdown rendering.
 */
@Component
public class EbookContentRenderer {

    /** Opening fence of a directive block: {@code :::type optional title}. */
    private static final Pattern DIRECTIVE_OPEN =
            Pattern.compile("^:::\\s*([a-zA-Z][\\w-]*)\\s*(.*)$");
    /** Closing fence of a directive block: a line that is exactly {@code :::}. */
    private static final Pattern DIRECTIVE_CLOSE = Pattern.compile("^:::\\s*$");

    /**
     * Natural-language completion criterion ("Done when: ..."), optionally bold.
     * Detected from ordinary prose so legacy/plain content still gets the styled
     * component without the writer having to know the directive syntax.
     */
    private static final Pattern DONE_WHEN_LINE =
            Pattern.compile("^\\s*(?:[*_]{0,2})done when(?:[*_]{0,2})\\s*:\\s*(.+?)(?:[*_]{0,2})\\s*$",
                    Pattern.CASE_INSENSITIVE);

    /** A Markdown list item line: {@code - text} / {@code * text} / {@code 1. text}. */
    private static final Pattern LIST_ITEM =
            Pattern.compile("^\\s*(?:[-*+]|\\d+[.)])\\s+(.*)$");

    private static final String PLACEHOLDER_PREFIX = "CMPBLOCKPLACEHOLDERZ";

    private final Parser parser;
    private final HtmlRenderer renderer;

    public EbookContentRenderer() {
        List<Extension> extensions = List.of(TablesExtension.create());
        this.parser = Parser.builder().extensions(extensions).build();
        this.renderer = HtmlRenderer.builder().extensions(extensions).build();
    }

    /**
     * Render a chapter body (Markdown + directive blocks) to editorial HTML.
     * Never throws: on any parsing surprise the block is rendered as its inner
     * Markdown so no content is ever lost.
     */
    public String toHtml(String markdown) {
        return toHtml(markdown, new RenderContext());
    }

    /**
     * Render one chapter body as part of a whole book: {@code ctx} carries what
     * the book has already printed (so a block appears only once in the book)
     * and collects notes for the author's quality report. Beyond the plain
     * transform, the chapter is cleaned before pagination:
     * <ul>
     *   <li>a block already printed earlier in the book is dropped;</li>
     *   <li>a heading left with no content under it is dropped;</li>
     *   <li>every verbatim block is fitted to its column ({@link VerbatimLayout}).</li>
     * </ul>
     */
    public String toHtml(String markdown, RenderContext ctx) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        String html = renderBody(markdown.replace("\r\n", "\n").replace("\r", "\n"));
        Document doc = Jsoup.parseBodyFragment(html);
        doc.outputSettings().prettyPrint(false);
        Element body = doc.body();
        removeDuplicateBlocks(body, ctx);
        removeEmptyHeadings(body, ctx);
        float column = PageGeometry.book().contentWidthPt();
        for (Element pre : body.select("pre")) {
            VerbatimLayout.apply(pre, insetWidth(pre, column), ctx);
        }
        paginate(body);
        return body.html();
    }

    /**
     * Markdown with directive blocks to HTML — the pure transform, without the
     * book-level clean-up or pagination. Components may nest (a warning inside an
     * exercise, a tip inside a list item): each component's body goes through
     * this same transform.
     */
    String renderBody(String markdown) {
        String normalised = autodetect(markdown);

        List<String> components = new ArrayList<>();
        String outer = extractDirectives(normalised, components);

        String html = markdownToHtml(outer);

        // Swap each placeholder for its rendered component HTML. Tokens are
        // terminated, so no token is a prefix of another (Z1Z vs Z10Z).
        for (int i = 0; i < components.size(); i++) {
            String token = placeholder(i);
            html = html.replace("<p>" + token + "</p>", components.get(i));
            // A placeholder commonmark did not wrap on its own (e.g. a lazy line in
            // a list item) is still swapped exactly.
            html = html.replace(token, components.get(i));
        }
        return html;
    }

    static String placeholder(int index) {
        return PLACEHOLDER_PREFIX + index + "Z";
    }

    // ---- Book-level clean-up ------------------------------------------------

    /** Prose shorter than this is too generic to call a duplicate ("Let's begin."). */
    static final int MIN_DUPLICATE_CHARS = 80;
    /** Components and verbatim blocks this short are not checked either. */
    static final int MIN_DUPLICATE_BLOCK_CHARS = 30;

    /**
     * Print each block once in the whole book. A component or a prose paragraph
     * whose normalised text was already printed — earlier in this chapter or in
     * an earlier one — is removed and noted. A repeated verbatim block is only
     * noted: a listing shown again on purpose (a refrain, an updated file shown
     * in full) is the author's call.
     */
    static void removeDuplicateBlocks(Element body, RenderContext ctx) {
        for (Element e : body.select("div.cmp, p, blockquote, pre")) {
            if (e.parent() == null || inside(e, "div.cmp, pre, blockquote, li, table, figure")) {
                continue; // part of a larger block, or detached with it
            }
            boolean verbatim = e.tagName().equals("pre");
            String fp = fingerprint(verbatim ? e.wholeText() : e.text());
            int min = e.tagName().equals("p") || e.tagName().equals("blockquote")
                    ? MIN_DUPLICATE_CHARS : MIN_DUPLICATE_BLOCK_CHARS;
            if (fp.length() < min) {
                continue;
            }
            int first = ctx.firstSeen(e.tagName() + ":" + fp);
            if (first < 0) {
                continue;
            }
            String where = first == ctx.chapter() ? "earlier in this chapter" : "in chapter " + first;
            if (verbatim) {
                String ref = ctx.newRef();
                e.attr(RenderContext.QA_ATTR, ref);
                ctx.note(RenderContext.NoteType.DUPLICATE_VERBATIM,
                        "Verbatim block already printed " + where + " (kept)", excerpt(e.wholeText()), ref);
            } else {
                ctx.note(RenderContext.NoteType.DUPLICATE_REMOVED,
                        "Block already printed " + where + "; not printed again", excerpt(e.text()), null);
                e.remove();
            }
        }
    }

    /**
     * Never print a heading with nothing under it: a heading followed directly by
     * a heading of the same or a higher level, or by the end of the chapter, is
     * removed. Repeated until stable, since removing an empty subsection can
     * empty its parent section.
     */
    static void removeEmptyHeadings(Element body, RenderContext ctx) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Element h : body.select("h1, h2, h3, h4, h5, h6")) {
                if (h.parent() != body) {
                    continue;
                }
                Element next = h.nextElementSibling();
                boolean empty = next == null
                        || (next.tagName().matches("h[1-6]") && level(next) <= level(h));
                if (empty) {
                    ctx.note(RenderContext.NoteType.EMPTY_HEADING_REMOVED,
                            "Heading with no content under it; not printed", h.text(), null);
                    h.remove();
                    changed = true;
                }
            }
        }
    }

    private static int level(Element heading) {
        return heading.tagName().charAt(1) - '0';
    }

    private static boolean inside(Element e, String selector) {
        for (Element p : e.parents()) {
            if (p.is(selector)) {
                return true;
            }
        }
        return false;
    }

    /** Lower-cased letters and digits only, single-spaced: what makes two blocks "the same". */
    static String fingerprint(String text) {
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .strip();
    }

    private static String excerpt(String text) {
        String t = text.strip().replaceAll("\\s+", " ");
        return t.length() > 120 ? t.substring(0, 120) : t;
    }

    /**
     * The width a block is laid out in: the text column less the inset of every
     * component box and list it is nested in.
     */
    static float insetWidth(Element e, float column) {
        float width = column;
        for (Element p : e.parents()) {
            if (p.hasClass("cmp")) {
                width -= 28f; // component padding (≈1.1em each side) + keyline
            } else if (p.tagName().equals("li")) {
                width -= 16f; // list indent
            } else if (p.tagName().equals("blockquote")) {
                width -= 13f;
            }
        }
        return width;
    }

    // ---- Semantic pagination ------------------------------------------------

    /** Alt text that says nothing — never printed as a caption. */
    private static final java.util.Set<String> GENERIC_ALT = java.util.Set.of(
            "", "image", "illustration", "picture", "photo", "figure", "diagram");

    /** Captions longer than this read as descriptions, not captions, and are not printed. */
    static final int MAX_CAPTION_CHARS = 140;

    /**
     * Group semantic units so the page breaker can't split them — the same HTML
     * drives the editor preview and the PDF, so both paginate alike:
     * <ul>
     *   <li><b>figures</b> — an image on its own line becomes a {@code <figure>}
     *       with its caption (a meaningful alt text) inside it, so a caption can
     *       never land on a different page from its image;</li>
     *   <li><b>heading + lead</b> — a section heading (or a run of them, an h2
     *       straight into its h3) is wrapped with the start of what it
     *       introduces, so a heading never sits alone at the foot of a page: the
     *       first block, plus — when that is only a short lead-in line — the
     *       block the lead-in introduces. Code, tables, figures and components
     *       join at any size (they never split, so they'd move anyway); a very
     *       long paragraph or list is left free (orphans/widows handle it) so a
     *       whole page isn't pushed forward for one heading.</li>
     * </ul>
     */
    static String paginate(String html) {
        if (html == null || html.isBlank()) {
            return html;
        }
        Document doc = Jsoup.parseBodyFragment(html);
        doc.outputSettings().prettyPrint(false);
        Element body = doc.body();
        paginate(body);
        return body.html();
    }

    /** {@link #paginate(String)} on a parsed chapter body, in place. */
    static void paginate(Element body) {
        for (Element p : body.select("p")) {
            if (p.children().size() == 1 && p.child(0).tagName().equals("img")
                    && p.ownText().isBlank()) {
                Element img = p.child(0);
                Element figure = new Element("figure").addClass("figure");
                figure.appendChild(img.clone());
                String alt = img.attr("alt").strip();
                if (!GENERIC_ALT.contains(alt.toLowerCase(Locale.ROOT)) && alt.length() <= MAX_CAPTION_CHARS) {
                    figure.appendElement("figcaption").text(alt);
                }
                p.replaceWith(figure);
            }
        }

        float column = PageGeometry.book().contentWidthPt();
        for (Element table : body.select("table")) {
            TableLayout.apply(table, availableWidth(table, column));
        }

        // Logical blocks: a title and the start of what it introduces.
        for (Element child : new ArrayList<>(body.children())) {
            if (child.parent() != body) {
                continue; // already part of an earlier block
            }
            List<Element> unit = logicalBlock(child);
            if (unit != null) {
                keepTogether(unit, column, child == body.firstElementChild());
            }
        }
        // Components keep their label and title with the start of their body.
        for (Element cmp : body.select("div.cmp")) {
            if (!opensChapter(cmp, body)) {
                guardComponent(cmp, column);
            }
        }
        TableLayout.guardStarts(body, body.firstElementChild());
    }

    /** Class of a guarded logical block (stylesheet: may split once its start is placed). */
    static final String KEEP_START = "keep-with-next--start";

    /** Room added to every estimated start, so "fits" means fits. */
    static final double START_HEADROOM_PT = 16;

    /**
     * The logical block that begins at {@code first}, or null if none does: a
     * title — a heading run (an h2 straight into its h3), or a label paragraph
     * ("**Request**", "Your task:") — followed by up to {@link #MAX_LEAD_INS}
     * lead-in lines and the content block they introduce.
     */
    private static List<Element> logicalBlock(Element first) {
        List<Element> unit = new ArrayList<>();
        Element next = first;
        if (isHeading(first)) {
            while (next != null && isHeading(next)) {
                unit.add(next);
                next = next.nextElementSibling();
            }
        } else if (isLabel(first)) {
            unit.add(first);
            next = first.nextElementSibling();
        } else {
            return null;
        }
        int leadIns = 0;
        while (next != null && !next.tagName().matches("h[1-6]")) {
            unit.add(next);
            if (!isLeadIn(next) || leadIns++ == MAX_LEAD_INS) {
                break;
            }
            next = next.nextElementSibling();
        }
        boolean content = unit.stream().anyMatch(e -> !isHeading(e) && e != first);
        return content ? unit : null; // a title with nothing after it: page-break-after: avoid applies
    }

    /**
     * Keep a logical block's start on one page, space-aware: the block is
     * wrapped in a group that starts on the current page only if the page has
     * room for its <b>start</b> — the title and lead-ins, plus the start of the
     * content ({@link BlockMetrics#start}: the first lines of a paragraph or
     * list, a code block or component whole if it fits a page, a table's header
     * and first row). Otherwise the group starts on the next page
     * ({@code -fs-page-break-min-height}). Past its start the content flows and
     * may continue overleaf — a long paragraph, code block, exercise or table is
     * never pushed forward whole for its title's sake.
     *
     * <p>A block whose size can't be estimated (a figure), or an action plan, is
     * kept whole with its title instead, as before.
     */
    private static void keepTogether(List<Element> unit, float column, boolean opensChapter) {
        Element content = unit.get(unit.size() - 1);
        // An action plan's floated step numbers make the renderer leave a blank
        // page inside a guarded group; it keeps its steps whole itself, so it
        // joins its title as one unit, as before.
        double start = content.hasClass("cmp--steps") ? -1 : BlockMetrics.start(content, column);
        Element group = new Element("div").addClass("keep-with-next");
        // The chapter's first block sits under the opener, which already heads
        // the page: it starts there, whatever its size (the stylesheet lets it
        // split) — moving it would leave the opener alone on a page.
        if (start >= 0 && !opensChapter) {
            for (Element e : unit.subList(0, unit.size() - 1)) {
                start += BlockMetrics.height(e, column);
            }
            group.addClass(KEEP_START).attr("style", TableLayout.minHeightStyle(start + START_HEADROOM_PT));
        }
        unit.get(0).before(group);
        for (Element e : unit) {
            group.appendChild(e);
        }
    }

    /** Whether {@code e} is, or begins, the first block of the chapter body. */
    private static boolean opensChapter(Element e, Element body) {
        Element first = body.firstElementChild();
        return first != null && (e == first || e.parents().contains(first));
    }

    /** Class of a component that flows over pages (stylesheet: no page-break-inside: avoid). */
    static final String FLOWS = "cmp--flows";

    /**
     * A component that may split — an exercise, or anything taller than a page —
     * flows over pages, but starts only where its label, title and the first
     * lines of its body fit, so the title never ends a page above a body that
     * begins overleaf. One that fits a page is moved whole by the stylesheet and
     * needs no guard. (Keeping "avoid" on a component taller than a page is what
     * strands its title: the renderer can't honour it and breaks right after the
     * header.)
     */
    private static void guardComponent(Element cmp, float column) {
        if (cmp.hasClass("cmp--steps")) {
            // An action plan keeps every step whole already, and its floated step
            // numbers make the renderer leave a blank page when it flows.
            return;
        }
        double start = BlockMetrics.start(cmp, column);
        double whole = BlockMetrics.height(cmp, column);
        if (start > 0 && start < whole) {
            cmp.addClass(FLOWS);
            String style = cmp.attr("style");
            String guard = TableLayout.minHeightStyle(start + START_HEADROOM_PT);
            cmp.attr("style", style.isBlank() ? guard : style + ";" + guard);
        }
    }

    /**
     * The width a table is laid out in: the text column, less the inset of a
     * component box or list it sits in. (Column widths are emitted as shares, so
     * this only steers how the width is divided, never the table's own width.)
     */
    private static float availableWidth(Element table, float column) {
        float width = column;
        if (table.closest(".cmp") != null) {
            width -= 28f; // component padding (≈1.1em each side) + keyline
        }
        if (table.closest("li") != null) {
            width -= 16f; // list indent
        }
        return width;
    }

    /** Lead-in paragraphs a heading may gather before reaching the block they introduce. */
    static final int MAX_LEAD_INS = 2;

    /** A paragraph this short (chars, ~2 lines) is a lead-in, not content that can stand alone. */
    static final int LEAD_IN_MAX_CHARS = 160;

    private static boolean isHeading(Element e) {
        String tag = e.tagName();
        return tag.equals("h2") || tag.equals("h3") || tag.equals("h4");
    }

    /**
     * Whether a block is only a lead-in to the next one: a short paragraph, or one
     * that ends in a colon ("The entity looks like this:"). Left at a page foot
     * under its heading, it reads as a stranded heading.
     */
    private static boolean isLeadIn(Element block) {
        if (!block.tagName().equals("p")) {
            return false;
        }
        String text = block.text().strip();
        return text.length() <= LEAD_IN_MAX_CHARS || text.endsWith(":");
    }

    /** A label this short ("Request", "Important") is a title for what follows, not content. */
    static final int LABEL_MAX_CHARS = 80;

    /**
     * Whether a paragraph is a label for the block after it: a short line set
     * entirely in bold or italics ("**Request**", "**Important**"), or a lead-in
     * that ends in a colon ("Your task:", "The endpoints:"). Left at a page foot,
     * it reads like a stranded heading.
     */
    private static boolean isLabel(Element p) {
        if (!p.tagName().equals("p") || p.nextElementSibling() == null) {
            return false;
        }
        String text = p.text().strip();
        if (text.isEmpty()) {
            return false;
        }
        if (text.endsWith(":") && text.length() <= LEAD_IN_MAX_CHARS) {
            return true;
        }
        boolean emphasised = p.children().size() == 1 && p.ownText().isBlank()
                && p.child(0).tagName().matches("strong|b|em|i");
        return emphasised && text.length() <= LABEL_MAX_CHARS;
    }

    /**
     * Promote a few natural-language patterns to explicit directive blocks before
     * the main pass, so plainly-written content still benefits from the design
     * system. Conservative by design — currently only the "Done when:" completion
     * criterion, which the content routinely produces verbatim.
     */
    private static String autodetect(String markdown) {
        String[] lines = markdown.split("\n", -1);
        StringBuilder out = new StringBuilder(markdown.length() + 64);
        boolean insideDirective = false;
        String fence = null;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.strip();
            // Verbatim content is never rewritten.
            if (fence != null) {
                if (closesFence(trimmed, fence)) {
                    fence = null;
                }
                out.append(line).append('\n');
                continue;
            }
            String opened = fenceMarker(trimmed);
            if (opened != null) {
                fence = opened;
                out.append(line).append('\n');
                continue;
            }
            if (DIRECTIVE_OPEN.matcher(trimmed).matches()) {
                insideDirective = true;
            } else if (DIRECTIVE_CLOSE.matcher(trimmed).matches()) {
                insideDirective = false;
            }
            Matcher dw = insideDirective ? null : DONE_WHEN_LINE.matcher(line);
            if (dw != null && dw.matches()) {
                // Capture the whole paragraph: the "Done when:" line plus any
                // soft-wrapped continuation lines up to the next blank line, so
                // the component never clips mid-sentence.
                StringBuilder body = new StringBuilder(dw.group(1).strip());
                while (i + 1 < lines.length && !lines[i + 1].isBlank()
                        && !DIRECTIVE_OPEN.matcher(lines[i + 1].strip()).matches()) {
                    body.append(' ').append(lines[++i].strip());
                }
                String done = body.toString().replaceAll("[*_]{1,2}\\s*$", "").strip();
                out.append("\n:::done-when\n").append(done).append("\n:::\n\n");
            } else {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    /**
     * Replace every {@code :::type ... :::} block with a placeholder paragraph and
     * collect the rendered component HTML in {@code componentsOut} (indexed to
     * match the placeholder). Returns the outer Markdown with placeholders.
     */
    private String extractDirectives(String markdown, List<String> componentsOut) {
        String[] lines = markdown.split("\n", -1);
        StringBuilder outer = new StringBuilder(markdown.length());

        int i = 0;
        String fence = null;
        while (i < lines.length) {
            String line = lines[i];
            String trimmed = line.strip();
            // ::: inside a code block is part of the code.
            if (fence != null) {
                if (closesFence(trimmed, fence)) {
                    fence = null;
                }
                outer.append(line).append('\n');
                i++;
                continue;
            }
            String opened = fenceMarker(trimmed);
            if (opened != null) {
                fence = opened;
                outer.append(line).append('\n');
                i++;
                continue;
            }
            Matcher open = DIRECTIVE_OPEN.matcher(trimmed);
            if (!open.matches()) {
                outer.append(line).append('\n');
                i++;
                continue;
            }

            String type = open.group(1).toLowerCase(Locale.ROOT);
            String title = open.group(2) == null ? "" : open.group(2).strip();
            boolean verbatim = VERBATIM_TYPES.contains(type);

            // Collect the inner lines up to the matching close (or end of input).
            // Components nest: an inner ":::name" opens a level its own ":::"
            // closes. A verbatim block is literal, so its first ":::" closes it.
            List<String> inner = new ArrayList<>();
            int j = i + 1;
            int depth = 1;
            String innerFence = null;
            while (j < lines.length) {
                String t = lines[j].strip();
                if (innerFence != null) {
                    if (closesFence(t, innerFence)) {
                        innerFence = null;
                    }
                } else if (!verbatim && fenceMarker(t) != null) {
                    innerFence = fenceMarker(t);
                } else if (DIRECTIVE_CLOSE.matcher(t).matches()) {
                    if (--depth == 0) {
                        break;
                    }
                } else if (!verbatim && DIRECTIVE_OPEN.matcher(t).matches()) {
                    depth++;
                }
                inner.add(lines[j]);
                j++;
            }
            boolean closed = depth == 0;

            String token = placeholder(componentsOut.size());
            componentsOut.add(verbatim
                    ? renderVerbatim(title, dedent(inner))
                    : renderComponent(type, title, String.join("\n", dedent(inner)).strip()));
            // The placeholder keeps the block's indentation, so a block written
            // inside a list item stays inside it (the list is not broken and its
            // numbering continues). Blank lines around it make it its own
            // paragraph, so the <p>token</p> swap is exact.
            String indent = line.substring(0, line.indexOf(':'));
            outer.append('\n').append(indent).append(token).append("\n\n");

            i = closed ? j + 1 : j;
        }
        return outer.toString();
    }

    /** Directive names of a verbatim block set in the text face (see {@link VerbatimLayout}). */
    static final java.util.Set<String> VERBATIM_TYPES =
            java.util.Set.of("verbatim", "preformatted", "lines", "verse", "poem");

    /** Opening code fence (``` or ~~~, 3+), returning its marker, or null. */
    static String fenceMarker(String stripped) {
        Matcher m = FENCE_OPEN.matcher(stripped);
        return m.matches() ? m.group(1) : null;
    }

    /** Whether {@code stripped} closes a fence opened with {@code marker}. */
    static boolean closesFence(String stripped, String marker) {
        if (stripped.length() < marker.length() || stripped.charAt(0) != marker.charAt(0)) {
            return false;
        }
        int n = 0;
        while (n < stripped.length() && stripped.charAt(n) == marker.charAt(0)) {
            n++;
        }
        return n >= marker.length() && stripped.substring(n).isBlank();
    }

    private static final Pattern FENCE_OPEN = Pattern.compile("^(`{3,}|~{3,}).*$");

    /** The lines less their common indentation, without leading or trailing blank lines. */
    static List<String> dedent(List<String> lines) {
        int common = Integer.MAX_VALUE;
        for (String l : lines) {
            if (l.isBlank()) {
                continue;
            }
            int k = 0;
            while (k < l.length() && l.charAt(k) == ' ') {
                k++;
            }
            common = Math.min(common, k);
        }
        int cut = common == Integer.MAX_VALUE ? 0 : common;
        List<String> out = new ArrayList<>();
        for (String l : lines) {
            out.add(l.isBlank() ? "" : l.substring(Math.min(cut, l.length())).stripTrailing());
        }
        while (!out.isEmpty() && out.get(0).isEmpty()) {
            out.remove(0);
        }
        while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    /**
     * A verbatim block in the text face: every line break and every leading space
     * is kept as written. An optional title is printed as a label above it.
     */
    String renderVerbatim(String title, List<String> lines) {
        StringBuilder sb = new StringBuilder();
        if (title != null && !title.isBlank()) {
            sb.append("<p class=\"verbatim-title\"><strong>").append(escape(title)).append("</strong></p>");
        }
        sb.append("<pre class=\"verbatim ").append(VerbatimLayout.TEXT_CLASS).append("\">")
                .append(escape(String.join("\n", lines)))
                .append("</pre>");
        return sb.toString();
    }

    // ---- Component rendering ------------------------------------------------

    private String renderComponent(String type, String title, String inner) {
        return switch (type) {
            case "steps", "action-plan", "actions" -> renderSteps(title, inner);
            case "flow", "process", "cycle", "sequence" -> renderFlow(title, inner);
            case "checklist", "checks" -> renderChecklist(title, inner);
            case "pullquote", "pull-quote", "quote" -> renderPullQuote(inner);
            case "done-when", "donewhen", "done" -> renderCallout("donewhen", "Done when", inner);
            case "key-idea", "keyidea", "key" -> renderCallout("keyidea", "Key idea", title, inner);
            case "takeaway", "takeaways", "summary" -> renderCallout("takeaway", "Takeaway", title, inner);
            case "warning", "caution", "mistake", "common-mistake" ->
                    renderCallout("warning", "Common mistake", title, inner);
            case "example" -> renderCallout("example", "Example", title, inner);
            case "exercise", "practice" -> renderCallout("exercise", "Exercise", title, inner);
            case "tip" -> renderCallout("tip", "Tip", title, inner);
            case "note", "info" -> renderCallout("note", "Note", title, inner);
            default -> renderCallout("note", capitalise(type), title, inner);
        };
    }

    private String renderCallout(String cssType, String label, String inner) {
        return renderCallout(cssType, label, "", inner);
    }

    /** A labelled callout: an eyebrow label, an optional title, and Markdown body. */
    private String renderCallout(String cssType, String label, String title, String inner) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"cmp cmp--").append(cssType).append("\">");
        if (label != null && !label.isBlank()) {
            sb.append("<div class=\"cmp-label\">").append(escape(label.toUpperCase(Locale.ROOT))).append("</div>");
        }
        if (title != null && !title.isBlank()) {
            sb.append("<div class=\"cmp-title\">").append(escape(title)).append("</div>");
        }
        sb.append("<div class=\"cmp-body\">").append(renderBody(inner)).append("</div>");
        sb.append("</div>");
        return sb.toString();
    }

    /** A large editorial pull quote (no label). */
    private String renderPullQuote(String inner) {
        String text = inner.replaceFirst("^>\\s?", "").strip();
        return "<div class=\"cmp cmp--pullquote\"><div class=\"cmp-body\">"
                + renderBody(text) + "</div></div>";
    }

    /**
     * An action plan: numbered steps, each with a title, an optional duration/meta
     * (after a {@code |}), and a description. The number is drawn as a large
     * editorial index (01, 02, …).
     */
    String renderSteps(String eyebrow, String inner) {
        List<Step> steps = parseSteps(inner);
        if (steps.isEmpty()) {
            // Nothing parseable — fall back to rendering the inner Markdown so no
            // content is lost.
            return renderCallout("note", eyebrow.isBlank() ? "Steps" : eyebrow, "", inner);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"cmp cmp--steps\">");
        if (eyebrow != null && !eyebrow.isBlank()) {
            sb.append("<div class=\"steps-eyebrow\">").append(escape(eyebrow.toUpperCase(Locale.ROOT))).append("</div>");
        }
        sb.append("<ol class=\"steps-list\">");
        int n = 1;
        for (Step step : steps) {
            sb.append("<li class=\"step\">");
            sb.append("<div class=\"step-index\">").append(String.format("%02d", n++)).append("</div>");
            sb.append("<div class=\"step-main\">");
            sb.append("<div class=\"step-title\">").append(escape(step.title));
            if (step.meta != null && !step.meta.isBlank()) {
                sb.append("<span class=\"step-meta\">").append(escape(step.meta.toUpperCase(Locale.ROOT))).append("</span>");
            }
            sb.append("</div>");
            if (step.description != null && !step.description.isBlank()) {
                sb.append("<div class=\"step-desc\">").append(renderBody(step.description)).append("</div>");
            }
            sb.append("</div></li>");
        }
        sb.append("</ol></div>");
        return sb.toString();
    }

    /** A programmatic process/cycle diagram: nodes joined by drawn arrows. */
    String renderFlow(String title, String inner) {
        List<String> nodes = new ArrayList<>();
        for (String raw : inner.split("\n")) {
            // Allow authors to write nodes on one line separated by arrows too.
            for (String part : raw.split("->|→|=>|\\u2192")) {
                String node = part.replaceFirst("^\\s*(?:[-*+]|\\d+[.)])\\s+", "").strip();
                if (!node.isBlank()) {
                    nodes.add(node);
                }
            }
        }
        if (nodes.isEmpty()) {
            return renderCallout("note", title.isBlank() ? "Diagram" : title, "", inner);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"cmp cmp--flow\">");
        if (title != null && !title.isBlank()) {
            sb.append("<div class=\"flow-title\">").append(escape(title)).append("</div>");
        }
        for (int i = 0; i < nodes.size(); i++) {
            if (i > 0) {
                sb.append("<div class=\"flow-arrow\"></div>");
            }
            sb.append("<div class=\"flow-node\">").append(escape(nodes.get(i))).append("</div>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    /** A checklist: each item gets a drawn checkbox. */
    private String renderChecklist(String title, String inner) {
        List<String> items = new ArrayList<>();
        for (String raw : inner.split("\n")) {
            Matcher m = LIST_ITEM.matcher(raw);
            String item = m.matches() ? m.group(1).strip() : raw.strip();
            // Strip a leading GitHub-style task marker if present.
            item = item.replaceFirst("^\\[[ xX]?\\]\\s*", "").strip();
            if (!item.isBlank()) {
                items.add(item);
            }
        }
        if (items.isEmpty()) {
            return renderCallout("note", title.isBlank() ? "Checklist" : title, "", inner);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"cmp cmp--checklist\">");
        if (title != null && !title.isBlank()) {
            sb.append("<div class=\"cmp-label\">").append(escape(title.toUpperCase(Locale.ROOT))).append("</div>");
        }
        sb.append("<ul class=\"checklist\">");
        for (String item : items) {
            sb.append("<li class=\"check-item\">").append(inlineMarkdown(item)).append("</li>");
        }
        sb.append("</ul></div>");
        return sb.toString();
    }

    // ---- Steps parsing ------------------------------------------------------

    private record Step(String title, String meta, String description) {
    }

    /**
     * Parse {@code steps} inner text into ordered items. A new item starts at each
     * list marker; the first line is {@code title | meta}, and any following
     * (indented or plain) lines until the next marker are the description.
     */
    static List<Step> parseSteps(String inner) {
        List<Step> steps = new ArrayList<>();
        String[] lines = inner.split("\n");
        String title = null;
        String meta = null;
        StringBuilder desc = new StringBuilder();

        for (String line : lines) {
            Matcher m = LIST_ITEM.matcher(line);
            if (m.matches()) {
                if (title != null) {
                    steps.add(new Step(title, meta, desc.toString().strip()));
                }
                String head = m.group(1).strip();
                int pipe = head.indexOf('|');
                if (pipe >= 0) {
                    title = head.substring(0, pipe).strip();
                    meta = head.substring(pipe + 1).strip();
                } else {
                    title = head;
                    meta = null;
                }
                desc = new StringBuilder();
            } else if (title != null && !line.isBlank()) {
                if (desc.length() > 0) {
                    desc.append('\n');
                }
                desc.append(line.strip());
            }
        }
        if (title != null) {
            steps.add(new Step(title, meta, desc.toString().strip()));
        }
        return steps;
    }

    // ---- Markdown helpers ---------------------------------------------------

    private String markdownToHtml(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        return renderer.render(parser.parse(markdown));
    }

    /** Render a single line of Markdown without the wrapping {@code <p>}. */
    private String inlineMarkdown(String markdown) {
        String html = markdownToHtml(markdown).strip();
        if (html.startsWith("<p>") && html.endsWith("</p>")) {
            return html.substring(3, html.length() - 4);
        }
        return html;
    }

    private static String capitalise(String s) {
        if (s == null || s.isBlank()) {
            return "";
        }
        String cleaned = s.replace('-', ' ').replace('_', ' ').strip();
        return Character.toUpperCase(cleaned.charAt(0)) + cleaned.substring(1);
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
