package com.ebookwriter.SaaS.service.blueprint;

import com.ebookwriter.SaaS.dto.blueprint.BlueprintData;
import com.ebookwriter.SaaS.dto.blueprint.BlueprintData.Chapter;
import com.ebookwriter.SaaS.dto.blueprint.BlueprintData.Gap;
import com.ebookwriter.SaaS.dto.blueprint.BlueprintData.KeyPoint;
import com.ebookwriter.SaaS.dto.blueprint.BlueprintData.KnowledgeRef;
import com.ebookwriter.SaaS.dto.knowledge.BookKnowledgeData;
import com.ebookwriter.SaaS.service.knowledge.KnowledgeAssembler;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Turns the planner's raw JSON into a trustworthy {@link BlueprintData} plus the
 * questions to ask. Pure (no I/O, no Spring) so the whole knowledge → chapter
 * mapping logic is unit-testable:
 * <ul>
 *   <li><b>knowledge references</b> are checked against the real BookKnowledge
 *       items (type + name; tolerant of small wording differences); invented
 *       ones are dropped;</li>
 *   <li><b>source references</b> are resolved against the knowledge's source
 *       refs, and every chapter also inherits the sources of the knowledge items
 *       it uses — so CHAPTER → KNOWLEDGE → SOURCES always holds;</li>
 *   <li><b>gaps</b> get stable ids and are linked both ways to chapters; gaps the
 *       materials can answer ({@code canBeInferred}) are dropped;</li>
 *   <li><b>questions</b> are de-duplicated (one per gap), ordered by priority and
 *       capped — a few questions, never a form; gaps left unasked stay
 *       NOT_ASKED (open, never invented);</li>
 *   <li>on regeneration, the author's <b>edited / added chapters</b> and edited
 *       top-level fields are carried over instead of being overwritten.</li>
 * </ul>
 */
public final class BlueprintAssembler {

    public static final String ORIGIN_AI = "AI";
    public static final String ORIGIN_AUTHOR = "AUTHOR";

    public static final String GAP_OPEN = "OPEN";
    public static final String GAP_NOT_ASKED = "NOT_ASKED";
    public static final String GAP_ANSWERED = "ANSWERED";
    public static final String GAP_SKIPPED = "SKIPPED";

    private static final Pattern NON_ALNUM = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final List<String> SEVERITIES = List.of("critical", "important", "minor");

    private BlueprintAssembler() {
    }

    /** A question to store, linked to the assembled gap/chapter ids. */
    public record ProposedQuestion(String gapId, String chapterId, String question, String reason, int priority) {
    }

    /**
     * The assembled result.
     *
     * @param answerLinks already-answered question id → chapter id (from the planner)
     */
    public record Result(BlueprintData blueprint, List<ProposedQuestion> questions,
                         Map<String, String> answerLinks, List<String> warnings) {
    }

