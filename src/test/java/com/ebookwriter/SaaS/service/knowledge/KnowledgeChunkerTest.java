package com.ebookwriter.SaaS.service.knowledge;

import com.ebookwriter.SaaS.config.properties.KnowledgeProperties;
import com.ebookwriter.SaaS.dto.knowledge.BookKnowledgeData.SourceRef;
import com.ebookwriter.SaaS.dto.knowledge.NormalizedDocument;
import com.ebookwriter.SaaS.dto.knowledge.NormalizedDocument.Kind;
import com.ebookwriter.SaaS.entity.KnowledgeSourceType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Batching, request-size control, priorities and duplicate handling. */
class KnowledgeChunkerTest {

    private final KnowledgeProperties limits = new KnowledgeProperties();
    private final DocumentTextExtractor docs = new DocumentTextExtractor(limits);
    private final KnowledgeChunker chunker = new KnowledgeChunker(limits);

    private AnalysisDocument zipDoc(String path, Kind kind, String content) {
        return new AnalysisDocument("my-shop.zip", KnowledgeSourceType.ZIP, docs.document(path, kind, content));
    }

    private AnalysisDocument notes(String content) {
        return new AnalysisDocument("user-notes", KnowledgeSourceType.NOTES,
                docs.document("user-notes", Kind.NOTES, content));
    }

    @Test
    void notesComeFirstAndEveryDocumentIsLabelledWithItsSource() {
        KnowledgeChunker.Plan plan = chunker.plan(List.of(
                zipDoc("src/Main.java", Kind.CODE, "class Main {}"),
                zipDoc("README.md", Kind.DOCUMENT, "# Shop"),
                notes("I had problems with JWT")));

        assertEquals(1, plan.chunks().size());
        String text = plan.chunks().get(0).text();
        assertTrue(text.indexOf("SOURCE: user-notes") < text.indexOf("SOURCE: README.md"));
        assertTrue(text.indexOf("SOURCE: README.md") < text.indexOf("SOURCE: src/Main.java"));
        assertTrue(text.contains("=== SOURCE: src/Main.java | type: code | language: java | from: my-shop.zip ==="));
        assertEquals("I had problems with JWT", plan.notesExcerpt());
        assertTrue(plan.sourcesAfter(Set.of(1)).stream().allMatch(SourceRef::analyzed));
    }

    @Test
    void largeMaterialIsSplitIntoBoundedBatches() {
        limits.setMaxCharsPerChunk(5_000);
        List<AnalysisDocument> input = new ArrayList<>();
        for (int i = 0; i < 12; i++) input.add(zipDoc("src/F" + i + ".java", Kind.CODE, ("// line " + i + "\n").repeat(150)));
        input.add(zipDoc("docs/huge.md", Kind.DOCUMENT, "paragraph text\n".repeat(1_200)));

        KnowledgeChunker.Plan plan = chunker.plan(input);

        assertTrue(plan.chunks().size() > 3);
        assertTrue(plan.chunks().stream().allMatch(c -> c.chars() <= 5_000), "no request exceeds the batch size");
        // The oversized document was split into parts, all analysed.
        assertTrue(plan.chunks().stream().anyMatch(c -> c.text().contains("SOURCE: docs/huge.md | type: document | from: my-shop.zip | part 1/")));
        assertEquals(0, plan.notAnalyzed());
        assertEquals(plan.analyzedChars(), plan.chunks().stream().mapToLong(KnowledgeChunker.Chunk::chars).sum());
    }

