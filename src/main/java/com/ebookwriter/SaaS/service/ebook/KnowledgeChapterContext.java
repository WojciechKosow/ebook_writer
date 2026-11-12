package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.config.properties.KnowledgeWritingProperties;
import com.ebookwriter.SaaS.dto.blueprint.BlueprintData;
import com.ebookwriter.SaaS.dto.blueprint.BookGenerationInput;
import com.ebookwriter.SaaS.dto.knowledge.BookKnowledgeData;
import com.ebookwriter.SaaS.dto.knowledge.NormalizedDocument;
import com.ebookwriter.SaaS.service.knowledge.KnowledgeChunker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Selects what Claude needs to write ONE chapter of a knowledge-based book —
 * small but sufficient, never the whole knowledge base or upload:
 * <ul>
 *   <li><b>book context</b> (every chapter): title, audience, goal, promise,
 *       project, terminology and the author's intended order — so chapter 8
 *       knows what chapter 2 established;</li>
 *   <li><b>chapter knowledge</b>: the BookKnowledge items the blueprint mapped
 *       to this chapter, plus items that share this chapter's source files, ranked
 *       and capped (items another chapter owns are down-weighted);</li>
 *   <li><b>answers</b> the author gave for this chapter (and book-wide ones);</li>
 *   <li><b>open gaps</b> — what the author did NOT provide, so it is not invented;</li>
 *   <li><b>source excerpts</b>: short quotes of this chapter's own source files
 *       (the real code/config names), at most a few, size-capped — and the passages
 *       of the author's notes that belong to this chapter, in their own words.</li>
 * </ul>
 * Pure (no I/O): the caller passes the generation input and the documents.
 */
public final class KnowledgeChapterContext {

    /** What one chapter request receives. {@code excerptRefs}/{@code itemCount} are for logging and tests. */
    public record Context(String book, String knowledge, String answers, String gaps, String excerpts,
                          int itemCount, List<String> excerptRefs) {
        public int totalChars() {
            return book.length() + knowledge.length() + answers.length() + gaps.length() + excerpts.length();
        }
    }

    private static final Pattern NON_ALNUM = Pattern.compile("[^\\p{L}\\p{N}]+");
    static final int SELECTION_THRESHOLD = 7;

    private KnowledgeChapterContext() {
    }

    public static Context build(BookGenerationInput input, String blueprintChapterId,
                                Map<String, NormalizedDocument> documents, KnowledgeWritingProperties limits) {
        BookKnowledgeData k = input.knowledge();
        BlueprintData bp = input.blueprint();
        BlueprintData.Chapter chapter = bp.chapters().stream()
                .filter(c -> Objects.equals(c.id(), blueprintChapterId)).findFirst().orElse(null);

        String book = cap(bookContext(k, bp), limits.getMaxBookContextChars());
        if (chapter == null) {
            return new Context(book, "", answers(input, null, limits), gaps(input, null), "", 0, List.of());
        }
        List<Item> selected = select(k, bp, chapter, limits.getMaxChapterKnowledgeChars());
        StringBuilder knowledge = new StringBuilder();
        if (!chapter.keyPoints().isEmpty()) {
            knowledge.append("Key points the blueprint assigned to this chapter:\n");
            for (BlueprintData.KeyPoint kp : chapter.keyPoints()) {
                knowledge.append("- ").append(kp.point()).append(sources(kp.sources())).append('\n');
            }
            knowledge.append('\n');
        }
        for (Item it : selected) knowledge.append("- ").append(it.text()).append(sources(it.sources())).append('\n');

        List<String> excerptRefs = new ArrayList<>();
        String excerpts = excerpts(chapter, documents, limits, excerptRefs);
        String notes = notesExcerpt(chapter, documents, limits);
        if (!notes.isEmpty()) {
            excerptRefs.add(KnowledgeChunker.NOTES_REF);
            excerpts = notes + (excerpts.isEmpty() ? "" : "\n\n" + excerpts);
        }
        return new Context(book, knowledge.toString().strip(), answers(input, chapter.id(), limits),
                gaps(input, chapter.id()), excerpts, selected.size(), excerptRefs);
    }

    // ---- book level -------------------------------------------------------------

