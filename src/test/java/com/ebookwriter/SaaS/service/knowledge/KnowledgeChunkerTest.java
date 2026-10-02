package com.ebookwriter.SaaS.service.knowledge;

import com.ebookwriter.SaaS.config.properties.KnowledgeProperties;
import com.ebookwriter.SaaS.dto.knowledge.BookKnowledgeData.SourceRef;
import com.ebookwriter.SaaS.dto.knowledge.NormalizedDocument;
import com.ebookwriter.SaaS.dto.knowledge.NormalizedDocument.Kind;
import com.ebookwriter.SaaS.entity.KnowledgeSourceType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
        assertTrue(plan.sources().stream().allMatch(SourceRef::analyzed));
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
        Map<String, SourceRef> refs = plan.sources().stream().collect(Collectors.toMap(SourceRef::ref, Function.identity()));
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
