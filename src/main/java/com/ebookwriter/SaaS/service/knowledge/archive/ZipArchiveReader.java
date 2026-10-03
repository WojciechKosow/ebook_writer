package com.ebookwriter.SaaS.service.knowledge.archive;

import com.ebookwriter.SaaS.entity.KnowledgeSourceType;
import com.ebookwriter.SaaS.service.knowledge.DocumentTextExtractor;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * ZIP via {@code java.util.zip}. The upload is spooled to a private temp file
 * (deleted on close) so entries can be read randomly and skipped for free;
 * entry contents are only ever read into memory.
 */
@Slf4j
@Component
public class ZipArchiveReader implements ArchiveReader {

    private static final byte[] LOCAL_HEADER = {'P', 'K', 3, 4};
    private static final byte[] EMPTY_ARCHIVE = {'P', 'K', 5, 6};
    private static final byte[] SPANNED = {'P', 'K', 7, 8};

    @Override
    public KnowledgeSourceType type() {
        return KnowledgeSourceType.ZIP;
    }

    @Override
    public boolean matches(byte[] bytes) {
        return ArchiveReader.startsWith(bytes, LOCAL_HEADER) || ArchiveReader.startsWith(bytes, EMPTY_ARCHIVE)
                || ArchiveReader.startsWith(bytes, SPANNED);
    }

    @Override
    public OpenedArchive open(byte[] bytes) throws ArchiveReadException {
        Path temp = null;
        try {
            temp = Files.createTempFile("scrivetta-knowledge-", ".zip");
            Files.write(temp, bytes);
            return new Opened(openZip(temp), temp);
        } catch (ZipException e) {
            delete(temp);
            if (e.getMessage() != null && e.getMessage().contains("encrypted")) {
                throw new ArchiveReadException("This ZIP archive is password-protected. Upload a copy without a password.");
            }
            throw new ArchiveReadException("Not a valid ZIP archive (the file may be damaged).");
        } catch (IOException e) {
            delete(temp);
            log.warn("ZIP could not be opened: {}", e.getMessage());
            throw new ArchiveReadException("Could not read the ZIP archive.");
        }
    }

    /** Open with UTF-8 names, falling back to the legacy CP437 encoding some tools still write. */
    private static ZipFile openZip(Path file) throws IOException {
        try {
            ZipFile zip = new ZipFile(file.toFile(), StandardCharsets.UTF_8);
            // Force name decoding now so a bad encoding surfaces here.
            try {
                zip.stream().forEach(ZipEntry::getName);
            } catch (IllegalArgumentException e) {
                zip.close();
                throw e;
            }
            return zip;
        } catch (IllegalArgumentException e) {
            return new ZipFile(file.toFile(), Charset.forName("IBM437"));
        }
    }

    private static void delete(Path temp) {
        if (temp == null) return;
        try {
            Files.deleteIfExists(temp);
        } catch (IOException ignored) {
            // temp dir cleanup will get it
        }
    }

    private record Opened(ZipFile zip, Path temp) implements OpenedArchive {

        @Override
        public List<ArchiveEntry> entries(int limit) {
            List<ArchiveEntry> out = new ArrayList<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements() && out.size() <= limit) {
                ZipEntry e = entries.nextElement();
                // java.util.zip does not expose the encryption flag; an encrypted
                // entry fails on read and is skipped with that reason.
                out.add(new ArchiveEntry(e.getName(), e.isDirectory(), e.getSize(), e.getCompressedSize(),
                        false, false, e));
            }
            return out;
        }

        @Override
        public boolean solid() {
            return false;
        }

        @Override
        public byte[] read(ArchiveEntry entry, long maxBytes) throws IOException {
            try (InputStream in = zip.getInputStream((ZipEntry) entry.handle())) {
                return DocumentTextExtractor.readBounded(in, maxBytes);
            }
        }

        @Override
        public void close() {
            try {
                zip.close();
            } catch (IOException ignored) {
                // nothing useful to do
            }
            delete(temp);
        }
    }
}