    static String bookContext(BookKnowledgeData k, BlueprintData bp) {
        StringBuilder sb = new StringBuilder();
        line(sb, "Title", bp.workingTitle());
        line(sb, "Subtitle", bp.subtitle());
        line(sb, "Reader", bp.audience());
        line(sb, "Reader's goal", bp.readerGoal());
        line(sb, "Promise", bp.promise());
        line(sb, "Concept", bp.concept());
        BookKnowledgeData.ProjectInfo p = k.project();
        if (p != null) {
            sb.append("\nWHAT THE AUTHOR'S MATERIAL IS ABOUT\n");
            line(sb, "Name", p.name());
            line(sb, "Type", p.type());
            line(sb, "What it is", p.description());
            line(sb, "Domain", p.domain());
            if (!p.technologies().isEmpty()) line(sb, "Tools, methods and technologies", String.join(", ", p.technologies()));
        }
        if (!k.terminology().isEmpty()) {
            sb.append("\nTERMINOLOGY (use these names consistently in every chapter)\n");
            for (BookKnowledgeData.Term t : k.terminology()) {
                sb.append("- ").append(t.term());
                if (t.definition() != null) sb.append(": ").append(t.definition());
                sb.append('\n');
            }
        }
        if (!k.intendedSequence().isEmpty()) {
            sb.append("\nTHE AUTHOR'S OWN ORDER OF WORK\n");
            int i = 1;
            for (BookKnowledgeData.SequenceStep s : k.intendedSequence()) sb.append(i++).append(". ").append(s.step()).append('\n');
        }
        return sb.toString().strip();
    }

    // ---- chapter knowledge selection --------------------------------------------------

    record Item(String type, String name, String text, List<String> sources) {
    }

    static List<Item> select(BookKnowledgeData k, BlueprintData bp, BlueprintData.Chapter chapter, int maxChars) {
        Set<String> chapterSources = new HashSet<>(chapter.sourceReferences());
        Set<String> mine = new HashSet<>();
        for (BlueprintData.KnowledgeRef r : chapter.knowledgeReferences()) mine.add(key(r.type(), r.name()));
        Set<String> others = new HashSet<>();
        for (BlueprintData.Chapter c : bp.chapters()) {
            if (c.id().equals(chapter.id())) continue;
            for (BlueprintData.KnowledgeRef r : c.knowledgeReferences()) others.add(key(r.type(), r.name()));
        }
        Set<String> words = new HashSet<>(tokens(chapter.title()));
        for (String t : chapter.topics()) words.addAll(tokens(t));

        record Scored(Item item, int score, int seq) {
        }
        List<Scored> scored = new ArrayList<>();
        int seq = 0;
        for (Item it : items(k)) {
            String key = key(it.type(), it.name());
            int score = 0;
            if (mine.contains(key)) score += 100;
            int shared = 0;
            boolean sharedNotes = false;
            for (String s : it.sources()) {
                if (!chapterSources.contains(s)) continue;
                if (s.equals(KnowledgeChunker.NOTES_REF)) sharedNotes = true;
                else shared++;
            }
            score += shared * 10 + (sharedNotes ? 2 : 0);
            if (!words.isEmpty() && tokens(it.name()).stream().anyMatch(words::contains)) score += 5;
            if (score < 100 && others.contains(key)) score -= 8;
            if (score >= SELECTION_THRESHOLD) scored.add(new Scored(it, score, seq++));
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed().thenComparingInt(Scored::seq));

        List<Item> out = new ArrayList<>();
        int used = 0;
        for (Scored s : scored) {
            int len = s.item().text().length() + 60;
            if (used + len > maxChars && !out.isEmpty()) {
                if (s.score() >= 100) continue; // try smaller explicit items
                break;
            }
            out.add(s.item());
            used += len;
        }
        return out;
    }

