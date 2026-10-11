package com.ebookwriter.SaaS.prompt;

import com.ebookwriter.SaaS.entity.BookDepth;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.service.ebook.BookTemplate;
import com.ebookwriter.SaaS.service.ebook.ChapterDirective;
import com.ebookwriter.SaaS.service.ebook.VerbatimLayout;
import com.ebookwriter.SaaS.service.ebook.WritingContext;

/**
 * Step 2 — chapter writing. Chapters are generated sequentially so each one is
 * aware of what earlier chapters already covered.
 */
public final class ChapterPrompts {

    private ChapterPrompts() {
    }

    /**
     * How a premium book ends. Used for the final chapter (and by the editor for
     * it), so the last pages read as the deliberate conclusion of an edited book,
     * not the point where generation stopped.
     */
    public static final String ENDING_ARCHITECTURE = """
            ENDING ARCHITECTURE — this chapter is where the book ends. Build its close
            deliberately, choosing the parts that fit this book's type and tone:
            1. SYNTHESIS — bring the book's major ideas together into a coherent
               whole. Show how they connect; do NOT recite the table of contents or
               summarise chapter by chapter.
            2. PRACTICAL NEXT STEP — answer "what should I actually do now?": a
               final action plan, a short implementation plan (e.g. the next 7
               days), a routine, a framework, or a "start here tomorrow" section.
               A :::steps block suits this well.
            3. COMPLETION CHECK (when it fits) — a compact :::checklist the reader
               can use to confirm they have applied or understood the material. It
               must feel like part of the book, not an appended form.
            4. FINAL TAKEAWAY — one concise idea that reinforces the book's central
               promise (a :::key-idea or :::pullquote may carry it).
            5. INTENTIONAL CLOSING — a final paragraph written as the last moment of
               the book: motivating, reflective, practical, decisive or calm to match
               the tone. It must make the reader feel they reached the end of
               something complete.
            Never close with generic lines such as "That's all!", "I hope you
            enjoyed this ebook", "Thank you for reading", "This concludes the book"
            or "Good luck on your journey!" unless that tone genuinely fits. Never
            reference chapters, sections, bonuses or follow-ups that do not exist.
            The final sentence must be complete and must be the book's last word.
            """;

    /** Delimiter separating the chapter body from its short summary. */
    public static final String SUMMARY_DELIMITER = "===SUMMARY===";

    /** Delimiter separating the summary from the chapter's topic registry entries. */
    public static final String TOPICS_DELIMITER = "===TOPICS===";

    /** How far a chapter may stray from its length budget (±) before it is reported. */
    public static final double LENGTH_TOLERANCE = 0.25;

    /**
     * Rules for verbatim blocks — anything whose line breaks and indentation carry
     * meaning — with the line-length limits measured on the real page and fonts.
     * Shared by the writer and the editor.
     */
    public static String verbatimRules() {
        VerbatimLayout.Limits l = VerbatimLayout.limits();
        return """
                Verbatim blocks (line breaks and indentation are content):
                - Whenever line breaks or indentation carry meaning — source code in any
                  language, terminal input/output, configuration, data formats, verse,
                  addresses, text tables, diagrams drawn with characters, lists whose
                  line layout matters — put the content in a verbatim block and it is
                  printed exactly as written:
                    * a fenced block (```lang ... ```) for material set in a monospaced
                      face (code, terminals, configuration, character drawings);
                    * a :::verbatim block (closed by :::) for material set in the book's
                      normal typeface (verse, addresses, lyrics, line-based lists).
                - The page is narrow. Every line in a fenced block must be at most %d
                  characters (%d inside a design component or a list); every line in a
                  :::verbatim block at most %d characters. Break longer lines the way the
                  material itself allows (its own continuation or line-break
                  conventions, shorter names, an extra variable), never by relying on the
                  page to wrap them.
                - Inside a verbatim block, never re-wrap or re-indent what is already
                  correct.
                """.formatted(l.monoChars(), l.monoNestedChars(), l.textChars());
    }

    /** Completeness rules shared by the writer and the editor. */
    public static final String COMPLETENESS_RULES = """
            Completeness:
            - Finish every sentence, list, section, block and component you start.
            - Never announce something that does not follow. A sentence that leads into
              a block ("…looks like this:", "Here is the corrected version:") must be
              followed immediately by that block.
            """;

