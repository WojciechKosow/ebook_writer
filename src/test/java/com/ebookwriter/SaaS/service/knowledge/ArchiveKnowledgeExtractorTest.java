package com.ebookwriter.SaaS.service.knowledge;

import com.ebookwriter.SaaS.config.properties.KnowledgeProperties;
import com.ebookwriter.SaaS.dto.knowledge.NormalizedDocument;
import com.ebookwriter.SaaS.dto.knowledge.NormalizedDocument.Kind;
import com.ebookwriter.SaaS.dto.knowledge.SkippedFile;
import com.ebookwriter.SaaS.service.knowledge.archive.RarArchiveReader;
import com.ebookwriter.SaaS.service.knowledge.archive.ZipArchiveReader;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Archive (ZIP) ingestion: what is read, what is skipped (and why), and the abuse guards. RAR: {@link RarKnowledgeExtractionTest}. */
class ArchiveKnowledgeExtractorTest {

    private final KnowledgeProperties limits = new KnowledgeProperties();
    private final ArchiveKnowledgeExtractor extractor = new ArchiveKnowledgeExtractor(limits,
            new DocumentTextExtractor(limits), List.of(new ZipArchiveReader(), new RarArchiveReader(limits)));

    @Test
    void readsTheProjectWithPathsAndSkipsNoise() throws Exception {
        ExtractionResult result = extractor.extract("my-shop.zip", MyShopFixture.zip());

        assertNull(result.error());
        Map<String, NormalizedDocument> byPath = result.documents().stream()
                .collect(Collectors.toMap(NormalizedDocument::path, Function.identity()));

        // The wrapping "my-shop/" folder is stripped: paths read like the project's own.
        NormalizedDocument security = byPath.get("src/main/java/com/shop/security/SecurityConfig.java");
        assertNotNull(security, "source code is read with its path; got " + byPath.keySet());
        assertEquals(Kind.CODE, security.kind());
        assertEquals("java", security.language());
        assertEquals("java", security.extension());
        assertTrue(security.content().contains("SessionCreationPolicy.STATELESS"));

        assertEquals(Kind.BUILD, byPath.get("pom.xml").kind());
        assertEquals(Kind.DOCUMENT, byPath.get("README.md").kind());
        assertEquals(Kind.CONFIG, byPath.get("src/main/resources/application.properties").kind());
        assertEquals(Kind.TEST, byPath.get("src/test/java/com/shop/OrderServiceTest.java").kind());
        assertTrue(byPath.containsKey("notes/todo.md"));

        // Never read: secrets, build output, dependencies, VCS/IDE state, lockfiles, binaries.
        assertFalse(byPath.containsKey(".env"));
        assertTrue(byPath.keySet().stream().noneMatch(p -> p.startsWith("target/") || p.startsWith("node_modules/")
                || p.startsWith(".git/") || p.startsWith(".idea/") || p.endsWith("package-lock.json")
                || p.endsWith(".png")));
        String allContent = result.documents().stream().map(NormalizedDocument::content).collect(Collectors.joining());
        assertFalse(allContent.contains("super-secret-value"), "secrets must never be extracted");
        assertFalse(allContent.contains("module.exports = leftPad"));

        Map<String, String> skipped = result.skipped().stream()
                .collect(Collectors.toMap(SkippedFile::path, SkippedFile::reason, (a, b) -> a));
        assertTrue(skipped.get(".env").contains("secrets"));
        assertTrue(skipped.get("screenshots/home.png").contains("binary"));
        assertTrue(skipped.containsKey("node_modules/"));

        // One structure document lists the tree, including what was skipped.
        NormalizedDocument structure = result.documents().get(0);
        assertEquals(Kind.STRUCTURE, structure.kind());
        assertEquals("my-shop.zip (structure)", structure.path());
        assertTrue(structure.content().contains("Root folder: my-shop/"));
        assertTrue(structure.content().contains("src/main/java/com/shop/order/OrderService.java"));
        assertTrue(structure.content().contains("screenshots/home.png  [binary/media file]"));
        assertTrue(structure.content().contains("node_modules/  ["));
    }

    @Test
    void extractsPdfAndDocxInsideTheArchive() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("course/lesson1.docx", MyShopFixture.docx("Lesson 1", "Variables hold values."));
        entries.put("course/slides.pdf", MyShopFixture.pdf("Loops repeat work."));
        entries.put("course/broken.pdf", "%PDF-1.4 not really".getBytes(StandardCharsets.UTF_8));
        ExtractionResult result = extractor.extract("course.zip", MyShopFixture.zipOf(entries));