    /**
     * @param raw              the planner's JSON
     * @param knowledge        the book's knowledge (the only allowed grounding)
     * @param previous         the current blueprint (regeneration), or null
     * @param answeredIds      ids of questions the author already answered
     * @param maxQuestions     question cap
     * @param maxChapters      chapter cap
     */
    public static Result assemble(JsonNode raw, BookKnowledgeData knowledge, BlueprintData previous,
                                  Set<String> answeredIds, int maxQuestions, int maxChapters) {
        List<String> warnings = new ArrayList<>();
        KnowledgeIndex index = new KnowledgeIndex(knowledge);
        Set<String> previousIds = previous == null ? Set.of()
                : previous.chapters().stream().map(Chapter::id).collect(java.util.stream.Collectors.toSet());

        // ---- chapters -------------------------------------------------------------
        Map<String, String> chapterIdByKey = new HashMap<>();
        List<Chapter> chapters = new ArrayList<>();
        List<List<String>> chapterGapKeys = new ArrayList<>();
        for (JsonNode c : raw.path("chapters")) {
            String title = text(c, "title");
            if (title == null) continue;
            if (chapters.size() >= maxChapters) {
                warnings.add("The proposed structure was cut to " + maxChapters + " chapters.");
                break;
            }
            // Same chapter as in the current blueprint → keep its id (stable across rebuilds).
            String same = text(c, "sameAsChapterId");
            boolean reuse = same != null && previousIds.contains(same)
                    && chapters.stream().noneMatch(x -> x.id().equals(same));
            String id = reuse ? same : UUID.randomUUID().toString();
            String key = text(c, "key");
            if (key != null) chapterIdByKey.put(key, id);

            List<KnowledgeRef> refs = new ArrayList<>();
            Set<String> sources = new LinkedHashSet<>(index.resolveSources(strings(c.path("sourceReferences"))));
            for (JsonNode r : c.path("knowledgeReferences")) {
                KnowledgeIndex.Item item = index.find(text(r, "type"), text(r, "name"));
                if (item != null && refs.stream().noneMatch(x -> x.type().equals(item.type()) && x.name().equals(item.name()))) {
                    refs.add(new KnowledgeRef(item.type(), item.name()));
                    sources.addAll(item.sources());
                }
            }
            List<KeyPoint> keyPoints = new ArrayList<>();
            for (JsonNode kp : c.path("keyPoints")) {
                String point = kp.isTextual() ? kp.asText().strip() : text(kp, "point");
                if (point == null || point.isBlank()) continue;
                List<String> kpSources = index.resolveSources(strings(kp.path("sources")));
                sources.addAll(kpSources);
                keyPoints.add(new KeyPoint(point, kpSources));
            }
            chapters.add(new Chapter(id, chapters.size() + 1, title, text(c, "purpose"), strings(c.path("topics")),
                    keyPoints, refs, List.copyOf(sources), List.of(), ORIGIN_AI, false));
            chapterGapKeys.add(strings(c.path("gapKeys")));
        }
        if (chapters.isEmpty()) {
            throw new IllegalArgumentException("The planner returned no chapters");
        }

        // ---- gaps -------------------------------------------------------------------
        Map<String, String> gapIdByKey = new HashMap<>();
        Map<String, Gap> gaps = new LinkedHashMap<>();
        int inferred = 0;
        for (JsonNode g : raw.path("knowledgeGaps")) {
            String description = text(g, "description");
            if (description == null) continue;
            if (g.path("canBeInferred").asBoolean(false)) {
                inferred++;
                continue;
            }
            String id = "gap-" + UUID.randomUUID().toString().substring(0, 8);
            String key = text(g, "key");
            if (key != null) gapIdByKey.put(key, id);
            List<String> chapterIds = strings(g.path("chapterKeys")).stream()
                    .map(chapterIdByKey::get).filter(x -> x != null).distinct().toList();
            gaps.put(id, new Gap(id, description, text(g, "whyItMatters"), severity(text(g, "severity")),
                    chapterIds, GAP_NOT_ASKED, null));
        }
        // chapter.gapKeys → link both ways
        for (int i = 0; i < chapters.size(); i++) {
            Chapter ch = chapters.get(i);
            Set<String> gapIds = new LinkedHashSet<>();
            for (String key : chapterGapKeys.get(i)) {
                String gid = gapIdByKey.get(key);
                if (gid != null) gapIds.add(gid);
            }
            for (Gap gap : gaps.values()) if (gap.chapterIds().contains(ch.id())) gapIds.add(gap.id());
            for (String gid : gapIds) {
                Gap gap = gaps.get(gid);
                if (!gap.chapterIds().contains(ch.id())) {
                    List<String> ids = new ArrayList<>(gap.chapterIds());
                    ids.add(ch.id());
                    gaps.put(gid, new Gap(gap.id(), gap.description(), gap.whyItMatters(), gap.severity(), ids,
                            gap.status(), gap.questionId()));
                }
            }
            chapters.set(i, new Chapter(ch.id(), ch.order(), ch.title(), ch.purpose(), ch.topics(), ch.keyPoints(),
                    ch.knowledgeReferences(), ch.sourceReferences(), List.copyOf(gapIds), ch.origin(), ch.edited()));
        }

        // ---- questions: one per gap, by priority, capped ------------------------------
        record Candidate(String gapId, String chapterId, String question, String reason, int priority, int severityRank, int seq) {
        }
        List<Candidate> candidates = new ArrayList<>();
        int seq = 0;
        for (JsonNode q : raw.path("questions")) {
            String question = text(q, "question");
            if (question == null) continue;
            String gapKey = text(q, "gapKey");
            String gapId = gapKey == null ? null : gapIdByKey.get(gapKey);
            if (gapKey != null && gapId == null) continue; // its gap was inferable / unknown
            String chapterId = chapterIdByKey.get(String.valueOf(text(q, "chapterKey")));
            if (chapterId == null && gapId != null && !gaps.get(gapId).chapterIds().isEmpty()) {
                chapterId = gaps.get(gapId).chapterIds().get(0);
            }
            int priority = Math.max(1, Math.min(5, q.path("priority").asInt(3)));
            int sevRank = gapId == null ? 1 : SEVERITIES.indexOf(gaps.get(gapId).severity());
            candidates.add(new Candidate(gapId, chapterId, question, text(q, "reason"), priority, sevRank, seq++));
        }
        candidates.sort(Comparator.comparingInt(Candidate::priority).thenComparingInt(Candidate::severityRank)
                .thenComparingInt(Candidate::seq));
        List<ProposedQuestion> questions = new ArrayList<>();
        Set<String> askedGaps = new HashSet<>();
        Set<String> seenQuestions = new HashSet<>();
        int dropped = 0;
        for (Candidate c : candidates) {
            if (c.gapId() != null && !askedGaps.add(c.gapId())) continue;
            if (!seenQuestions.add(norm(c.question()))) continue;
            if (questions.size() >= Math.max(0, maxQuestions)) {
                dropped++;
                continue;
            }
            questions.add(new ProposedQuestion(c.gapId(), c.chapterId(), c.question(), c.reason(), c.priority()));
        }
        if (dropped > 0) {
            warnings.add(dropped + " lower-priority question(s) were not asked; those gaps stay open.");
        }
        if (inferred > 0) {
            warnings.add(inferred + " gap(s) can be worked out from your materials and were not asked about.");
        }

        // ---- answers the planner linked to chapters ------------------------------------
        Map<String, String> answerLinks = new LinkedHashMap<>();
        for (JsonNode link : raw.path("answerLinks")) {
            String qid = text(link, "questionId");
            String cid = chapterIdByKey.get(String.valueOf(text(link, "chapterKey")));
            if (qid != null && cid != null && answeredIds.contains(qid)) answerLinks.put(qid, cid);
        }

        BlueprintData data = new BlueprintData(BlueprintData.CURRENT_SCHEMA_VERSION, text(raw, "concept"),
                text(raw, "workingTitle"), text(raw, "subtitle"), text(raw, "audience"), text(raw, "readerGoal"),
                text(raw, "promise"), text(raw, "structureRationale"), chapters, List.copyOf(gaps.values()), List.of());

        if (previous != null) {
            data = preserveAuthorEdits(data, previous);
        }
        for (Chapter ch : data.chapters()) {
            if (ch.knowledgeReferences().isEmpty() && ch.sourceReferences().isEmpty() && !ORIGIN_AUTHOR.equals(ch.origin())) {
                warnings.add("Chapter \"" + ch.title() + "\" is not linked to any of your materials.");
            }
        }
        return new Result(data, questions, answerLinks, warnings);
    }