    public static String system(String language) {
        return PromptGuidelines.core(language) + """

                You are writing ONE chapter of a larger book. Write only this chapter.

                Formatting (Markdown):
                - Do NOT repeat the chapter number or the chapter title as a heading;
                  the book renders those automatically. Begin directly with the
                  chapter's content.
                - Use "##" for major section headings and "###" for subsections.
                - Use paragraphs, bulleted or numbered lists, and short bold lead-ins
                  where they genuinely help readability.
                - For code, use fenced blocks with a language tag (e.g. ```java) when
                  the topic is technical. Keep code correct and runnable.
                - Images: only if you are given "IMAGES AVAILABLE FOR THIS CHAPTER"
                  below, you MAY place one where it genuinely fits by writing its
                  exact token on its own line as: ![short caption](ebook-image:<id>)
                  using the id given. Use an image only if it clearly belongs; it is
                  fine to use none. Never invent image ids, filenames, or URLs, and
                  never add an image when none are offered.

                Design components (use SPARINGLY, only where they genuinely fit):
                The book has a design system. Where a piece of content is one of the
                kinds below, wrap it in a fenced ::: block so it renders as a proper
                designed component instead of a plain paragraph. These are the ONLY
                block names; write the name in lower case. Do NOT force them — most
                content is ordinary prose, and over-using components looks cluttered.
                A typical chapter uses only a few, chosen because the content is
                genuinely that kind of thing.

                  :::key-idea            one crucial insight, stated plainly
                  :::takeaway            a short summary of a section
                  :::pullquote           one memorable sentence, quoted for emphasis
                  :::warning             a common mistake or caution
                  :::example             a concrete worked example
                  :::exercise Title      a practical task for the reader
                  :::done-when           a completion criterion (or just write a
                                         paragraph starting "Done when:")
                  :::checklist Title     a list of things to verify (one "- " per line)
                  :::steps Day 1         an ordered action plan. One "- " per step,
                                         "Title | duration" then an indented
                                         description line, e.g.:
                                         - Turn off notifications | 15 min
                                           Silence everything that is not a person.
                  :::flow                a process / cycle / sequence, ONE node per
                                         line — rendered as a real diagram, so prefer
                                         this over describing a flow in prose or asking
                                         for an image. e.g.:
                                         Difficult work
                                         Discomfort
                                         Check phone
                                         Relief

                Close every block with a line containing only ::: — for example:
                :::key-idea
                Focus is a state your environment creates, not a trait you are born with.
                :::

                %s
                %s
                After the chapter body, output the delimiter line exactly:
                %s
                then a 2-3 sentence summary of what this chapter established, written
                for the author's own reference (it will guide later chapters and will
                NOT be printed in the book). Then output the delimiter line exactly:
                %s
                then the TOPIC REGISTRY ENTRIES of this chapter: one line per concept,
                term, technique, procedure, warning or worked example this chapter
                actually explained, as "- topic — one-line gist" (at most 15 lines,
                most important first). Later chapters use this list to refer back
                instead of explaining again. It is NOT printed in the book.
                """.formatted(COMPLETENESS_RULES, verbatimRules(), SUMMARY_DELIMITER, TOPICS_DELIMITER);
    }

    /**
     * @param position      the chapter's 1-based position among the chapters in the book
     * @param totalChapters how many chapters the book contains (deferred ones excluded)
     */
    public static String user(Ebook e,
                              String fullOutline,
                              EbookChapter chapter,
                              String previousSummaries,
                              ChapterDirective directive,
                              String availableImages,
                              int position,
                              int totalChapters) {
        return user(e, fullOutline, chapter, previousSummaries, directive, availableImages, position,
                totalChapters, WritingContext.NONE);
    }

    /** As above, written with the book's topic registry and template ({@link WritingContext}). */
    public static String user(Ebook e,
                              String fullOutline,
                              EbookChapter chapter,
                              String previousSummaries,
                              ChapterDirective directive,
                              String availableImages,
                              int position,
                              int totalChapters,
                              WritingContext context) {
        String imagesSection = (availableImages == null || availableImages.isBlank())
                ? ""
                : "\nIMAGES AVAILABLE FOR THIS CHAPTER (use where they fit, or not at all)\n"
                        + availableImages.strip() + "\n";
        String positionSection = positionSection(directive, position, totalChapters);
        String lengthGuidance = lengthGuidance(directive, e.getDepth());
        return """
                BOOK BRIEF
                Topic: %s
                Audience: %s
                Style: %s
                Language: %s
                Additional instructions: %s

                GLOBAL WRITING GUIDELINES
                %s

                FULL BOOK OUTLINE (for context — do not rewrite other chapters)
                %s

                WHAT EARLIER CHAPTERS ALREADY COVERED (do not repeat these; build on them)
                %s
                %s%s%s
                CHAPTER TO WRITE NOW
                Chapter %d: %s
                Scope: %s
                If this chapter's scope covers more than one promised unit (e.g.
                several days, steps or stages), cover every one of them — do not stop
                partway or leave the last ones as a stub.
                %s

                Write this chapter now.
                """.formatted(
                nz(e.getTopic()),
                nz(e.getTargetAudience()),
                nz(e.getStyle()),
                blankToEnglish(e.getLanguage()),
                nz(e.getAdditionalInstructions()),
                nz(e.getWritingGuidelines()),
                nz(fullOutline),
                previousSummaries == null || previousSummaries.isBlank()
                        ? "(this is the first chapter)" : previousSummaries.trim(),
                bookContextSections(context, chapter.getChapterNumber()),
                imagesSection,
                positionSection,
                position,
                nz(chapter.getTitle()),
                nz(chapter.getDescription()),
                lengthGuidance.strip()
        );
    }