    @Test
    void runBudgetCapsCostAndRecordsWhatWasLeftOut() {
        limits.setMaxCharsPerChunk(5_000);
        limits.setMaxAnalysisChars(12_000);
        List<AnalysisDocument> input = new ArrayList<>();
        input.add(notes("notes first"));
        for (int i = 0; i < 20; i++) input.add(zipDoc("src/F" + i + ".java", Kind.CODE, "x".repeat(2_000) + i));

        KnowledgeChunker.Plan plan = chunker.plan(input);

        assertTrue(plan.analyzedChars() <= 12_000);
        assertTrue(plan.notAnalyzed() > 0);
        Map<String, SourceRef> refs = byRef(plan.sourcesAfter(allChunks(plan)));
        assertTrue(refs.get("user-notes").analyzed(), "the author's notes always make the cut");
        assertEquals(KnowledgeChunker.BUDGET_REASON, refs.get("src/F19.java").notAnalyzedReason());
        assertFalse(plan.warnings().isEmpty());
    }

    @Test
    void maxChunksCapsTheNumberOfRequests() {
        limits.setMaxCharsPerChunk(2_500);
        limits.setMaxChunks(3);
        List<AnalysisDocument> input = new ArrayList<>();
        for (int i = 0; i < 20; i++) input.add(zipDoc("f" + i + ".txt", Kind.DOCUMENT, "y".repeat(1_500) + i));
        KnowledgeChunker.Plan plan = chunker.plan(input);
        assertEquals(3, plan.chunks().size());
        assertEquals(17, plan.notAnalyzed());
    }

    @Test
    void duplicatesAreAnalysedOnce() {
        KnowledgeChunker.Plan plan = chunker.plan(List.of(
                zipDoc("README.md", Kind.DOCUMENT, "# Shop\nA shop."),
                zipDoc("docs/README-copy.md", Kind.DOCUMENT, "# Shop\n\nA   shop.")));
        assertEquals(1, plan.duplicates());
        SourceRef copy = plan.sources().stream().filter(s -> s.ref().equals("docs/README-copy.md")).findFirst().orElseThrow();
        assertFalse(copy.analyzed());
        assertEquals("README.md", copy.duplicateOf());
        assertFalse(plan.chunks().get(0).text().contains("README-copy"));
    }

    // ---- analysed only after a successful batch -----------------------------------

    @Test
    void plannedDocumentsAreNotAnalysedUntilTheirBatchSucceeds() {
        KnowledgeChunker.Plan plan = chunker.plan(List.of(
                notes("notes"), zipDoc("README.md", Kind.DOCUMENT, "# Shop")));
        assertTrue(plan.sources().stream().noneMatch(SourceRef::analyzed), "planning is not analysing");
    }

    @Test
    void successfulBatchMarksItsDocumentsAnalysed() {
        KnowledgeChunker.Plan plan = chunker.plan(List.of(
                notes("notes"), zipDoc("README.md", Kind.DOCUMENT, "# Shop")));
        List<SourceRef> sources = plan.sourcesAfter(Set.of(1));
        assertTrue(sources.stream().allMatch(SourceRef::analyzed));
        assertTrue(sources.stream().allMatch(s -> s.notAnalyzedReason() == null));
    }

    @Test
    void failedBatchLeavesItsDocumentsNotAnalysed() {
        KnowledgeChunker.Plan plan = chunker.plan(List.of(
                notes("notes"), zipDoc("README.md", Kind.DOCUMENT, "# Shop")));
        List<SourceRef> sources = plan.sourcesAfter(Set.of());
        assertTrue(sources.stream().noneMatch(SourceRef::analyzed));
        assertTrue(sources.stream().allMatch(s -> KnowledgeChunker.ANALYSIS_FAILED_REASON.equals(s.notAnalyzedReason())));
    }

