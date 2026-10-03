package com.ebookwriter.SaaS.service.knowledge;

import com.ebookwriter.SaaS.config.properties.KnowledgeProperties;
import com.ebookwriter.SaaS.dto.knowledge.NormalizedDocument;
import com.ebookwriter.SaaS.dto.knowledge.SkippedFile;
import com.ebookwriter.SaaS.entity.KnowledgeSourceType;
import com.ebookwriter.SaaS.service.knowledge.archive.ArchiveEntry;
import com.ebookwriter.SaaS.service.knowledge.archive.ArchiveReadException;
import com.ebookwriter.SaaS.service.knowledge.archive.ArchiveReader;
import com.ebookwriter.SaaS.service.knowledge.archive.OpenedArchive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Safely unpacks an uploaded archive — ZIP or RAR, treated exactly the same —
 * into normalised documents, <b>in memory, never onto disk paths taken from the
 * archive</b>. Each format is only an {@link ArchiveReader} (open, list, read an
 * entry); everything below is shared, so both formats get the same limits, the
 * same path safety and the same documents for the analysis that follows.
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
 * <p>Path safety: entry names are normalised to relative display paths; names
 * that try to leave the archive ({@code ../}) are ignored, links are never
 * followed. Bomb / abuse guards: a cap on entries, on bytes per file, on total
 * unpacked bytes (for solid RAR archives including what must be decoded to
 * reach a file), on the per-entry compression ratio and on wall-clock time.
 * Nested archives are not unpacked. A bad entry is skipped, never fatal; only
 * an unreadable archive fails the source.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArchiveKnowledgeExtractor {

    static final int MAX_STRUCTURE_LINES = 1_500;

    private final KnowledgeProperties limits;
    private final DocumentTextExtractor documentExtractor;
    private final List<ArchiveReader> readers;

    /** Whether uploads of this type are archives handled here. */
    public boolean supports(KnowledgeSourceType type) {
        return type != null && readers.stream().anyMatch(r -> r.type() == type);
    }

    /**
     * Extract an archive. The format is recognised from the content's signature
     * (a RAR renamed to ".zip" still works), falling back to the file extension
     * so a damaged file gets that format's own error message.
     */
    public ExtractionResult extract(String archiveName, byte[] bytes) {
        Optional<ArchiveReader> reader = readers.stream().filter(r -> r.matches(bytes)).findFirst()
                .or(() -> readers.stream().filter(r -> r.type() == KnowledgeSourceType.fromFilename(archiveName)).findFirst());
        if (reader.isEmpty()) {
            return ExtractionResult.failed("Unsupported archive format. Upload a ZIP or RAR file.");
        }
        try (OpenedArchive archive = reader.get().open(bytes)) {
            return extract(archiveName, reader.get().formatName(), archive);
        } catch (ArchiveReadException e) {
            return ExtractionResult.failed(e.getMessage());
        } catch (RuntimeException e) {
            log.warn("{} extraction failed for {}", reader.get().formatName(), archiveName, e);
            return ExtractionResult.failed("Could not read the " + reader.get().formatName()
                    + " archive (the file may be damaged).");
        }
    }

    private ExtractionResult extract(String archiveName, String format, OpenedArchive archive) throws ArchiveReadException {
        long deadline = System.currentTimeMillis() + limits.getExtractionTimeoutMs();
        List<SkippedFile> skipped = new ArrayList<>();
        List<NormalizedDocument> documents = new ArrayList<>();
        boolean solid = archive.solid();

        // 1. Collect file entries (bounded), with safe display paths. For a solid
        //    archive, remember how many bytes must be decoded to reach each file.
        List<ArchiveEntry> files = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        Map<String, String> notRead = new TreeMap<>(); // links / encrypted: path -> reason
        List<Long> decodeCost = new ArrayList<>();
        int fileEntries = 0;
        int unsafe = 0;
        int encrypted = 0;
        long decoded = 0;
        List<ArchiveEntry> entries = archive.entries(limits.getArchiveMaxEntries());
        boolean truncatedListing = entries.size() > limits.getArchiveMaxEntries();
        if (truncatedListing) entries = entries.subList(0, limits.getArchiveMaxEntries());
        for (ArchiveEntry entry : entries) {
            if (entry.directory()) continue;
            fileEntries++;
            decoded += entry.size() >= 0 ? entry.size() : limits.getMaxFileBytes();
            String path = safePath(entry.name());
            if (path == null) {
                unsafe++;
                skipped.add(new SkippedFile(displayName(entry.name()), "unsafe path — ignored"));
                continue;
            }
            if (entry.link()) {
                notRead.put(path, "link — ignored");
                continue;
            }
            if (entry.encrypted()) {
                encrypted++;
                notRead.put(path, "password-protected — ignored");
                continue;
            }
            files.add(entry);
            paths.add(path);
            decodeCost.add(decoded);
        }
        if (truncatedListing) {
            skipped.add(new SkippedFile(archiveName, "archive has more than " + limits.getArchiveMaxEntries()
                    + " entries — the rest was ignored"));
        }
        if (fileEntries == 0) {
            return new ExtractionResult(List.of(), skipped, "The " + format + " archive is empty — it contains no files.");
        }
        if (files.isEmpty()) {
            notRead.forEach((path, reason) -> skipped.add(new SkippedFile(path, reason)));
            String why = encrypted > 0 && unsafe == 0
                    ? "The " + format + " archive is password-protected. Upload a copy without a password."
                    : unsafe == fileEntries
                    ? "The " + format + " archive has an invalid structure: every entry has an unsafe path"
                    + " (one pointing outside the archive, such as ../), so nothing was unpacked."
                    : "The " + format + " archive contains no files that can be read (only links,"
                    + " password-protected or unsafe entries).";
            return new ExtractionResult(List.of(), skipped, why);
        }

        // A single wrapping folder ("my-shop/src/...") is stripped so paths read
        // like the project's own ("src/...").
        String root = commonRoot(paths);
        if (root != null) {
            paths.replaceAll(p -> p.substring(root.length() + 1));
        }

        // 2. Read each useful file — in archive order (required for solid archives).
        Map<String, String> structure = new TreeMap<>();
        notRead.forEach((path, reason) -> record(structure, skipped,
                root != null && path.startsWith(root + "/") ? path.substring(root.length() + 1) : path, reason));
        Map<String, Integer> skippedDirs = new TreeMap<>();
        long inflated = 0;
        boolean budgetHit = false;
        int attempted = 0;
        int unreadable = 0;
        for (int i = 0; i < files.size(); i++) {
            ArchiveEntry entry = files.get(i);
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
            long declared = entry.size();
            if (declared > limits.getMaxFileBytes()) {
                record(structure, skipped, path, "file too large (" + (declared / 1024) + " KB)");
                continue;
            }
            if (solid && decodeCost.get(i) > limits.getArchiveMaxTotalUncompressedBytes()) {
                // Reaching this file would mean decoding everything before it.
                budgetHit = true;
                skipped.add(new SkippedFile(archiveName, "archive size limit reached — remaining files not read"));
                structure.put(path, "not read — archive limit reached");
                continue;
            }
            // In a solid archive the packed size of one entry is meaningless (it
            // shares a stream with its neighbours); the byte bounds still apply.
            long compressed = solid ? -1 : entry.compressedSize();
            if (declared > 1_000_000 && compressed > 0 && declared / compressed > limits.getArchiveMaxCompressionRatio()) {
                record(structure, skipped, path, "suspicious compression ratio — ignored");
                continue;
            }

            byte[] data;
            attempted++;
            try {
                data = archive.read(entry, limits.getMaxFileBytes());
            } catch (IOException | RuntimeException e) {
                unreadable++;
                record(structure, skipped, path, "could not be read (" + shortMessage(e) + ")");
                continue;
            }
            inflated += data.length;
            if (compressed > 0 && data.length > 1_000_000 && data.length / compressed > limits.getArchiveMaxCompressionRatio()) {
                record(structure, skipped, path, "suspicious compression ratio — ignored");
                continue;
            }
            if (inflated > limits.getArchiveMaxTotalUncompressedBytes()) {
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

        if (attempted > 0 && unreadable == attempted) {
            // Every file we tried to unpack failed: the archive itself is damaged.
            return new ExtractionResult(List.of(), skipped, "The " + format
                    + " archive is damaged — none of its files could be unpacked.");
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
            log.info("{} {} had no readable files ({} entries)", format, archiveName, files.size());
        }
        return new ExtractionResult(documents, skipped, null);
    }

    /** An entry name as shown in the skipped list — never trusted, only displayed. */
    private static String displayName(String name) {
        if (name == null) return "(unnamed entry)";
        String clean = name.replaceAll("\\p{Cntrl}", "?");
        return clean.length() > 200 ? clean.substring(0, 200) + "…" : clean;
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
     * to escape the archive ("..") or carry control characters — they are
     * ignored rather than trusted.
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
            if (part.chars().anyMatch(Character::isISOControl)) return null;
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
