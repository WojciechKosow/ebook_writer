package com.ebookwriter.SaaS.service.knowledge;

import com.ebookwriter.SaaS.config.properties.KnowledgeProperties;
import com.ebookwriter.SaaS.dto.knowledge.NormalizedDocument;
import com.ebookwriter.SaaS.dto.knowledge.NormalizedDocument.Kind;
import com.ebookwriter.SaaS.dto.knowledge.SkippedFile;
import com.ebookwriter.SaaS.entity.KnowledgeSourceType;
import com.ebookwriter.SaaS.service.knowledge.archive.ArchiveEntry;
import com.ebookwriter.SaaS.service.knowledge.archive.OpenedArchive;
import com.ebookwriter.SaaS.service.knowledge.archive.RarArchiveReader;
import com.ebookwriter.SaaS.service.knowledge.archive.ZipArchiveReader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RAR ingestion goes through the same {@link ArchiveKnowledgeExtractor} as ZIP:
 * same documents, same skips, same guards. Fixtures are real archives (see
 * {@code src/test/resources/knowledge/rar/README.md}).
 */
class RarKnowledgeExtractionTest {

    private final KnowledgeProperties limits = new KnowledgeProperties();
    private final ArchiveKnowledgeExtractor extractor = new ArchiveKnowledgeExtractor(limits,
            new DocumentTextExtractor(limits), List.of(new ZipArchiveReader(), new RarArchiveReader(limits)));

    static byte[] fixture(String name) throws IOException {
        try (InputStream in = RarKnowledgeExtractionTest.class.getResourceAsStream("/knowledge/rar/" + name)) {
            assertNotNull(in, "missing fixture " + name);
            return in.readAllBytes();
        }
    }

    // ---- RAR is read exactly like ZIP ------------------------------------------------

    @Test
    void solidRar5ProjectReadsExactlyLikeTheZip() throws Exception {
        ExtractionResult rar = extractor.extract("my-shop.rar", fixture("my-shop.rar"));
        ExtractionResult zip = extractor.extract("my-shop.zip", MyShopFixture.zip());

        assertNull(rar.error());
        assertEquals(contents(zip), contents(rar), "same files, same text as the ZIP of the same project");
        assertEquals(skips(zip), skips(rar), "same files skipped for the same reasons");

        Map<String, NormalizedDocument> byPath = rar.documents().stream()
                .collect(Collectors.toMap(NormalizedDocument::path, Function.identity()));
        NormalizedDocument security = byPath.get("src/main/java/com/shop/security/SecurityConfig.java");
        assertEquals(Kind.CODE, security.kind());
        assertEquals("java", security.language());
        assertTrue(security.content().contains("SessionCreationPolicy.STATELESS"));
        String all = rar.documents().stream().map(NormalizedDocument::content).collect(Collectors.joining());
        assertFalse(all.contains("super-secret-value"), "secrets are never extracted from a RAR either");

        NormalizedDocument structure = rar.documents().get(0);
        assertEquals(Kind.STRUCTURE, structure.kind());
        assertEquals("my-shop.rar (structure)", structure.path());
        assertTrue(structure.content().contains("Root folder: my-shop/"));
        assertTrue(structure.content().contains("screenshots/home.png  [binary/media file]"));
    }

    @Test
    void documentsInsideARarAreExtracted() throws Exception {
        ExtractionResult result = extractor.extract("course.rar", fixture("course.rar"));
        assertNull(result.error());
        Map<String, NormalizedDocument> byPath = result.documents().stream()
                .collect(Collectors.toMap(NormalizedDocument::path, Function.identity()));
        assertTrue(byPath.get("lesson1.docx").content().contains("Variables hold values."));
        assertTrue(byPath.get("slides.pdf").content().contains("Loops repeat work."));
    }

    @Test
    void legacyRar4ArchivesAreRead() throws Exception {
        ExtractionResult solid = extractor.extract("old.rar", fixture("rar4-solid.rar"));
        assertNull(solid.error());
        assertEquals(List.of("stest1.txt", "stest2.txt"), textPaths(solid));
        assertTrue(solid.documents().stream().allMatch(d -> d.chars() > 0));

        ExtractionResult dirs = extractor.extract("dirs.rar", fixture("rar4-subdirs.rar"));
        assertNull(dirs.error());
        assertTrue(textPaths(dirs).containsAll(List.of("dir1/file1.txt", "dir2/file2.txt", "with space/long fn.txt")),
                "nested folders (wrapping 'sub/' stripped): " + textPaths(dirs));
    }

    @Test
    void formatIsRecognisedFromContentNotJustTheName() throws Exception {
        ExtractionResult rarNamedZip = extractor.extract("project.zip", fixture("course.rar"));
        assertNull(rarNamedZip.error());
        assertTrue(textPaths(rarNamedZip).contains("lesson1.docx"));

        ExtractionResult zipNamedRar = extractor.extract("project.rar", MyShopFixture.zip());
        assertNull(zipNamedRar.error());
        assertTrue(textPaths(zipNamedRar).contains("README.md"));
    }

