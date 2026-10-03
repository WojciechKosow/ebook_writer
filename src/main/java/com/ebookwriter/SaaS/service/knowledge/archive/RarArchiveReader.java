package com.ebookwriter.SaaS.service.knowledge.archive;

import com.ebookwriter.SaaS.config.properties.KnowledgeProperties;
import com.ebookwriter.SaaS.entity.KnowledgeSourceType;
import com.github.junrar.Archive;
import com.github.junrar.ArchiveOptions;
import com.github.junrar.exception.CrcErrorException;
import com.github.junrar.exception.MissingNextVolumeException;
import com.github.junrar.exception.MissingPreviousVolumeException;
import com.github.junrar.exception.RarException;
import com.github.junrar.exception.UnsupportedDictionarySizeException;
import com.github.junrar.exception.UnsupportedRarEncryptedException;
import com.github.junrar.exception.UnsupportedRarMethodException;
import com.github.junrar.exception.UnsupportedRarVersionException;
import com.github.junrar.exception.WrongPasswordException;
import com.github.junrar.rarfile.FileHeader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * RAR (both RAR4 / "RAR 2.9" and RAR5) via junrar — pure Java, so a hostile
 * archive is parsed inside the JVM's memory safety rather than by native code.
 *
 * <p>The archive is read straight from the uploaded bytes: nothing is written to
 * disk, and junrar is given no file to look for sibling volumes next to.
 * The decoder window is capped ({@link KnowledgeProperties#getRarMaxDictionaryBytes()})
 * so a header claiming a 4 GB dictionary cannot claim that much memory.
 * Multi-part (".part1.rar") and password-protected archives are refused with
 * a clear message.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RarArchiveReader implements ArchiveReader {

    /** "Rar!\x1A\x07" — shared by RAR4 ("\x00" next) and RAR5 ("\x01\x00" next). */
    private static final byte[] SIGNATURE = {'R', 'a', 'r', '!', 0x1A, 0x07};

    static final String PASSWORD_PROTECTED =
            "This RAR archive is password-protected. Upload a copy without a password.";
    static final String MULTI_VOLUME =
            "This RAR is one part of a multi-part archive (e.g. project.part1.rar). "
                    + "Upload the project as a single RAR or ZIP file.";

    private final KnowledgeProperties limits;

    @Override
    public KnowledgeSourceType type() {
        return KnowledgeSourceType.RAR;
    }

    @Override
    public boolean matches(byte[] bytes) {
        return ArchiveReader.startsWith(bytes, SIGNATURE);
    }

    @Override
    public OpenedArchive open(byte[] bytes) throws ArchiveReadException {
        if (!matches(bytes)) {
            throw new ArchiveReadException("Not a valid RAR archive (the file may be damaged or not a RAR at all).");
        }
        Archive archive;
        try {
            archive = new Archive(new ByteArrayInputStream(bytes), ArchiveOptions.builder()
                    .maxDictionarySize(limits.getRarMaxDictionaryBytes())
                    .build());
        } catch (WrongPasswordException | UnsupportedRarEncryptedException e) {
            throw new ArchiveReadException(PASSWORD_PROTECTED);
        } catch (UnsupportedRarVersionException e) {
            throw new ArchiveReadException("This RAR archive was made with a format version Scrivetta cannot read.");
        } catch (RarException | IOException | RuntimeException e) {
            log.info("RAR could not be opened: {}", e.toString());
            throw new ArchiveReadException("Not a valid RAR archive (the file may be damaged or incomplete).");
        }

        List<FileHeader> headers = archive.getFileHeaders();
        if (headers.isEmpty() && archive.hasBrokenHeaders()) {
            closeQuietly(archive);
            throw new ArchiveReadException("Not a valid RAR archive (the file may be damaged).");
        }
        for (FileHeader h : headers) {
            if (h.isSplitBefore() || h.isSplitAfter()) {
                closeQuietly(archive);
                throw new ArchiveReadException(MULTI_VOLUME);
            }
            if (h.getRar5WinSize() > limits.getRarMaxDictionaryBytes()) {
                closeQuietly(archive);
                throw new ArchiveReadException("This RAR archive was packed with a compression dictionary over "
                        + size(limits.getRarMaxDictionaryBytes()) + ", which is more than Scrivetta unpacks. "
                        + "Re-pack it with default settings, or upload a ZIP.");
            }
        }
        return new Opened(archive, headers);
    }

    private static String size(long bytes) {
        return bytes >= 1024 * 1024 ? (bytes >> 20) + " MB" : Math.max(1, bytes >> 10) + " KB";
    }

    private static void closeQuietly(Archive archive) {
        try {
            archive.close();
        } catch (IOException ignored) {
            // in-memory
        }
    }

    private record Opened(Archive archive, List<FileHeader> headers) implements OpenedArchive {

        @Override
        public List<ArchiveEntry> entries(int limit) {
            List<ArchiveEntry> out = new ArrayList<>();
            for (FileHeader h : headers) {
                if (out.size() > limit) break;
                long size = h.isUnpSizeUnknown() ? -1 : h.getFullUnpackSize();
                out.add(new ArchiveEntry(h.getFileName(), h.isDirectory(), size, h.getFullPackSize(),
                        h.isEncrypted(), h.getRedirection() != null, h));
            }
            return out;
        }

        @Override
        public boolean solid() {
            return headers.stream().anyMatch(FileHeader::isSolid);
        }

        @Override
        public byte[] read(ArchiveEntry entry, long maxBytes) throws IOException {
            BoundedBuffer out = new BoundedBuffer(maxBytes);
            try {
                archive.extractFile((FileHeader) entry.handle(), out);
            } catch (RarException e) {
                if (e.getCause() instanceof BoundedBuffer.LimitExceeded limit) throw limit;
                throw new IOException(describe(e), e);
            }
            return out.toByteArray();
        }

        @Override
        public void close() {
            closeQuietly(archive);
        }

        private static String describe(RarException e) {
            if (e instanceof CrcErrorException) return "damaged — checksum mismatch";
            if (e instanceof WrongPasswordException || e instanceof UnsupportedRarEncryptedException) {
                return "password-protected";
            }
            if (e instanceof UnsupportedDictionarySizeException) return "compression dictionary too large";
            if (e instanceof UnsupportedRarMethodException) return "unsupported compression method";
            if (e instanceof MissingNextVolumeException || e instanceof MissingPreviousVolumeException) {
                return "continues in another archive part";
            }
            String m = e.getMessage();
            return m == null || m.isBlank() ? "damaged" : "damaged (" + m + ")";
        }
    }

    /** Collects an entry's bytes and stops the decoder once it exceeds the limit. */
    private static final class BoundedBuffer extends OutputStream {

        static final class LimitExceeded extends IOException {
            LimitExceeded(long max) {
                super("entry larger than " + max + " bytes");
            }
        }

        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final long max;

        BoundedBuffer(long max) {
            this.max = max;
        }

        @Override
        public void write(int b) throws IOException {
            ensureRoom(1);
            buffer.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            ensureRoom(len);
            buffer.write(b, off, len);
        }

        private void ensureRoom(int len) throws IOException {
            if (buffer.size() + (long) len > max) throw new LimitExceeded(max);
        }

        byte[] toByteArray() {
            return buffer.toByteArray();
        }
    }
}