    /** Every BookKnowledge item as a renderable line (project-wide lists only). */
    static List<Item> items(BookKnowledgeData k) {
        List<Item> out = new ArrayList<>();
        for (var t : k.topics()) out.add(new Item("topic", t.name(), "Topic — " + t.name() + desc(t.description()), t.sources()));
        for (var p : k.processes()) {
            StringBuilder sb = new StringBuilder("Process — " + p.name() + desc(p.description()));
            if (!p.steps().isEmpty()) {
                sb.append(" Steps: ");
                for (int i = 0; i < p.steps().size(); i++) sb.append(i + 1).append(") ").append(p.steps().get(i)).append(' ');
            }
            out.add(new Item("process", p.name(), sb.toString().strip(), p.sources()));
        }
        for (var u : k.userInsights()) {
            out.add(new Item("userInsight", u.insight(), "AUTHOR'S OWN EXPERIENCE"
                    + (u.kind() != null ? " (" + u.kind() + ")" : "") + " — " + u.insight(), u.sources()));
        }
        for (var d : k.importantDetails()) {
            out.add(new Item("importantDetail", d.detail(), "Must cover — " + d.detail()
                    + (d.whyItMatters() != null ? " (" + d.whyItMatters() + ")" : ""), d.sources()));
        }
        for (var e : k.examples()) {
            String text = "Example from the materials — " + e.title() + desc(e.description())
                    + (e.snippet() != null ? "\n  ```\n  " + e.snippet().strip().replace("\n", "\n  ") + "\n  ```" : "");
            out.add(new Item("example", e.title(), text, e.sources()));
        }
        for (var t : k.technicalDetails()) {
            out.add(new Item("technicalDetail", t.detail(), "Technical detail — "
                    + (t.area() != null ? t.area() + ": " : "") + t.detail(), t.sources()));
        }
        for (var f : k.facts()) out.add(new Item("fact", f.fact(), "Fact — " + f.fact(), f.sources()));
        return out;
    }

    // ---- answers, gaps, excerpts -----------------------------------------------------

    static String answers(BookGenerationInput input, String chapterId, KnowledgeWritingProperties limits) {
        StringBuilder sb = new StringBuilder();
        for (BookGenerationInput.Answer a : input.answers()) {
            boolean forChapter = chapterId != null && chapterId.equals(a.chapterId());
            if (!forChapter && a.chapterId() != null) continue;
            String entry = "Q: " + a.question() + "\nA (the author's own words" + (forChapter ? "" : ", book-wide")
                    + "): " + a.answer() + "\n\n";
            if (sb.length() + entry.length() > limits.getMaxAnswersChars()) break;
            sb.append(entry);
        }
        return sb.toString().strip();
    }

    static String gaps(BookGenerationInput input, String chapterId) {
        StringBuilder sb = new StringBuilder();
        for (BlueprintData.Gap g : input.unresolvedGaps()) {
            boolean relevant = g.chapterIds().isEmpty() || (chapterId != null && g.chapterIds().contains(chapterId));
            if (relevant) sb.append("- ").append(g.description()).append('\n');
        }
        return sb.toString().strip();
    }

    private static final List<NormalizedDocument.Kind> EXCERPT_ORDER = List.of(
            NormalizedDocument.Kind.CODE, NormalizedDocument.Kind.BUILD, NormalizedDocument.Kind.CONFIG,
            NormalizedDocument.Kind.STRUCTURE, NormalizedDocument.Kind.DOCUMENT, NormalizedDocument.Kind.DATA,
            NormalizedDocument.Kind.TEST);

    static String excerpts(BlueprintData.Chapter chapter, Map<String, NormalizedDocument> documents,
                           KnowledgeWritingProperties limits, List<String> refsOut) {
        List<String> refs = new ArrayList<>(new LinkedHashSet<>(chapter.sourceReferences()));
        refs.removeIf(r -> !documents.containsKey(r) || documents.get(r).kind() == NormalizedDocument.Kind.NOTES);
        refs.sort(Comparator.comparingInt(r -> {
            int i = EXCERPT_ORDER.indexOf(documents.get(r).kind());
            return i < 0 ? 99 : i;
        }));
        StringBuilder sb = new StringBuilder();
        for (String ref : refs) {
            if (refsOut.size() >= limits.getMaxExcerpts()) break;
            NormalizedDocument d = documents.get(ref);
            String body = d.content().length() > limits.getMaxExcerptChars()
                    ? d.content().substring(0, limits.getMaxExcerptChars()) + "\n[… rest of the file omitted …]"
                    : d.content();
            String block = "--- FILE: " + ref + (d.language() != null ? " (" + d.language() + ")" : "") + " ---\n"
                    + body + "\n--- END FILE ---\n\n";
            if (sb.length() + block.length() > limits.getMaxExcerptTotalChars() && !refsOut.isEmpty()) break;
            sb.append(block);
            refsOut.add(ref);
        }
        return sb.toString().strip();
    }