    @Test
    void uploadTypesMapToTheArchiveFlow() {
        assertEquals(KnowledgeSourceType.RAR, KnowledgeSourceType.fromFilename("Project.RAR"));
        assertEquals(KnowledgeSourceType.ZIP, KnowledgeSourceType.fromFilename("project.zip"));
        assertTrue(KnowledgeSourceType.RAR.isArchive());
        assertTrue(KnowledgeSourceType.ZIP.isArchive());
        assertFalse(KnowledgeSourceType.PDF.isArchive());
        assertTrue(extractor.supports(KnowledgeSourceType.RAR));
        assertTrue(extractor.supports(KnowledgeSourceType.ZIP));
        assertFalse(extractor.supports(KnowledgeSourceType.DOCX));
        assertNull(KnowledgeSourceType.fromFilename("project.7z"));
    }

    // ---- bad archives ------------------------------------------------------------------

    @Test
    void notARarIsReportedClearly() throws Exception {
        assertError(extractor.extract("notes.rar", "just some text".getBytes(StandardCharsets.UTF_8)), "Not a valid RAR archive");
        byte[] signatureThenGarbage = "Rar!\u001a\u0007\u0001\u0000garbage garbage garbage".getBytes(StandardCharsets.ISO_8859_1);
        assertError(extractor.extract("bad.rar", signatureThenGarbage), "Not a valid RAR archive");
        assertError(extractor.extract("empty-file.rar", new byte[0]), "Not a valid RAR archive");
    }

    @Test
    void damagedRarsAreReportedAndWhatIsIntactIsKept() throws Exception {
        byte[] good = fixture("my-shop.rar");
        assertError(extractor.extract("cut.rar", Arrays.copyOf(good, good.length / 2)), "damaged or incomplete");

        // course.rar: bytes 80–520 are lesson1.docx's packed data, 560–1000 slides.pdf's.
        byte[] course = fixture("course.rar");
        ExtractionResult oneBad = extractor.extract("course.rar", flipped(course, 200));
        assertNull(oneBad.error());
        assertTrue(oneBad.skipped().stream().anyMatch(s -> s.path().equals("lesson1.docx")
                && s.reason().contains("checksum mismatch")), oneBad.skipped().toString());
        assertEquals(List.of("slides.pdf"), textPaths(oneBad), "the intact file is still read");

        assertError(extractor.extract("course.rar", flipped(flipped(course, 200), 700)), "RAR archive is damaged");
    }

    @Test
    void passwordProtectedRarsAreRefusedWithAClearMessage() throws Exception {
        assertError(extractor.extract("password.rar", fixture("password.rar")), "password-protected");
        assertError(extractor.extract("hidden.rar", fixture("header-encrypted.rar")), "password-protected");
        byte[] zip;
        try (InputStream in = getClass().getResourceAsStream("/knowledge/zip/password.zip")) {
            zip = in.readAllBytes(); // zip -P secret
        }
        assertError(extractor.extract("password.zip", zip), "ZIP archive is password-protected");
    }

    @Test
    void onePartOfAMultiPartRarIsRefused() throws Exception {
        assertError(extractor.extract("multipart.part1.rar", fixture("multipart.part1.rar")), "multi-part");
        assertError(extractor.extract("multipart.part2.rar", fixture("multipart.part2.rar")), "multi-part");
    }

    @Test
    void emptyArchivesAreReported() throws Exception {
        assertError(extractor.extract("only-dirs.rar", fixture("only-dirs.rar")), "RAR archive is empty");
        assertError(extractor.extract("empty.zip", MyShopFixture.zipOf(Map.of())), "ZIP archive is empty");
    }