    @Test
    void mixedBatchOutcomesAreTrackedPerDocument() {
        limits.setMaxCharsPerChunk(2_500);
        List<AnalysisDocument> input = new ArrayList<>();
        for (int i = 0; i < 3; i++) input.add(zipDoc("f" + i + ".txt", Kind.DOCUMENT, "y".repeat(1_500) + i));
        KnowledgeChunker.Plan plan = chunker.plan(input);
        assertEquals(3, plan.chunks().size(), "one document per batch");

        // Batch 1 → success, batch 2 → failure, batch 3 → success.
        Map<String, SourceRef> refs = byRef(plan.sourcesAfter(Set.of(1, 3)));
        assertTrue(refs.get(plan.chunks().get(0).refs().get(0)).analyzed());
        SourceRef failed = refs.get(plan.chunks().get(1).refs().get(0));
        assertFalse(failed.analyzed());
        assertEquals(KnowledgeChunker.ANALYSIS_FAILED_REASON, failed.notAnalyzedReason());
        assertTrue(refs.get(plan.chunks().get(2).refs().get(0)).analyzed());
        assertEquals(2, refs.values().stream().filter(SourceRef::analyzed).count());
    }

    @Test
    void documentSplitAcrossBatchesNeedsEveryBatchToSucceed() {
        limits.setMaxCharsPerChunk(5_000);
        KnowledgeChunker.Plan plan = chunker.plan(List.of(
                zipDoc("docs/huge.md", Kind.DOCUMENT, "paragraph text\n".repeat(1_200))));
        assertTrue(plan.chunks().size() > 1);

        Set<Integer> allButLast = new HashSet<>(allChunks(plan));
        allButLast.remove(plan.chunks().size());
        assertFalse(plan.sourcesAfter(allButLast).get(0).analyzed());
        assertTrue(plan.sourcesAfter(allChunks(plan)).get(0).analyzed());
    }

    @Test
    void documentsLeftOutOnPurposeKeepTheirReason() {
        limits.setMaxCharsPerChunk(2_500);
        limits.setMaxChunks(1);
        KnowledgeChunker.Plan plan = chunker.plan(List.of(
                zipDoc("README.md", Kind.DOCUMENT, "# Shop\nA shop."),
                zipDoc("docs/README-copy.md", Kind.DOCUMENT, "# Shop\n\nA   shop."),
                zipDoc("a.txt", Kind.DOCUMENT, "y".repeat(1_500)),
                zipDoc("b.txt", Kind.DOCUMENT, "z".repeat(1_500))));
        Map<String, SourceRef> refs = byRef(plan.sourcesAfter(Set.of()));
        assertEquals("README.md", refs.get("docs/README-copy.md").duplicateOf());
        assertEquals(KnowledgeChunker.BUDGET_REASON, refs.get("b.txt").notAnalyzedReason());
        assertEquals(KnowledgeChunker.ANALYSIS_FAILED_REASON, refs.get("README.md").notAnalyzedReason());
    }

    private static Map<String, SourceRef> byRef(List<SourceRef> sources) {
        return sources.stream().collect(Collectors.toMap(SourceRef::ref, Function.identity()));
    }

    private static Set<Integer> allChunks(KnowledgeChunker.Plan plan) {
        return plan.chunks().stream().map(KnowledgeChunker.Chunk::index).collect(Collectors.toSet());
    }

    @Test
    void samePathInTwoArchivesGetsDistinctRefs() {
        NormalizedDocument a = docs.document("README.md", Kind.DOCUMENT, "first");
        NormalizedDocument b = docs.document("README.md", Kind.DOCUMENT, "second");
        Map<String, AnalysisDocument> refs = KnowledgeChunker.assignRefs(List.of(
                new AnalysisDocument("a.zip", KnowledgeSourceType.ZIP, a),
                new AnalysisDocument("b.zip", KnowledgeSourceType.ZIP, b)));
        assertEquals(List.of("a.zip!/README.md", "b.zip!/README.md"), List.copyOf(refs.keySet()));
    }

    @Test
    void splitKeepsLineBoundaries() {
        List<String> parts = KnowledgeChunker.split("aaaa\nbbbb\ncccc\n", 10);
        assertEquals("aaaa\nbbbb\ncccc\n", String.join("", parts));
        assertTrue(parts.stream().allMatch(p -> p.length() <= 10));
        assertTrue(parts.get(0).endsWith("\n"));
    }
}
