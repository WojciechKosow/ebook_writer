package com.ebookwriter.SaaS.service.knowledge;

import com.ebookwriter.SaaS.config.properties.KnowledgeProperties;
import com.ebookwriter.SaaS.dto.knowledge.BookKnowledgeData.SourceRef;
import com.ebookwriter.SaaS.dto.knowledge.NormalizedDocument;
import com.ebookwriter.SaaS.dto.knowledge.NormalizedDocument.Kind;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plans what a processing run sends to OpenAI. Instead of one giant request it:
 * <ol>
 *   <li>gives every document a stable, citable <b>ref</b> (its path; prefixed with
 *       the archive name only when two uploads contain the same path);</li>
 *   <li>drops <b>duplicates</b> — identical content seen earlier (same file in two
 *       uploads, a copied README) is analysed once and marked {@code duplicateOf};</li>
 *   <li>orders by usefulness: the author's notes first, then the archive
 *       structure, prose documents, build files, config, code, tests, data;</li>
 *   <li>packs documents into <b>batches</b> of at most {@code maxCharsPerChunk},
 *       splitting an oversized document into parts at line boundaries;</li>
 *   <li>stops at the run's <b>budget</b> ({@code maxAnalysisChars},
 *       {@code maxChunks}); everything beyond is recorded as not analysed — never
 *       silently sent, never silently lost.</li>
 * </ol>
 * Pure (no I/O) so the cost-control logic is unit-testable.
 */
@Component
@RequiredArgsConstructor
public class KnowledgeChunker {

    public static final String NOTES_REF = "user-notes";
    static final String BUDGET_REASON = "analysis size limit reached";
    /** A planned document whose batch (or one of its batches) could not be analysed. */
    public static final String ANALYSIS_FAILED_REASON = "analysis failed (its batch could not be analysed)";

    private final KnowledgeProperties limits;

    /** One OpenAI request worth of material. */
    public record Chunk(int index, String text, int chars, List<String> refs) {
    }

    /**
     * The run's plan.
     *
     * @param chunks        batches to send, in order
     * @param sources       every document considered; documents placed in batches are
     *                      not yet {@code analyzed} — see {@link #sourcesAfter}
     * @param refs          ref → document (for resolving citations)
     * @param duplicates    documents skipped as duplicates
     * @param notAnalyzed   documents (fully) left out by the budget
     * @param analyzedChars material characters included in the batches
     * @param notesExcerpt  the start of the author's notes (context for later batches), or null
     * @param warnings      human-readable notes about what was left out
     */
    public record Plan(List<Chunk> chunks, List<SourceRef> sources, Map<String, AnalysisDocument> refs,
                       int duplicates, int notAnalyzed, long analyzedChars, String notesExcerpt,
                       List<String> warnings) {

        /**
         * The sources with the run's outcome applied: a document placed in batches
         * counts as analysed only when every batch carrying it was analysed
         * successfully; otherwise it is not analysed, with
         * {@link #ANALYSIS_FAILED_REASON}. Documents never placed in a batch
         * (duplicates, over budget) are unchanged.
         */
        public List<SourceRef> sourcesAfter(Set<Integer> analyzedChunks) {
            Map<String, List<Integer>> chunksByRef = new HashMap<>();
            for (Chunk c : chunks) {
                for (String r : c.refs()) chunksByRef.computeIfAbsent(r, k -> new ArrayList<>()).add(c.index());
            }
            List<SourceRef> out = new ArrayList<>();
            for (SourceRef s : sources) {
                List<Integer> carrying = chunksByRef.get(s.ref());
                if (carrying == null) {
                    out.add(s);
                } else if (analyzedChunks.containsAll(carrying)) {
                    out.add(withOutcome(s, true, s.notAnalyzedReason()));
                } else {
                    out.add(withOutcome(s, false, ANALYSIS_FAILED_REASON));
                }
            }
            return out;
        }
    }

    private static final Map<Kind, Integer> PRIORITY = Map.of(
            Kind.NOTES, 0, Kind.STRUCTURE, 1, Kind.DOCUMENT, 2, Kind.BUILD, 3,
            Kind.CONFIG, 4, Kind.CODE, 5, Kind.TEST, 6, Kind.DATA, 7);

