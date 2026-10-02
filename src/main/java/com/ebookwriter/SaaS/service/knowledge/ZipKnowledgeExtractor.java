package com.ebookwriter.SaaS.service.knowledge;

import com.ebookwriter.SaaS.config.properties.KnowledgeProperties;
import com.ebookwriter.SaaS.dto.knowledge.NormalizedDocument;
import com.ebookwriter.SaaS.dto.knowledge.SkippedFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Safely unpacks an uploaded ZIP into normalised documents — <b>in memory, never
 * onto disk paths taken from the archive</b> (the archive itself is spooled to a
 * private temp file so entries can be read randomly and skipped for free).
 *
 * <p>Universal by design: the archive may be a software project, course
 * material, documentation, notes or anything else. For each entry it:
 * <ol>
 *   <li>skips folders of generated output / dependencies, binaries, lockfiles
 *       and likely secrets ({@link FileClassifier}), recording why;</li>
 *   <li>reads text files, code and config with their path, extension and
 *       language; extracts PDF and DOCX documents found inside;</li>
 *   <li>adds one {@link NormalizedDocument.Kind#STRUCTURE} document — the
 *       archive's file tree, including skipped files (a {@code screenshots/}
 *       folder still says something about the project).</li>
 * </ol>
 *
 * <p>Zip-bomb / abuse guards: a cap on entries, on bytes per file, on total
 * inflated bytes, on the per-entry compression ratio and on wall-clock time.
 * Nested archives are not unpacked. A bad entry is skipped, never fatal; only an
 * unreadable archive fails the source.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ZipKnowledgeExtractor {

    static final int MAX_STRUCTURE_LINES = 1_500;

    private final KnowledgeProperties limits;
    private final DocumentTextExtractor documentExtractor;

    public ExtractionResult extract(String archiveName, byte[] bytes) {
        Path temp = null;
        try {
            temp = Files.createTempFile("scrivetta-knowledge-", ".zip");
            Files.write(temp, bytes);
            try (ZipFile zip = open(temp)) {
                return extract(archiveName, zip);
            }
        } catch (ZipException e) {
            return ExtractionResult.failed("Not a valid ZIP archive (the file may be damaged).");
        } catch (IOException e) {
            log.warn("ZIP extraction failed for {}: {}", archiveName, e.getMessage());
            return ExtractionResult.failed("Could not read the ZIP archive.");
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // temp dir cleanup will get it
                }
            }
        }
    }

    /** Open with UTF-8 names, falling back to the legacy CP437 encoding some tools still write. */
    private static ZipFile open(Path file) throws IOException {
        try {
            ZipFile zip = new ZipFile(file.toFile(), StandardCharsets.UTF_8);
            // Force name decoding now so a bad encoding surfaces here.
            zip.stream().forEach(ZipEntry::getName);
            return zip;
        } catch (IllegalArgumentException e) {
            return new ZipFile(file.toFile(), Charset.forName("IBM437"));
        }
    }

    private ExtractionResult extract(String archiveName, ZipFile zip) {
        long deadline = System.currentTimeMillis() + limits.getExtractionTimeoutMs();
        List<SkippedFile> skipped = new ArrayList<>();
        List<NormalizedDocument> documents = new ArrayList<>();

        // 1. Collect file entries (bounded), with safe display paths.
        List<ZipEntry> files = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        int seen = 0;
        boolean truncatedListing = false;
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (++seen > limits.getZipMaxEntries()) {
                truncatedListing = true;
                break;
            }
            if (entry.isDirectory()) continue;
            String path = safePath(entry.getName());
            if (path == null) {
                skipped.add(new SkippedFile(entry.getName(), "unsafe path — ignored"));
                continue;
            }
            files.add(entry);
            paths.add(path);
        }
        if (truncatedListing) {
            skipped.add(new SkippedFile(archiveName, "archive has more than " + limits.getZipMaxEntries()
                    + " entries — the rest was ignored"));
        }
        if (files.isEmpty()) {
            return new ExtractionResult(List.of(), skipped, "The ZIP archive is empty.");
        }

        // A single wrapping folder ("my-shop/src/...") is stripped so paths read
        // like the project's own ("src/...").
        String root = commonRoot(paths);
        if (root != null) {
            paths.replaceAll(p -> p.substring(root.length() + 1));
        }

        // 2. Read each useful file.
        Map<String, String> structure = new TreeMap<>();
        Map<String, Integer> skippedDirs = new TreeMap<>();
        long inflated = 0;
        boolean budgetHit = false;
        for (int i = 0; i < files.size(); i++) {
            ZipEntry entry = files.get(i);
            String path = paths.get(i);

            String reason = FileClassifier.skipReason(path);
            if (reason != null) {
                String dir = skippedDir(path);
                if (dir != null) {
                    skippedDirs.merge(dir, 1, Integer::sum);
                } else {
                    structure.put(path, reason);
                    skipped.add(new SkippedFile(path, reason));
                }
                continue;
            }
            if (budgetHit) {
                structure.put(path, "not read — archive limit reached");
                continue;
            }
            if (System.currentTimeMillis() > deadline) {
                budgetHit = true;
                skipped.add(new SkippedFile(archiveName, "extraction time limit reached — remaining files not read"));
                structure.put(path, "not read — time limit reached");
                continue;
            }
            long declared = entry.getSize();
            if (declared > limits.getMaxFileBytes()) {
                record(structure, skipped, path, "file too large (" + (declared / 1024) + " KB)");
                continue;
            }
            long compressed = entry.getCompressedSize();
            if (declared > 1_000_000 && compressed > 0 && declared / compressed > limits.getZipMaxCompressionRatio()) {
                record(structure, skipped, path, "suspicious compression ratio — ignored");
                continue;
            }

            byte[] data;
            try (InputStream in = zip.getInputStream(entry)) {
                data = DocumentTextExtractor.readBounded(in, limits.getMaxFileBytes());
            } catch (IOException | RuntimeException e) {
                record(structure, skipped, path, "could not be read (" + shortMessage(e) + ")");
                continue;
            }
            inflated += data.length;
            if (compressed > 0 && data.length > 1_000_000 && data.length / compressed > limits.getZipMaxCompressionRatio()) {
                record(structure, skipped, path, "suspicious compression ratio — ignored");
                continue;
            }
            if (inflated > limits.getZipMaxTotalUncompressedBytes()) {
                budgetHit = true;
                skipped.add(new SkippedFile(archiveName, "archive size limit reached — remaining files not read"));
                structure.put(path, "not read — archive limit reached");
                continue;
            }

            NormalizedDocument.Kind kind = FileClassifier.kindOf(path);
            try {
                NormalizedDocument doc = documentExtractor.extract(path, data, kind);
                documents.add(doc);
                structure.put(path, doc.truncated() ? "read (truncated)" : null);
            } catch (DocumentTextExtractor.DocumentExtractionException e) {
                record(structure, skipped, path, e.getMessage());
            }
        }

        // 3. The archive's structure, as its own document.
        documents.add(0, documentExtractor.document(
                archiveName + " (structure)", NormalizedDocument.Kind.STRUCTURE,
                structureListing(archiveName, root, structure, skippedDirs, files.size())));

        for (Map.Entry<String, Integer> dir : skippedDirs.entrySet()) {
            skipped.add(new SkippedFile(dir.getKey() + "/", dir.getValue() + " file(s) — generated/dependency folder"));
        }

        if (documents.size() == 1) {
            // Only the structure: nothing readable inside, but the listing is still knowledge.
            log.info("ZIP {} had no readable files ({} entries)", archiveName, files.size());
        }
        return new ExtractionResult(documents, skipped, null);
    }

    private static void record(Map<String, String> structure, List<SkippedFile> skipped, String path, String reason) {
        structure.put(path, reason);
        skipped.add(new SkippedFile(path, reason));
    }

    /** The top-level skipped folder of a path (e.g. "node_modules"), to collapse its listing. */
    private static String skippedDir(String path) {
        String[] parts = path.split("/");
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            if (prefix.length() > 0) prefix.append('/');
            prefix.append(parts[i]);
            String reason = FileClassifier.skipReason(prefix + "/x");
            if (reason != null && reason.startsWith("generated/dependency")) {
                return prefix.toString();
            }
        }
        return null;
    }

    static String structureListing(String archiveName, String root, Map<String, String> structure,
                                   Map<String, Integer> skippedDirs, int fileCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("Archive: ").append(archiveName).append('\n');
        if (root != null) sb.append("Root folder: ").append(root).append('/').append('\n');
        sb.append("Files: ").append(fileCount).append('\n').append('\n');
        int lines = 0;
        for (Map.Entry<String, String> e : structure.entrySet()) {
            if (++lines > MAX_STRUCTURE_LINES) {
                sb.append("… ").append(structure.size() - MAX_STRUCTURE_LINES).append(" more files\n");
                break;
            }
            sb.append(e.getKey());
            if (e.getValue() != null) sb.append("  [").append(e.getValue()).append(']');
            sb.append('\n');
        }
        for (Map.Entry<String, Integer> dir : skippedDirs.entrySet()) {
            sb.append(dir.getKey()).append("/  [").append(dir.getValue()).append(" files — generated/dependency folder, not read]\n");
        }
        return sb.toString();
    }

    /**
     * Normalise an entry name to a safe relative display path: forward slashes,
     * no leading slash or drive, no "." segments. Returns null for names that try
     * to escape the archive ("..") — they are ignored rather than trusted.
     */
    static String safePath(String name) {
        if (name == null) return null;
        String p = name.replace('\\', '/');
        p = p.replaceFirst("^[A-Za-z]:", "");
        while (p.startsWith("/")) p = p.substring(1);
        List<String> parts = new ArrayList<>();
        for (String part : p.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) return null;
            parts.add(part);
        }
        return parts.isEmpty() ? null : String.join("/", parts);
    }

    /** The single top-level folder all files share, or null. */
    static String commonRoot(List<String> paths) {
        String root = null;
        for (String p : paths) {
            int slash = p.indexOf('/');
            if (slash < 0) return null;
            String first = p.substring(0, slash);
            if (root == null) root = first;
            else if (!root.equals(first)) return null;
        }
        return root;
    }

    private static String shortMessage(Exception e) {
        String m = e.getMessage();
        return (m == null || m.isBlank()) ? e.getClass().getSimpleName() : (m.length() > 80 ? m.substring(0, 80) : m);
    }
}
