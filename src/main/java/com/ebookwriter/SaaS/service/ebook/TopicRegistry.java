package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.entity.EbookChapter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The book's register of what has already been explained, and where: one short
 * line per topic ("topic — gist"), never the text itself. Each chapter
 * contributes the entries the writer listed after its summary plus, read from
 * its Markdown, the first sentence of every box it printed (a warning, a tip, an
 * example…), so a box is never given twice. Every later chapter is written — and
 * edited — with the entries of the chapters before it and the rule to refer back
 * instead of explaining again.
 *
 * <p>Pure and static; nothing here depends on the book's genre or language.
 */
public final class TopicRegistry {

    private TopicRegistry() {
    }

    /** Entries kept per chapter. */
    static final int MAX_ENTRIES_PER_CHAPTER = 20;
    /** Longest entry kept (internal text: longer ones are shortened, never printed). */
    static final int MAX_ENTRY_CHARS = 200;
    /** Size of the registry handed to a prompt; older chapters keep fewer entries beyond it. */
    static final int MAX_REGISTRY_CHARS = 12_000;

    static final String BOX_PREFIX = "Box (";

    private static final Pattern DIRECTIVE_OPEN = Pattern.compile("^:::\\s*([a-zA-Z][\\w-]*)\\s*(.*)$");
    private static final Pattern DIRECTIVE_CLOSE = Pattern.compile("^:::\\s*$");
    /** Boxes that state something (as opposed to plans, diagrams and checklists). */
    private static final Set<String> STATEMENT_BOXES = Set.of(
            "warning", "caution", "mistake", "common-mistake", "tip", "note", "info", "key-idea", "keyidea",
            "key", "example", "takeaway", "takeaways", "summary", "pullquote", "pull-quote", "quote");

    /**
     * The registry entries of one chapter: the writer's own list (the section
     * after the topics delimiter) plus one entry per box the chapter printed.
     * Returned as stored on the chapter: one entry per line.
     */
    public static String entries(String writerTopics, String markdown) {
        Set<String> out = new LinkedHashSet<>();
        if (writerTopics != null) {
            for (String line : writerTopics.split("\n")) {
                String e = clean(line);
                // Box entries are always re-read from the Markdown below, so an
                // edited chapter's boxes replace the old ones.
                if (!e.isEmpty() && !e.startsWith(BOX_PREFIX)) {
                    out.add(e);
                }
            }
        }
        out.addAll(boxEntries(markdown));
        List<String> kept = new ArrayList<>(out);
        return String.join("\n", kept.subList(0, Math.min(kept.size(), MAX_ENTRIES_PER_CHAPTER)));
    }

    /** One entry per statement box in the chapter: "Box (type): its first sentence". */
    static List<String> boxEntries(String markdown) {
        List<String> out = new ArrayList<>();
        if (markdown == null) {
            return out;
        }
        String[] lines = markdown.split("\n");
        for (int i = 0; i < lines.length; i++) {
            Matcher open = DIRECTIVE_OPEN.matcher(lines[i].strip());
            if (!open.matches() || !STATEMENT_BOXES.contains(open.group(1).toLowerCase(Locale.ROOT))) {
                continue;
            }
            StringBuilder body = new StringBuilder();
            for (int j = i + 1; j < lines.length && !DIRECTIVE_CLOSE.matcher(lines[j].strip()).matches(); j++) {
                if (DIRECTIVE_OPEN.matcher(lines[j].strip()).matches()) {
                    break;
                }
                body.append(lines[j].strip()).append(' ');
            }
            String title = open.group(2).strip();
            String first = firstSentence(body.toString().replaceAll("[*_`#>]", "").strip());
            if (!first.isEmpty() || !title.isEmpty()) {
                String what = title.isEmpty() ? first : title + (first.isEmpty() ? "" : ": " + first);
                out.add(clean(BOX_PREFIX + open.group(1).toLowerCase(Locale.ROOT) + ") — " + what));
            }
        }
        return out;
    }

    /**
     * What a chapter is written with: the entries of every chapter in the book
     * before {@code chapterNumber}, as "Chapter N: entry" lines. When the whole
     * register outgrows {@link #MAX_REGISTRY_CHARS}, each chapter keeps its
     * first (most important) entries, so every earlier chapter stays represented.
     */
    public static String forChapter(List<EbookChapter> inBook, int chapterNumber) {
        Map<Integer, List<String>> byChapter = new LinkedHashMap<>();
        for (EbookChapter c : inBook) {
            if (c.getChapterNumber() >= chapterNumber || c.getCoveredTopics() == null) {
                continue;
            }
            List<String> entries = new ArrayList<>();
            for (String line : c.getCoveredTopics().split("\n")) {
                String e = clean(line);
                if (!e.isEmpty()) {
                    entries.add(e);
                }
            }
            if (!entries.isEmpty()) {
                byChapter.put(c.getChapterNumber(), entries);
            }
        }
        for (int keep = MAX_ENTRIES_PER_CHAPTER; keep >= 1; keep--) {
            String text = render(byChapter, keep);
            if (text.length() <= MAX_REGISTRY_CHARS || keep == 1) {
                return text;
            }
        }
        return "";
    }

    private static String render(Map<Integer, List<String>> byChapter, int keep) {
        StringBuilder sb = new StringBuilder();
        byChapter.forEach((n, entries) -> {
            for (String e : entries.subList(0, Math.min(keep, entries.size()))) {
                sb.append("- Chapter ").append(n).append(": ").append(e).append('\n');
            }
        });
        return sb.toString().strip();
    }

    /**
     * Topics the register shows in more than one chapter (compared by the part
     * before the "—", case- and punctuation-insensitively): the same thing
     * explained again. Map: topic → the chapters, in book order.
     */
    public static Map<String, List<Integer>> repeated(List<EbookChapter> inBook) {
        Map<String, List<Integer>> where = new LinkedHashMap<>();
        Map<String, String> label = new LinkedHashMap<>();
        for (EbookChapter c : inBook) {
            if (c.getCoveredTopics() == null) {
                continue;
            }
            for (String raw : c.getCoveredTopics().split("\n")) {
                String line = clean(raw);
                // A box is identified by what it says; a topic by its name.
                String topic = line.startsWith(BOX_PREFIX)
                        ? line.substring(line.indexOf('—') + 1)
                        : line.split("—|–| - |:", 2)[0];
                String key = BookTemplate.normalise(topic);
                if (key.length() < 3) {
                    continue;
                }
                label.putIfAbsent(key, topic.strip());
                List<Integer> chapters = where.computeIfAbsent(key, k -> new ArrayList<>());
                if (!chapters.contains(c.getChapterNumber())) {
                    chapters.add(c.getChapterNumber());
                }
            }
        }
        Map<String, List<Integer>> out = new LinkedHashMap<>();
        where.forEach((key, chapters) -> {
            if (chapters.size() > 1) {
                out.put(label.get(key), chapters);
            }
        });
        return out;
    }

    private static String clean(String line) {
        String e = line.strip().replaceFirst("^(?:[-*+•]|\\d+[.)])\\s*", "").replaceAll("\\s+", " ").strip();
        return e.length() > MAX_ENTRY_CHARS ? e.substring(0, MAX_ENTRY_CHARS).strip() : e;
    }

    private static String firstSentence(String text) {
        Matcher m = Pattern.compile("^(.+?[.!?。！？])(\\s|$)").matcher(text);
        String s = m.find() ? m.group(1) : text;
        return s.length() > 140 ? s.substring(0, 140).strip() : s;
    }
}