    public Plan plan(List<AnalysisDocument> documents) {
        Map<String, AnalysisDocument> byRef = assignRefs(documents);

        // Priority order; stable within a kind (upload order, then path).
        List<Map.Entry<String, AnalysisDocument>> ordered = new ArrayList<>(byRef.entrySet());
        ordered.sort(Comparator.comparing((Map.Entry<String, AnalysisDocument> e) ->
                PRIORITY.getOrDefault(e.getValue().document().kind(), 9)));

        int maxChunk = Math.max(2_000, limits.getMaxCharsPerChunk());
        long budget = Math.max(maxChunk, limits.getMaxAnalysisChars());
        int maxChunks = Math.max(1, limits.getMaxChunks());

        List<Chunk> chunks = new ArrayList<>();
        List<SourceRef> sources = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, String> firstByHash = new HashMap<>();
        StringBuilder current = new StringBuilder();
        List<String> currentRefs = new ArrayList<>();
        long analyzedChars = 0;
        int duplicates = 0;
        int notAnalyzed = 0;
        boolean budgetReached = false;
        String notesExcerpt = null;

        for (Map.Entry<String, AnalysisDocument> entry : ordered) {
            String ref = entry.getKey();
            AnalysisDocument ad = entry.getValue();
            NormalizedDocument doc = ad.document();

            String original = doc.contentHash() == null ? null : firstByHash.putIfAbsent(doc.contentHash(), ref);
            if (original != null) {
                duplicates++;
                sources.add(sourceRef(ref, ad, false, original, "duplicate of " + original));
                continue;
            }
            if (doc.kind() == Kind.NOTES && notesExcerpt == null) {
                notesExcerpt = TextNormalizer.truncate(doc.content(), Math.max(500, limits.getNotesContextChars()));
            }

            List<String> parts = split(doc.content(), maxChunk - 600);
            int partsSent = 0;
            for (int p = 0; p < parts.size(); p++) {
                String block = block(ref, ad, parts.get(p), p + 1, parts.size());
                if (budgetReached || analyzedChars + block.length() > budget) {
                    budgetReached = true;
                    break;
                }
                if (current.length() > 0 && current.length() + block.length() > maxChunk) {
                    if (chunks.size() + 1 >= maxChunks) {
                        budgetReached = true;
                        break;
                    }
                    chunks.add(new Chunk(chunks.size() + 1, current.toString(), current.length(), List.copyOf(currentRefs)));
                    current.setLength(0);
                    currentRefs.clear();
                }
                current.append(block);
                if (!currentRefs.contains(ref)) currentRefs.add(ref);
                analyzedChars += block.length();
                partsSent++;
            }
            if (partsSent == 0) {
                notAnalyzed++;
                sources.add(sourceRef(ref, ad, false, null, BUDGET_REASON));
            } else if (partsSent < parts.size()) {
                sources.add(sourceRef(ref, ad, false, null,
                        "partially analysed (" + partsSent + " of " + parts.size() + " parts; " + BUDGET_REASON + ")"));
            } else {
                sources.add(sourceRef(ref, ad, false, null, null));
            }
        }
        if (current.length() > 0) {
            chunks.add(new Chunk(chunks.size() + 1, current.toString(), current.length(), List.copyOf(currentRefs)));
        }
        if (budgetReached) {
            warnings.add("The materials are larger than one analysis run allows: " + notAnalyzed
                    + " document(s) were not analysed. The most important files (notes, documentation,"
                    + " build files, main code) were analysed first.");
        }
        if (duplicates > 0) {
            warnings.add(duplicates + " duplicate document(s) were analysed only once.");
        }
        return new Plan(chunks, sources, byRef, duplicates, notAnalyzed, analyzedChars, notesExcerpt, warnings);
    }

    /** Give each document a unique, citable ref. */
    public static Map<String, AnalysisDocument> assignRefs(List<AnalysisDocument> documents) {
        Map<String, Integer> pathCounts = new HashMap<>();
        for (AnalysisDocument d : documents) pathCounts.merge(basicRef(d), 1, Integer::sum);

        Map<String, AnalysisDocument> byRef = new LinkedHashMap<>();
        Set<String> used = new HashSet<>();
        for (AnalysisDocument d : documents) {
            String ref = basicRef(d);
            if (pathCounts.get(ref) > 1 && d.sourceType().isArchive()
                    && d.document().kind() != Kind.STRUCTURE) {
                ref = d.origin() + "!/" + ref;
            }
            String unique = ref;
            for (int n = 2; used.contains(unique); n++) unique = ref + " (" + n + ")";
            used.add(unique);
            byRef.put(unique, d);
        }
        return byRef;
    }

    private static String basicRef(AnalysisDocument d) {
        return d.document().kind() == Kind.NOTES ? NOTES_REF : d.document().path();
    }

    private static String block(String ref, AnalysisDocument ad, String content, int part, int parts) {
        NormalizedDocument doc = ad.document();
        StringBuilder header = new StringBuilder("=== SOURCE: ").append(ref)
                .append(" | type: ").append(doc.kind().name().toLowerCase());
        if (doc.language() != null) header.append(" | language: ").append(doc.language());
        if (ad.sourceType().isArchive() && doc.kind() != Kind.STRUCTURE) {
            header.append(" | from: ").append(ad.origin());
        }
        if (parts > 1) header.append(" | part ").append(part).append('/').append(parts);
        if (doc.truncated() && part == parts) header.append(" | truncated");
        return header.append(" ===\n").append(content).append("\n=== END SOURCE ===\n\n").toString();
    }

    /** Split text into pieces of at most {@code max} chars, at line boundaries where possible. */
    static List<String> split(String text, int max) {
        if (text.length() <= max) return List.of(text);
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + max);
            if (end < text.length()) {
                int nl = text.lastIndexOf('\n', end);
                if (nl > start + max / 2) end = nl + 1;
            }
            parts.add(text.substring(start, end));
            start = end;
        }
        return parts;
    }

    private static SourceRef withOutcome(SourceRef s, boolean analyzed, String notAnalyzedReason) {
        return new SourceRef(s.ref(), s.origin(), s.sourceType(), s.kind(), s.language(), s.chars(), s.truncated(),
                analyzed, s.duplicateOf(), notAnalyzedReason);
    }

    private static SourceRef sourceRef(String ref, AnalysisDocument ad, boolean analyzed, String duplicateOf,
                                       String notAnalyzedReason) {
        NormalizedDocument d = ad.document();
        return new SourceRef(ref, ad.origin(), ad.sourceType().name(), d.kind().name(), d.language(), d.chars(),
                d.truncated(), analyzed, duplicateOf, notAnalyzedReason);
    }
}