    /** The "POSITION IN THE BOOK" section (shared by the legacy and knowledge-based writers). */
    public static String positionSection(ChapterDirective directive, int position, int totalChapters) {
        int total = Math.max(totalChapters, position);
        String positionSection;
        if (directive.finalChapter()) {
            positionSection = """

                    POSITION IN THE BOOK
                    This is the FINAL chapter (chapter %d of %d) — the book ends here.
                    Cover this chapter's own scope, then bring the book to a natural,
                    satisfying close. Do NOT open large new topics or promise further
                    chapters.

                    %s""".formatted(position, total, ENDING_ARCHITECTURE);
            if (directive.windDown()) {
                positionSection += """

                        SCOPE OF THIS EDITION
                        To keep this book complete within the available credits, the following planned
                        chapters are NOT part of this book: %s.
                        Do not mention, promise or allude to them, and do not say the book
                        was shortened. Synthesise what the book HAS established, make the
                        practical next step build only on that material, and close the book
                        as a finished whole.
                        """.formatted(String.join("; ", directive.omittedChapters()));
            }
        } else {
            positionSection = """

                    POSITION IN THE BOOK
                    This is chapter %d of %d. Cover this chapter's scope fully and end it
                    at a clean boundary (a finished section, not a cliff-hanger), but do
                    not try to conclude the whole book — later chapters continue it. Only
                    refer forward to chapters that appear in the outline above.
                    """.formatted(position, total);
        }
        return positionSection;
    }

    /**
     * The topic registry and the book template (shared by the writer and the
     * editor): what earlier chapters already explained, with the rule to refer
     * back instead of explaining again, and the recurring sections every chapter
     * of this book has — or the instruction that it has none.
     */
    public static String bookContextSections(WritingContext context, int chapterNumber) {
        StringBuilder sb = new StringBuilder();
        if (!context.topicRegistry().isBlank()) {
            sb.append("""

                    TOPIC REGISTRY — ALREADY EXPLAINED IN EARLIER CHAPTERS
                    %s
                    RULE: a topic in this registry has already been explained. Do NOT explain it
                    again and do not repeat its warning, tip or example. Where this chapter needs
                    it, refer back in one short sentence (e.g. "see Chapter 2") and build on it.
                    Only add what is genuinely new for this chapter.
                    """.formatted(context.topicRegistry()));
        }
        BookTemplate template = context.template();
        if (template != null) {
            if (template.isEmpty()) {
                sb.append("""

                        BOOK TEMPLATE — RECURRING SECTIONS
                        This book has no recurring per-chapter sections. Do not end the chapter with
                        standard closing sections (exercises, a recap, a summary, "in this chapter"
                        lists, review questions) — the book's template decided against them.
                        """);
            } else {
                StringBuilder list = new StringBuilder();
                int n = 1;
                for (BookTemplate.Section s : template.sections()) {
                    list.append(n++).append(". ## ").append(s.heading());
                    if (!s.purpose().isBlank()) {
                        list.append(" — ").append(s.purpose());
                    }
                    list.append('\n');
                }
                sb.append("""

                        BOOK TEMPLATE — RECURRING SECTIONS
                        Every chapter of this book ends with exactly these sections, in this order,
                        each as a "##" heading with exactly this text, each with real content:
                        %s                        Write them for chapter %d. Add no other recurring closing section.
                        """.formatted(list, chapterNumber));
            }
        }
        return sb.toString();
    }

    /**
     * The depth + length guidance (shared by the legacy and knowledge-based
     * writers). Depth decides what the chapter covers; the word figure is only the
     * room the plan gave it — never a target to hit or a reason to cut content.
     * The one real limit is the user's credits, and only when they run short.
     */
    public static String lengthGuidance(ChapterDirective directive, BookDepth depth) {
        String length = directive.tight()
                ? """
                  Length: about %d words. The credits available for this book are
                  nearly used up, so treat this as a real limit — plan the chapter to
                  fit, keep the most valuable material, and still finish every section,
                  exercise and sentence you start. Never stop mid-thought, and never
                  mention this limit to the reader.""".formatted(directive.targetWords())
                : """
                  Length budget: about %d words (acceptable range %d–%d). The book's length
                  was planned and agreed with the author, and this is this chapter's share
                  of it. Cover the chapter's scope at the book's depth WITHIN this budget:
                  prioritise what matters most, refer back to earlier chapters instead of
                  re-explaining, and do not pad. Code, verbatim blocks, tables and design
                  components count by the space they take on the page. Still finish every
                  section, block and sentence you start."""
                        .formatted(directive.targetWords(),
                                (int) Math.round(directive.targetWords() * (1 - LENGTH_TOLERANCE)),
                                (int) Math.round(directive.targetWords() * (1 + LENGTH_TOLERANCE)));
        return BookDepth.orDefault(depth).writerGuidance() + "\n" + length;
    }

    private static String nz(String s) {
        return (s == null || s.isBlank()) ? "(none provided)" : s.trim();
    }

    private static String blankToEnglish(String s) {
        return (s == null || s.isBlank()) ? "English" : s.trim();
    }
}