        Map<String, NormalizedDocument> byPath = result.documents().stream()
                .collect(Collectors.toMap(NormalizedDocument::path, Function.identity()));
        assertTrue(byPath.get("lesson1.docx").content().contains("# Lesson 1"));
        assertTrue(byPath.get("lesson1.docx").content().contains("Variables hold values."));
        assertTrue(byPath.get("slides.pdf").content().contains("Loops repeat work."));
        // One damaged file is skipped with a reason; the archive still succeeds.
        assertNull(result.error());
        assertTrue(result.skipped().stream().anyMatch(s -> s.path().equals("broken.pdf") && s.reason().contains("PDF")));
    }

    @Test
    void corruptArchiveFailsGracefully() {
        ExtractionResult result = extractor.extract("bad.zip", "this is not a zip".getBytes(StandardCharsets.UTF_8));
        assertNotNull(result.error());
        assertTrue(result.documents().isEmpty());
    }

    @Test
    void emptyArchiveIsReported() throws Exception {
        ExtractionResult result = extractor.extract("empty.zip", MyShopFixture.zipOf(Map.of()));
        assertNotNull(result.error());
    }

    @Test
    void entryCountLimitStopsHugeArchives() throws Exception {
        limits.setArchiveMaxEntries(10);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 0; i < 50; i++) entries.put("notes/n" + i + ".txt", ("note " + i).getBytes(StandardCharsets.UTF_8));
        ExtractionResult result = extractor.extract("many.zip", MyShopFixture.zipOf(entries));

        long notes = result.documents().stream().filter(d -> d.kind() != Kind.STRUCTURE).count();
        assertEquals(10, notes);
        assertTrue(result.skipped().stream().anyMatch(s -> s.reason().contains("more than 10 entries")));
    }

    @Test
    void zipBombEntriesAreNotInflated() throws Exception {
        // 4 MB of zeros compresses ~1000:1 — over the ratio guard.
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("bomb.txt", new byte[4 * 1024 * 1024]);
        entries.put("ok.md", "# Fine".getBytes(StandardCharsets.UTF_8));
        ExtractionResult result = extractor.extract("bomb.zip", MyShopFixture.zipOf(entries));

        assertTrue(result.skipped().stream().anyMatch(s -> s.path().equals("bomb.txt")
                && s.reason().contains("compression ratio")));
        assertTrue(result.documents().stream().anyMatch(d -> d.path().equals("ok.md")));
    }

    @Test
    void oversizedFilesAndTotalBudgetAreEnforced() throws Exception {
        limits.setMaxFileBytes(1_000);
        limits.setArchiveMaxTotalUncompressedBytes(1_500);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("big.txt", "x".repeat(5_000).getBytes(StandardCharsets.UTF_8));
        entries.put("a.txt", "a".repeat(900).getBytes(StandardCharsets.UTF_8));
        entries.put("b.txt", "b".repeat(900).getBytes(StandardCharsets.UTF_8));
        entries.put("c.txt", "c".repeat(900).getBytes(StandardCharsets.UTF_8));
        ExtractionResult result = extractor.extract("sizes.zip", MyShopFixture.zipOf(entries));

        List<String> read = result.documents().stream().filter(d -> d.kind() != Kind.STRUCTURE).map(NormalizedDocument::path).toList();
        assertEquals(List.of("a.txt"), read);
        assertTrue(result.skipped().stream().anyMatch(s -> s.path().equals("big.txt") && s.reason().contains("too large")));
        assertTrue(result.skipped().stream().anyMatch(s -> s.reason().contains("archive size limit")));
    }

    @Test
    void longDocumentsAreTruncatedAndFlagged() throws Exception {
        limits.setMaxDocumentChars(1_000);
        ExtractionResult result = extractor.extract("long.zip",
                MyShopFixture.zipOf(Map.of("long.md", ("line\n").repeat(1_000).getBytes(StandardCharsets.UTF_8))));
        NormalizedDocument doc = result.documents().stream().filter(d -> d.path().equals("long.md")).findFirst().orElseThrow();
        assertTrue(doc.truncated());
        assertTrue(doc.chars() <= 1_000 + TextNormalizer.TRUNCATION_MARKER.length());
    }

    @Test
    void unsafePathsAreNeutralised() {
        assertNull(ArchiveKnowledgeExtractor.safePath("../../etc/passwd"));
        assertEquals("a/b.txt", ArchiveKnowledgeExtractor.safePath("/a/./b.txt"));
        assertEquals("dir/file.md", ArchiveKnowledgeExtractor.safePath("C:\\dir\\file.md"));
        assertEquals("my-shop", ArchiveKnowledgeExtractor.commonRoot(List.of("my-shop/a", "my-shop/b/c")));
        assertNull(ArchiveKnowledgeExtractor.commonRoot(List.of("a/x", "b/y")));
        assertNull(ArchiveKnowledgeExtractor.commonRoot(List.of("README.md")));
    }

    @Test
    void classifierIsGenericAcrossKindsOfArchives() {
        assertNull(FileClassifier.skipReason("chapters/01-intro.md"));
        assertNull(FileClassifier.skipReason("recipes/soup.txt"));
        assertNull(FileClassifier.skipReason("app/main.py"));
        assertNull(FileClassifier.skipReason(".env.example"));
        assertNotNull(FileClassifier.skipReason("frontend/node_modules/react/index.js"));
        assertNotNull(FileClassifier.skipReason("keys/server.pem"));
        assertNotNull(FileClassifier.skipReason("video/lesson.mp4"));
        assertEquals(Kind.CODE, FileClassifier.kindOf("app/main.py"));
        assertEquals("python", FileClassifier.languageOf("app/main.py"));
        assertEquals(Kind.BUILD, FileClassifier.kindOf("package.json"));
        assertEquals(Kind.DOCUMENT, FileClassifier.kindOf("chapters/01-intro.md"));
    }
}