    @Test
    void unsupportedArchiveFormatIsReported() {
        assertError(extractor.extract("project.7z", new byte[]{'7', 'z', (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C}),
                "Unsupported archive format");
    }

    // ---- safety --------------------------------------------------------------------------

    @Test
    void pathTraversalEntriesAreNeverUnpacked() throws Exception {
        // A real RAR whose only entry is named "../../../evil.md".
        ExtractionResult result = extractor.extract("traversal.rar", fixture("traversal.rar"));
        assertError(result, "invalid structure");
        assertTrue(result.documents().isEmpty());
        assertTrue(result.skipped().stream().anyMatch(s -> s.path().equals("../../../evil.md")
                && s.reason().contains("unsafe path")));

        // Mixed with good entries, only the escaping one is dropped (ZIP shown; same code path).
        ExtractionResult mixed = extractor.extract("mixed.zip", MyShopFixture.zipOf(Map.of(
                "../outside.md", "# escape".getBytes(StandardCharsets.UTF_8),
                "/etc/cron.d/x.md", "# absolute".getBytes(StandardCharsets.UTF_8),
                "docs/ok.md", "# fine".getBytes(StandardCharsets.UTF_8))));
        assertNull(mixed.error());
        assertTrue(textPaths(mixed).contains("docs/ok.md"));
        assertTrue(textPaths(mixed).contains("etc/cron.d/x.md"), "absolute paths become relative, never absolute");
        assertTrue(mixed.documents().stream().noneMatch(d -> d.path().contains("..")));
        assertTrue(mixed.skipped().stream().anyMatch(s -> s.path().equals("../outside.md")));
    }

    @Test
    void linksAreNeverFollowed() throws Exception {
        // symlink.rar holds real.md and evil.md -> /etc/passwd.
        ExtractionResult result = extractor.extract("symlink.rar", fixture("symlink.rar"));
        assertNull(result.error());
        assertEquals(List.of("real.md"), textPaths(result));
        assertTrue(result.skipped().stream().anyMatch(s -> s.path().equals("evil.md") && s.reason().contains("link")));
        String all = result.documents().stream().map(NormalizedDocument::content).collect(Collectors.joining());
        assertFalse(all.contains("root:"), "the link target is never read");
    }

    @Test
    void rarBombEntriesAreNotUnpacked() throws Exception {
        // 4 MB of zeros packed into a few hundred bytes — over the ratio guard.
        ExtractionResult result = extractor.extract("bomb.rar", fixture("bomb.rar"));
        assertTrue(result.skipped().stream().anyMatch(s -> s.path().equals("bomb.txt")
                && s.reason().contains("compression ratio")), result.skipped().toString());
        assertTrue(textPaths(result).contains("ok.md"));
    }

    @Test
    void sizeLimitsApplyToRarsToo() throws Exception {
        limits.setMaxFileBytes(500);
        ExtractionResult result = extractor.extract("my-shop.rar", fixture("my-shop.rar"));
        assertTrue(result.skipped().stream().anyMatch(s -> s.path().equals("pom.xml")
                && s.reason().startsWith("file too large")), result.skipped().toString());
        assertTrue(result.documents().stream().allMatch(d -> d.kind() == Kind.STRUCTURE || d.chars() <= 500));
    }

    @Test
    void solidRarBudgetCountsWhatMustBeDecodedToReachAFile() throws Exception {
        // Tiny total budget: in a solid archive, files later in the stream are not reached.
        limits.setArchiveMaxTotalUncompressedBytes(500);
        ExtractionResult result = extractor.extract("my-shop.rar", fixture("my-shop.rar"));
        assertNull(result.error());
        assertTrue(result.skipped().stream().anyMatch(s -> s.reason().contains("archive size limit")));
    }

    @Test
    void readerNeverBuffersMoreThanTheLimitEvenIfTheHeaderLies() throws Exception {
        try (OpenedArchive archive = new RarArchiveReader(limits).open(fixture("my-shop.rar"))) {
            ArchiveEntry pom = archive.entries(100).stream().filter(e -> e.name().endsWith("pom.xml")).findFirst().orElseThrow();
            IOException e = assertThrows(IOException.class, () -> archive.read(pom, 100));
            assertTrue(e.getMessage().contains("larger than 100 bytes"));
            assertEquals(pom.size(), archive.read(pom, 10_000).length);
        }
    }

    @Test
    void entryCountLimitAppliesToRars() throws Exception {
        limits.setArchiveMaxEntries(3);
        ExtractionResult result = extractor.extract("my-shop.rar", fixture("my-shop.rar"));
        assertTrue(result.skipped().stream().anyMatch(s -> s.reason().contains("more than 3 entries")));
        assertTrue(result.documents().stream().filter(d -> d.kind() != Kind.STRUCTURE).count() <= 3);
    }

    @Test
    void hugeDictionaryIsRefusedNotAllocated() throws Exception {
        // A decoder window bigger than the cap must not be allocated.
        limits.setRarMaxDictionaryBytes(1024);
        assertError(extractor.extract("my-shop.rar", fixture("my-shop.rar")), "compression dictionary over 1 KB");
    }

    // ---- helpers ---------------------------------------------------------------------------

    private static void assertError(ExtractionResult result, String expected) {
        assertNotNull(result.error(), "expected an error containing '" + expected + "'");
        assertTrue(result.error().contains(expected), result.error());
        assertTrue(result.documents().isEmpty());
    }

    private static Map<String, String> contents(ExtractionResult r) {
        return r.documents().stream().filter(d -> d.kind() != Kind.STRUCTURE)
                .collect(Collectors.toMap(NormalizedDocument::path, NormalizedDocument::content));
    }

    private static Map<String, String> skips(ExtractionResult r) {
        return r.skipped().stream().collect(Collectors.toMap(SkippedFile::path, SkippedFile::reason, (a, b) -> a));
    }

    private static List<String> textPaths(ExtractionResult r) {
        return r.documents().stream().filter(d -> d.kind() != Kind.STRUCTURE).map(NormalizedDocument::path).sorted().toList();
    }

    private static byte[] flipped(byte[] bytes, int at) {
        byte[] copy = bytes.clone();
        copy[at] = (byte) ~copy[at];
        return copy;
    }
}