    /**
     * Carry the author's manual work into a regenerated blueprint: edited
     * top-level fields keep their values; edited/added chapters are kept (matched
     * by id — the planner's {@code sameAsChapterId} — or title, else re-inserted
     * at their previous position) with the author's title and purpose.
     */
    static BlueprintData preserveAuthorEdits(BlueprintData fresh, BlueprintData previous) {
        Set<String> fields = new LinkedHashSet<>(previous.userEditedFields());
        String concept = fields.contains("concept") ? previous.concept() : fresh.concept();
        String title = fields.contains("workingTitle") ? previous.workingTitle() : fresh.workingTitle();
        String subtitle = fields.contains("subtitle") ? previous.subtitle() : fresh.subtitle();
        String audience = fields.contains("audience") ? previous.audience() : fresh.audience();
        String goal = fields.contains("readerGoal") ? previous.readerGoal() : fresh.readerGoal();
        String promise = fields.contains("promise") ? previous.promise() : fresh.promise();

        List<Chapter> chapters = new ArrayList<>(fresh.chapters());
        List<Chapter> prev = previous.chapters();
        for (int i = 0; i < prev.size(); i++) {
            Chapter mine = prev.get(i);
            if (!mine.edited() && !ORIGIN_AUTHOR.equals(mine.origin())) continue;
            int match = -1;
            for (int j = 0; j < chapters.size() && match < 0; j++) {
                if (chapters.get(j).id().equals(mine.id())) match = j;
            }
            for (int j = 0; j < chapters.size() && match < 0; j++) {
                if (norm(chapters.get(j).title()).equals(norm(mine.title()))) match = j;
            }
            if (match >= 0) {
                Chapter ai = chapters.get(match);
                chapters.set(match, new Chapter(ai.id(), ai.order(), mine.title(), mine.purpose(),
                        mine.topics().isEmpty() ? ai.topics() : mine.topics(), ai.keyPoints(), ai.knowledgeReferences(),
                        ai.sourceReferences(), ai.gapIds(), mine.origin(), true));
            } else {
                chapters.add(Math.min(i, chapters.size()), mine);
            }
        }
        List<Chapter> ordered = new ArrayList<>();
        for (int i = 0; i < chapters.size(); i++) ordered.add(chapters.get(i).withOrder(i + 1));
        return new BlueprintData(fresh.schemaVersion(), concept, title, subtitle, audience, goal, promise,
                fresh.structureRationale(), ordered, fresh.knowledgeGaps(), List.copyOf(fields));
    }

    // ---- knowledge index ------------------------------------------------------------

    /** Lookup of BookKnowledge items by type and (fuzzy) name, plus source resolution. */
    static final class KnowledgeIndex {
        record Item(String type, String name, List<String> sources) {
        }

        private final Map<String, List<Item>> byType = new LinkedHashMap<>();
        private final KnowledgeAssembler.SourceResolver resolver;