    /**
     * The passages of the author's pasted notes that belong to this chapter, quoted
     * in the author's own words. Notes are often the whole material of a book that
     * is not about software (a photographer's, trainer's or editor's notes), so the
     * writer must see the original wording and values — not only the extracted
     * summary. Notes can be long and cover the whole book, so only the blocks that
     * share the most words with this chapter's title, topics and key points are
     * quoted (in their original order), up to one excerpt's size. Empty when the
     * chapter does not draw on the notes.
     */
    static String notesExcerpt(BlueprintData.Chapter chapter, Map<String, NormalizedDocument> documents,
                               KnowledgeWritingProperties limits) {
        NormalizedDocument notes = documents.get(KnowledgeChunker.NOTES_REF);
        if (notes == null || notes.content() == null || notes.content().isBlank()
                || !chapter.sourceReferences().contains(KnowledgeChunker.NOTES_REF)) {
            return "";
        }
        int budget = limits.getMaxExcerptChars();
        Set<String> words = new HashSet<>(tokens(chapter.title()));
        for (String t : chapter.topics()) words.addAll(tokens(t));
        for (BlueprintData.KeyPoint kp : chapter.keyPoints()) words.addAll(tokens(kp.point()));
        List<String> blocks = noteBlocks(notes.content(), Math.max(200, budget / 4));

        String body;
        if (notes.content().length() <= budget) {
            body = notes.content().strip();
        } else {
            record Scored(int index, int score) {
            }
            List<Scored> scored = new ArrayList<>();
            for (int i = 0; i < blocks.size(); i++) {
                int score = 0;
                for (String w : tokens(blocks.get(i))) if (words.contains(w)) score++;
                if (score > 0) scored.add(new Scored(i, score));
            }
            scored.sort(Comparator.comparingInt(Scored::score).reversed().thenComparingInt(Scored::index));
            Set<Integer> picked = new java.util.TreeSet<>();
            int used = 0;
            for (Scored sc : scored) {
                int len = blocks.get(sc.index()).length() + 2;
                if (used + len > budget) continue;
                picked.add(sc.index());
                used += len;
            }
            if (picked.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            int previous = -2;
            for (int i : picked) {
                if (sb.length() > 0) sb.append(i == previous + 1 ? "\n" : "\n[…]\n");
                sb.append(blocks.get(i));
                previous = i;
            }
            body = sb.toString();
        }
        return "--- THE AUTHOR'S NOTES (passages for this chapter, in the author's own words) ---\n"
                + body + "\n--- END NOTES ---";
    }

    /** Notes split at blank lines into blocks; overlong blocks are split at line ends near {@code maxBlock}. */
    static List<String> noteBlocks(String text, int maxBlock) {
        List<String> out = new ArrayList<>();
        for (String para : text.strip().split("\\n\\s*\\n")) {
            String p = para.strip();
            if (p.isEmpty()) continue;
            if (p.length() <= maxBlock) {
                out.add(p);
                continue;
            }
            StringBuilder cur = new StringBuilder();
            for (String line : p.split("\\n")) {
                if (cur.length() > 0 && cur.length() + line.length() + 1 > maxBlock) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                if (cur.length() > 0) cur.append('\n');
                cur.append(line);
            }
            if (cur.length() > 0) out.add(cur.toString());
        }
        return out;
    }

    // ---- helpers ------------------------------------------------------------------

    private static String key(String type, String name) {
        return (type == null ? "" : type.toLowerCase(Locale.ROOT)) + "|" + norm(name);
    }

    private static String norm(String s) {
        return s == null ? "" : NON_ALNUM.matcher(s.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
    }

    /** Meaningful words (≥ 3 chars) of a phrase. */
    private static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        for (String w : norm(s).split(" ")) if (w.length() >= 3 && !STOP.contains(w)) out.add(w);
        return out;
    }

    private static final Set<String> STOP = Set.of("the", "and", "for", "with", "from", "into", "your", "our",
            "what", "how", "why", "this", "that", "are", "was", "using", "building", "setting", "project");

    private static String desc(String d) {
        return d == null || d.isBlank() ? "" : ": " + d.strip();
    }

    private static String sources(List<String> s) {
        return s.isEmpty() ? "" : "  [from: " + String.join(", ", s) + "]";
    }

    private static void line(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) sb.append(label).append(": ").append(value.strip()).append('\n');
    }

    private static String cap(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "\n[…]";
    }
}
