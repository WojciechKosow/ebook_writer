package com.ebookwriter.SaaS.service.store;

import com.ebookwriter.SaaS.entity.ProductFileFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** The product file checks: extension AND content, no executables, no unsafe archives. */
class ProductFileValidatorTest {

    static final long MAX = 10L * 1024 * 1024;

    @TempDir
    Path dir;

    static final byte[] PDF = "%PDF-1.7\n%âãÏÓ\n1 0 obj\n<<>>\nendobj\ntrailer\n%%EOF".getBytes(StandardCharsets.ISO_8859_1);

    // ---- Accepted ------------------------------------------------------------------

    @Test
    void acceptsRealPdfEpubAndZip() throws IOException {
        assertEquals(ProductFileFormat.PDF, validate("Guide.pdf", PDF));
        assertEquals(ProductFileFormat.EPUB, validate("Book.EPUB", epub()));
        assertEquals(ProductFileFormat.ZIP, validate("templates.zip", zip(Map.of(
                "templates/budget.xlsx", "x", "templates/readme.txt", "hello", "scripts/setup.sh", "echo"))));
    }

    @Test
    void acceptsZip64CentralDirectory() throws IOException {
        // > 65535 entries forces ZIP64 end-of-central-directory records.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.setLevel(0);
            for (int i = 0; i < 65_600; i++) {
                zip.putNextEntry(new ZipEntry("d/" + i));
                zip.closeEntry();
            }
        }
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> validate("many.zip", out.toByteArray()));
        // Parsed fine through ZIP64 — refused only for the entry limit.
        assertTrue(e.getReason().contains("too many files"), e.getReason());
    }

    // ---- Refused -------------------------------------------------------------------

    @Test
    void refusesUnsupportedExtensions() {
        for (String name : new String[]{"setup.exe", "notes.docx", "book.mobi", "archive.rar", "noextension", "trailing."}) {
            assertBad(name, PDF, "Upload a PDF, EPUB or ZIP file.");
        }
    }

    @Test
    void refusesExecutablesWhateverTheirName() {
        assertBad("invoice.pdf", bytes("MZ\u0090\u0000 this program cannot be run in DOS mode"), "Executable");
        assertBad("tool.zip", new byte[]{0x7f, 'E', 'L', 'F', 2, 1, 1, 0}, "Executable");
        assertBad("app.epub", new byte[]{(byte) 0xcf, (byte) 0xfa, (byte) 0xed, (byte) 0xfe, 7, 0, 0, 1}, "Executable");
        assertBad("run.pdf", bytes("#!/bin/sh\nrm -rf /\n"), "Executable");
    }

    @Test
    void refusesContentThatDoesNotMatchTheExtension() throws IOException {
        assertBad("fake.pdf", bytes("just some text"), "isn't a valid PDF");
        assertBad("fake.zip", PDF, "isn't a valid ZIP");
        assertBad("fake.epub", zip(Map.of("chapter.html", "<p>hi</p>")), "isn't a valid EPUB");
        assertBad("fake.epub", PDF, "isn't a valid EPUB");
    }

    @Test
    void refusesArchivesWithProgramsInside() throws IOException {
        assertBad("bundle.zip", zip(Map.of("readme.txt", "hi", "bin/Setup.EXE", "MZ")), "programs or installers");
        assertBad("bundle.zip", zip(Map.of("My.app/Contents/Info.plist", "<plist/>")), "programs or installers");
        assertBad("bundle.zip", zip(Map.of("lib/tool.jar", "PK")), "programs or installers");
    }

    @Test
    void refusesZipSlipPaths() throws IOException {
        assertBad("evil.zip", zip(Map.of("../../etc/cron.d/x", "x")), "unsafe path");
        assertBad("evil.zip", zip(Map.of("ok/../../x", "x")), "unsafe path");
        assertBad("evil.zip", zip(Map.of("/abs/x", "x")), "unsafe path");
        assertBad("evil.zip", zip(Map.of("C:\\Windows\\x.txt", "x")), "unsafe path");
    }

    @Test
    void refusesDamagedOrEmptyArchives() throws IOException {
        byte[] good = zip(Map.of("a.txt", "a"));
        byte[] truncated = java.util.Arrays.copyOf(good, good.length - 10);
        assertBad("cut.zip", truncated, "damaged");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new ZipOutputStream(out).close(); // empty archive: no local header at all
        assertBad("empty.zip", out.toByteArray(), "isn't a valid ZIP");
    }

    @Test
    void refusesEmptyAndOversizedFiles() throws IOException {
        assertBad("empty.pdf", new byte[0], "empty");
        Path big = dir.resolve("big.pdf");
        Files.write(big, PDF);
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> ProductFileValidator.validate("big.pdf", big, MAX + 1, MAX));
        assertEquals(HttpStatus.CONTENT_TOO_LARGE, e.getStatusCode());
    }

    // ---- Filenames -----------------------------------------------------------------

    @Test
    void sanitizesFilenames() {
        assertEquals("passwd.pdf", ProductFileValidator.sanitizeFilename("../../etc/passwd.pdf", ProductFileFormat.PDF));
        assertEquals("evil.zip", ProductFileValidator.sanitizeFilename("C:\\Users\\x\\evil.zip", ProductFileFormat.ZIP));
        assertEquals("ab.pdf", ProductFileValidator.sanitizeFilename("a\r\n\"b.pdf", ProductFileFormat.PDF));
        assertEquals("file.epub", ProductFileValidator.sanitizeFilename(".epub", ProductFileFormat.EPUB));
        assertEquals("notes.pdf", ProductFileValidator.sanitizeFilename("notes", ProductFileFormat.PDF));
        assertEquals("Żółw — przewodnik.pdf",
                ProductFileValidator.sanitizeFilename("Żółw — przewodnik.pdf", ProductFileFormat.PDF));
        String longName = "x".repeat(400) + ".zip";
        String cleaned = ProductFileValidator.sanitizeFilename(longName, ProductFileFormat.ZIP);
        assertEquals(200, cleaned.length());
        assertTrue(cleaned.endsWith(".zip"));
    }

    // ---- Helpers -------------------------------------------------------------------

    private ProductFileFormat validate(String name, byte[] content) throws IOException {
        Path file = Files.createTempFile(dir, "upload", ".bin");
        Files.write(file, content);
        return ProductFileValidator.validate(name, file, content.length, MAX);
    }

    private void assertBad(String name, byte[] content, String messagePart) {
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> validate(name, content), name);
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertTrue(e.getReason() != null && e.getReason().contains(messagePart), name + ": " + e.getReason());
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    static byte[] zip(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /** A minimal EPUB: the uncompressed mimetype entry first, then the container. */
    static byte[] epub() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            byte[] mime = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);
            ZipEntry m = new ZipEntry("mimetype");
            m.setMethod(ZipEntry.STORED);
            m.setSize(mime.length);
            CRC32 crc = new CRC32();
            crc.update(mime);
            m.setCrc(crc.getValue());
            zip.putNextEntry(m);
            zip.write(mime);
            zip.closeEntry();
            Map<String, String> rest = new LinkedHashMap<>();
            rest.put("META-INF/container.xml", "<container/>");
            rest.put("OEBPS/content.opf", "<package/>");
            rest.put("OEBPS/ch1.xhtml", "<html/>");
            for (Map.Entry<String, String> e : rest.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
