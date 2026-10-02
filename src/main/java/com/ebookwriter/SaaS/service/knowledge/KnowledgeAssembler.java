package com.ebookwriter.SaaS.service.knowledge;

import com.ebookwriter.SaaS.dto.knowledge.BookKnowledgeData;
import com.ebookwriter.SaaS.dto.knowledge.BookKnowledgeData.*;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Turns raw model output into trustworthy {@link BookKnowledgeData}:
 * <ul>
 *   <li><b>parse</b> — lenient JSON → records (unknown fields ignored, missing
 *       lists empty, blank items dropped);</li>
 *   <li><b>resolve sources</b> — every cited source is checked against the real
 *       documents of the run: exact ref, case-insensitive, a bare filename that
 *       matches exactly one document, or a notes alias. Citations that match
 *       nothing are dropped, so a stored reference always points at real
 *       material;</li>
 *   <li><b>merge</b> — combines per-batch results deterministically (same item
 *       from two batches → one item with the union of sources). Used as the
 *       input to the consolidation call, and as the fallback if that call fails.</li>
 * </ul>
 * Pure (no I/O, no Spring) so it is directly unit-testable.
 */
public final class KnowledgeAssembler {

    static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true)
            .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

    /** Most items kept per list after a merge (keeps the stored knowledge and later prompts bounded). */
    static final int MAX_ITEMS_PER_LIST = 60;

    private static final Pattern PART_SUFFIX = Pattern.compile("\\s*\\(part \\d+/\\d+\\)\\s*$");
    private static final Pattern NON_ALNUM = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Set<String> NOTES_ALIASES = Set.of(
            "user-notes", "user notes", "notes", "user-provided notes", "author notes", "author's notes", "pasted notes");

    private KnowledgeAssembler() {
    }

    // ---- Parse -----------------------------------------------------------------

    public static BookKnowledgeData parse(JsonNode json) {
        try {
            return MAPPER.treeToValue(json, BookKnowledgeData.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Model output does not match the knowledge schema: " + e.getMessage(), e);
        }
    }

    public static BookKnowledgeData read(String json) {
        try {
            return MAPPER.readValue(json, BookKnowledgeData.class);
        } catch (Exception e) {
            throw new IllegalStateException("Stored knowledge is unreadable: " + e.getMessage(), e);
        }
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise knowledge", e);
        }
    }

    // ---- Source resolution -------------------------------------------------------

    /** Resolves cited source labels to the run's real document refs. */
    public static final class SourceResolver {
        private final Map<String, String> exact = new HashMap<>();
        private final Map<String, String> lower = new HashMap<>();
        private final Map<String, List<String>> byFilename = new HashMap<>();
        private final Map<String, List<String>> bySuffix = new HashMap<>();
        private final String notesRef;

        public SourceResolver(Collection<String> refs) {
            String notes = null;
            for (String ref : refs) {
                exact.put(ref, ref);
                lower.putIfAbsent(ref.toLowerCase(Locale.ROOT), ref);
                String path = ref.contains("!/") ? ref.substring(ref.indexOf("!/") + 2) : ref;
                String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
                byFilename.computeIfAbsent(name, k -> new ArrayList<>()).add(ref);
                bySuffix.computeIfAbsent(path.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(ref);
                if (ref.equals(KnowledgeChunker.NOTES_REF)) notes = ref;
            }
            this.notesRef = notes;
        }

        /** The real ref for a citation, or null when it matches no document (unambiguously). */
        public String resolve(String cited) {
            if (cited == null) return null;
            String c = cited.strip();
            if (c.regionMatches(true, 0, "SOURCE:", 0, 7)) c = c.substring(7).strip();
            c = PART_SUFFIX.matcher(c).replaceAll("");
            // Quotes first; brackets only after an exact try, since refs may themselves
            // end in ")" ("my-shop.zip (structure)").
            c = c.replaceAll("^[\"'`]+|[\"'`]+$", "").strip();
            if (exact.containsKey(c)) return exact.get(c);
            if (lower.containsKey(c.toLowerCase(Locale.ROOT))) return lower.get(c.toLowerCase(Locale.ROOT));
            c = c.replaceAll("^[\\[(]+|[\\])]+$", "").strip();
            int pipe = c.indexOf(" | ");
            if (pipe > 0) c = c.substring(0, pipe).strip();
            if (c.isEmpty()) return null;

            if (exact.containsKey(c)) return exact.get(c);
            String lc = c.toLowerCase(Locale.ROOT);
            if (lower.containsKey(lc)) return lower.get(lc);
            if (notesRef != null && NOTES_ALIASES.contains(lc)) return notesRef;

            String path = lc.contains("!/") ? lc.substring(lc.indexOf("!/") + 2) : lc;
            while (path.startsWith("./") || path.startsWith("/")) path = path.substring(path.indexOf('/') + 1);
            List<String> sameSuffix = bySuffix.get(path);
            if (sameSuffix != null && sameSuffix.size() == 1) return sameSuffix.get(0);
            // A path missing its leading folders ("security/SecurityConfig.java").
            String finalPath = path;
            List<String> endsWith = bySuffix.keySet().stream()
                    .filter(k -> k.endsWith("/" + finalPath))
                    .flatMap(k -> bySuffix.get(k).stream())
                    .toList();
            if (endsWith.size() == 1) return endsWith.get(0);
            if (!path.contains("/")) {
                List<String> named = byFilename.get(path);
                if (named != null && named.size() == 1) return named.get(0);
            }
            return null;
        }

        List<String> resolveAll(List<String> cited) {
            Set<String> out = new LinkedHashSet<>();
            for (String c : cited) {
                String r = resolve(c);
                if (r != null) out.add(r);
            }
            return List.copyOf(out);
        }
    }

    /** Replace every item's sources with resolved refs and drop empty items. */
    public static BookKnowledgeData resolveSources(BookKnowledgeData d, SourceResolver r) {
        ProjectInfo project = d.project() == null ? null : new ProjectInfo(
                blankToNull(d.project().name()), blankToNull(d.project().type()), blankToNull(d.project().description()),
                blankToNull(d.project().domain()), cleanStrings(d.project().technologies()), r.resolveAll(d.project().sources()));
        return new BookKnowledgeData(d.schemaVersion(), d.book(), project, blankToNull(d.overallSummary()),
                map(d.topics(), t -> blank(t.name()) ? null
                        : new Topic(t.name().strip(), t.description(), normImportance(t.importance()), r.resolveAll(t.sources()))),
                map(d.processes(), p -> blank(p.name()) ? null
                        : new ProcessInfo(p.name().strip(), p.description(), cleanStrings(p.steps()), r.resolveAll(p.sources()))),
                map(d.examples(), e -> blank(e.title()) && blank(e.description()) ? null
                        : new Example(e.title(), e.description(), e.kind(), blankToNull(e.snippet()), r.resolveAll(e.sources()))),
                map(d.importantDetails(), x -> blank(x.detail()) ? null
                        : new ImportantDetail(x.detail().strip(), x.whyItMatters(), r.resolveAll(x.sources()))),
                map(d.terminology(), t -> blank(t.term()) ? null
                        : new Term(t.term().strip(), t.definition(), r.resolveAll(t.sources()))),
                map(d.userInsights(), u -> blank(u.insight()) ? null
                        : new UserInsight(u.insight().strip(), u.kind(), r.resolveAll(u.sources()))),
                map(d.technicalDetails(), t -> blank(t.detail()) ? null
                        : new TechnicalDetail(t.area(), t.detail().strip(), r.resolveAll(t.sources()))),
                map(d.facts(), f -> blank(f.fact()) ? null : new Fact(f.fact().strip(), r.resolveAll(f.sources()))),
                map(d.intendedSequence(), s -> blank(s.step()) ? null
                        : new SequenceStep(s.step().strip(), r.resolveAll(s.sources()))),
                map(d.knowledgeGaps(), g -> blank(g.question()) ? null
                        : new KnowledgeGap(g.question().strip(), g.whyItMatters(), g.relatedTopic(), r.resolveAll(g.sources()))),
                d.sources(), d.coverage());
    }

    // ---- Merge -------------------------------------------------------------------

    /** Deterministically merge per-batch results (first occurrence wins; sources are unioned). */
    public static BookKnowledgeData merge(List<BookKnowledgeData> parts) {
        if (parts.size() == 1) return parts.get(0);
        List<ProjectInfo> projects = parts.stream().map(BookKnowledgeData::project).filter(p -> p != null).toList();
        ProjectInfo project = projects.isEmpty() ? null : new ProjectInfo(
                first(projects, ProjectInfo::name), first(projects, ProjectInfo::type),
                longest(projects, ProjectInfo::description), first(projects, ProjectInfo::domain),
                unionStrings(projects.stream().flatMap(p -> p.technologies().stream()).toList()),
                unionStrings(projects.stream().flatMap(p -> p.sources().stream()).toList()));

        StringBuilder summary = new StringBuilder();
        for (BookKnowledgeData p : parts) {
            if (!blank(p.overallSummary())) {
                if (summary.length() > 0) summary.append(' ');
                summary.append(p.overallSummary().strip());
            }
        }
        String overall = summary.length() > 4_000 ? summary.substring(0, 4_000) + "…" : summary.toString();

        return new BookKnowledgeData(BookKnowledgeData.CURRENT_SCHEMA_VERSION, parts.get(0).book(), project,
                blankToNull(overall),
                mergeList(parts, BookKnowledgeData::topics, Topic::name, (a, b) ->
                        new Topic(a.name(), longer(a.description(), b.description()), higher(a.importance(), b.importance()),
                                union(a.sources(), b.sources()))),
                mergeList(parts, BookKnowledgeData::processes, ProcessInfo::name, (a, b) ->
                        new ProcessInfo(a.name(), longer(a.description(), b.description()),
                                a.steps().size() >= b.steps().size() ? a.steps() : b.steps(), union(a.sources(), b.sources()))),
                mergeList(parts, BookKnowledgeData::examples, e -> e.title() + "|" + e.description(), (a, b) ->
                        new Example(a.title(), a.description(), a.kind(), a.snippet() != null ? a.snippet() : b.snippet(),
                                union(a.sources(), b.sources()))),
                mergeList(parts, BookKnowledgeData::importantDetails, ImportantDetail::detail, (a, b) ->
                        new ImportantDetail(a.detail(), longer(a.whyItMatters(), b.whyItMatters()), union(a.sources(), b.sources()))),
                mergeList(parts, BookKnowledgeData::terminology, Term::term, (a, b) ->
                        new Term(a.term(), longer(a.definition(), b.definition()), union(a.sources(), b.sources()))),
                mergeList(parts, BookKnowledgeData::userInsights, UserInsight::insight, (a, b) ->
                        new UserInsight(a.insight(), a.kind(), union(a.sources(), b.sources()))),
                mergeList(parts, BookKnowledgeData::technicalDetails, t -> t.area() + "|" + t.detail(), (a, b) ->
                        new TechnicalDetail(a.area(), a.detail(), union(a.sources(), b.sources()))),
                mergeList(parts, BookKnowledgeData::facts, Fact::fact, (a, b) ->
                        new Fact(a.fact(), union(a.sources(), b.sources()))),
                mergeList(parts, BookKnowledgeData::intendedSequence, SequenceStep::step, (a, b) ->
                        new SequenceStep(a.step(), union(a.sources(), b.sources()))),
                mergeList(parts, BookKnowledgeData::knowledgeGaps, KnowledgeGap::question, (a, b) ->
                        new KnowledgeGap(a.question(), longer(a.whyItMatters(), b.whyItMatters()),
                                a.relatedTopic() != null ? a.relatedTopic() : b.relatedTopic(), union(a.sources(), b.sources()))),
                List.of(), null);
    }

    /** Cap every list (used before sending merged knowledge back to the model). */
    public static BookKnowledgeData capLists(BookKnowledgeData d, int max) {
        return new BookKnowledgeData(d.schemaVersion(), d.book(), d.project(), d.overallSummary(),
                cap(d.topics(), max), cap(d.processes(), max), cap(d.examples(), max), cap(d.importantDetails(), max),
                cap(d.terminology(), max), cap(d.userInsights(), max), cap(d.technicalDetails(), max), cap(d.facts(), max),
                cap(d.intendedSequence(), max), cap(d.knowledgeGaps(), max), d.sources(), d.coverage());
    }

    // ---- helpers -----------------------------------------------------------------

    private static <T> List<T> mergeList(List<BookKnowledgeData> parts, Function<BookKnowledgeData, List<T>> list,
                                         Function<T, String> key, BiFunction<T, T, T> combine) {
        Map<String, T> merged = new LinkedHashMap<>();
        for (BookKnowledgeData part : parts) {
            for (T item : list.apply(part)) {
                merged.merge(normKey(key.apply(item)), item, combine);
            }
        }
        return cap(new ArrayList<>(merged.values()), MAX_ITEMS_PER_LIST);
    }

    static String normKey(String s) {
        return s == null ? "" : NON_ALNUM.matcher(s.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
    }

    private static <T> List<T> map(List<T> items, Function<T, T> f) {
        List<T> out = new ArrayList<>();
        for (T item : items) {
            T mapped = f.apply(item);
            if (mapped != null) out.add(mapped);
        }
        return out;
    }

    private static <T> List<T> cap(List<T> list, int max) {
        return list.size() <= max ? list : List.copyOf(list.subList(0, max));
    }

    private static List<String> union(List<String> a, List<String> b) {
        Set<String> s = new LinkedHashSet<>(a);
        s.addAll(b);
        return List.copyOf(s);
    }

    private static List<String> unionStrings(List<String> values) {
        Map<String, String> byKey = new LinkedHashMap<>();
        for (String v : values) {
            if (!blank(v)) byKey.putIfAbsent(normKey(v), v.strip());
        }
        return List.copyOf(byKey.values());
    }

    private static List<String> cleanStrings(List<String> values) {
        return values.stream().filter(v -> !blank(v)).map(String::strip).toList();
    }

    private static <T> String first(List<T> items, Function<T, String> f) {
        return items.stream().map(f).filter(v -> !blank(v)).findFirst().orElse(null);
    }

    private static <T> String longest(List<T> items, Function<T, String> f) {
        return items.stream().map(f).filter(v -> !blank(v)).reduce(KnowledgeAssembler::longer).orElse(null);
    }

    private static String longer(String a, String b) {
        if (blank(a)) return b;
        if (blank(b)) return a;
        return b.length() > a.length() ? b : a;
    }

    private static String normImportance(String s) {
        if (s == null) return "medium";
        String l = s.toLowerCase(Locale.ROOT).strip();
        return switch (l) {
            case "high", "medium", "low" -> l;
            default -> "medium";
        };
    }

    private static String higher(String a, String b) {
        List<String> order = List.of("low", "medium", "high");
        return order.indexOf(normImportance(b)) > order.indexOf(normImportance(a)) ? normImportance(b) : normImportance(a);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return blank(s) ? null : s.strip();
    }
}