        KnowledgeIndex(BookKnowledgeData k) {
            add("topic", k.topics(), BookKnowledgeData.Topic::name, BookKnowledgeData.Topic::sources);
            add("process", k.processes(), BookKnowledgeData.ProcessInfo::name, BookKnowledgeData.ProcessInfo::sources);
            add("example", k.examples(), BookKnowledgeData.Example::title, BookKnowledgeData.Example::sources);
            add("importantDetail", k.importantDetails(), BookKnowledgeData.ImportantDetail::detail, BookKnowledgeData.ImportantDetail::sources);
            add("term", k.terminology(), BookKnowledgeData.Term::term, BookKnowledgeData.Term::sources);
            add("userInsight", k.userInsights(), BookKnowledgeData.UserInsight::insight, BookKnowledgeData.UserInsight::sources);
            add("technicalDetail", k.technicalDetails(), BookKnowledgeData.TechnicalDetail::detail, BookKnowledgeData.TechnicalDetail::sources);
            add("fact", k.facts(), BookKnowledgeData.Fact::fact, BookKnowledgeData.Fact::sources);
            add("intendedSequence", k.intendedSequence(), BookKnowledgeData.SequenceStep::step, BookKnowledgeData.SequenceStep::sources);
            add("knowledgeGap", k.knowledgeGaps(), BookKnowledgeData.KnowledgeGap::question, BookKnowledgeData.KnowledgeGap::sources);
            List<String> refs = new ArrayList<>();
            for (BookKnowledgeData.SourceRef s : k.sources()) refs.add(s.ref());
            for (List<Item> items : byType.values()) for (Item it : items) refs.addAll(it.sources());
            this.resolver = new KnowledgeAssembler.SourceResolver(new LinkedHashSet<>(refs));
        }

        private <T> void add(String type, List<T> items, Function<T, String> name, Function<T, List<String>> sources) {
            List<Item> list = new ArrayList<>();
            for (T t : items) {
                String n = name.apply(t);
                if (n != null && !n.isBlank()) list.add(new Item(type, n, sources.apply(t)));
            }
            byType.put(type, list);
        }

        /** The knowledge item a reference points at, or null. Type is a hint; exact > contained match. */
        Item find(String type, String name) {
            if (name == null || name.isBlank()) return null;
            String key = norm(name);
            List<Item> typed = type == null ? null : byType.get(canonicalType(type));
            Item hit = typed == null ? null : match(typed, key);
            if (hit != null) return hit;
            List<Item> all = byType.values().stream().flatMap(List::stream).toList();
            return match(all, key);
        }

        private static Item match(List<Item> items, String key) {
            for (Item it : items) if (norm(it.name()).equals(key)) return it;
            List<Item> contained = items.stream().filter(it -> {
                String n = norm(it.name());
                return key.length() >= 4 && n.length() >= 4 && (n.contains(key) || key.contains(n));
            }).toList();
            return contained.size() == 1 ? contained.get(0) : null;
        }

        private static String canonicalType(String type) {
            String t = type.strip().toLowerCase(Locale.ROOT).replace("_", "").replace(" ", "");
            return switch (t) {
                case "topic", "topics" -> "topic";
                case "process", "processes" -> "process";
                case "example", "examples" -> "example";
                case "importantdetail", "importantdetails", "detail" -> "importantDetail";
                case "term", "terminology" -> "term";
                case "userinsight", "userinsights", "insight", "experience" -> "userInsight";
                case "technicaldetail", "technicaldetails" -> "technicalDetail";
                case "fact", "facts" -> "fact";
                case "intendedsequence", "sequence", "step" -> "intendedSequence";
                case "knowledgegap", "knowledgegaps", "gap" -> "knowledgeGap";
                default -> type;
            };
        }

        List<String> resolveSources(List<String> cited) {
            Set<String> out = new LinkedHashSet<>();
            for (String c : cited) {
                String r = resolver.resolve(c);
                if (r != null) out.add(r);
            }
            return List.copyOf(out);
        }
    }

    // ---- helpers ------------------------------------------------------------------

    static String norm(String s) {
        return s == null ? "" : NON_ALNUM.matcher(s.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
    }

    private static String severity(String s) {
        String l = s == null ? "" : s.strip().toLowerCase(Locale.ROOT);
        return SEVERITIES.contains(l) ? l : "important";
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText().strip();
        return s.isEmpty() || s.equals("null") ? null : s;
    }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr == null || arr.isMissingNode() || arr.isNull()) return out;
        if (arr.isTextual()) {
            if (!arr.asText().isBlank()) out.add(arr.asText().strip());
            return out;
        }
        for (JsonNode v : arr) {
            if (v.isTextual() && !v.asText().isBlank()) out.add(v.asText().strip());
        }
        return out;
    }
}
