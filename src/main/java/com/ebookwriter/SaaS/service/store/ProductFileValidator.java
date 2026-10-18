package com.ebookwriter.SaaS.service.store;

import com.ebookwriter.SaaS.entity.ProductFileFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Server-side checks for a store product file before it is stored. Nothing is
 * executed, converted or extracted: the type is decided by the extension AND the
 * file's own bytes, and a ZIP/EPUB is only inspected through its central
 * directory (entry names), never inflated — so a zip bomb costs nothing.
 *
 * <ul>
 *   <li>Only {@code .pdf}, {@code .epub} and {@code .zip}; the bytes must match.</li>
 *   <li>Native executables (PE, ELF, Mach-O, scripts with a shebang) are refused
 *       whatever their name.</li>
 *   <li>A ZIP/EPUB may not contain executables or installers, nor entry names that
 *       would escape the folder it is unpacked into (zip-slip).</li>
 * </ul>
 */
public final class ProductFileValidator {

    static final int HEAD_BYTES = 1024;
    static final int MAX_ZIP_ENTRIES = 50_000;
    static final long MAX_CENTRAL_DIRECTORY_BYTES = 32L * 1024 * 1024;

    /** Executables and installers refused inside archives (any path segment, e.g. {@code Foo.app/}). */
    static final Set<String> EXECUTABLE_EXTENSIONS = Set.of(
            "exe", "msi", "msp", "msix", "appx", "bat", "cmd", "com", "scr", "pif", "cpl", "vbs", "vbe",
            "wsf", "wsh", "ps1", "psm1", "hta", "reg", "msc", "lnk", "jar", "apk", "ipa", "app", "dmg",
            "pkg", "dll", "sys", "deb", "rpm");

    private static final int LOCAL_HEADER = 0x04034b50;
    private static final int CENTRAL_HEADER = 0x02014b50;
    private static final int END_OF_CENTRAL_DIR = 0x06054b50;
    private static final int ZIP64_LOCATOR = 0x07064b50;
    private static final int ZIP64_END = 0x06064b50;

    private ProductFileValidator() {
    }

    /**
     * Validate the upload at {@code file} and return its format. Throws a 400 (or
     * 413 for size) {@link ResponseStatusException} with a message for the author.
     */
    public static ProductFileFormat validate(String filename, Path file, long size, long maxBytes) {
        if (size <= 0) {
            throw bad("The file is empty.");
        }
        if (size > maxBytes) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE,
                    "Files can be up to " + (maxBytes / (1024 * 1024)) + " MB.");
        }
        ProductFileFormat declared = formatOf(filename);
        if (declared == null) {
            throw bad("Upload a PDF, EPUB or ZIP file.");
        }
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            byte[] head = new byte[(int) Math.min(HEAD_BYTES, size)];
            raf.readFully(head);
            if (isExecutable(head)) {
                throw bad("Executable files can't be sold. Upload a PDF, EPUB or ZIP file.");
            }
            switch (declared) {
                case PDF -> {
                    if (indexOf(head, "%PDF-".getBytes(StandardCharsets.US_ASCII)) < 0) {
                        throw bad("This file isn't a valid PDF.");
                    }
                }
                case ZIP -> {
                    if (!isZip(head)) {
                        throw bad("This file isn't a valid ZIP archive.");
                    }
                    checkEntries(readEntryNames(raf, size));
                }
                case EPUB -> {
                    if (!isZip(head)) {
                        throw bad("This file isn't a valid EPUB.");
                    }
                    List<String> names = readEntryNames(raf, size);
                    if (!names.contains("mimetype") || !names.contains("META-INF/container.xml")
                            || indexOf(head, "application/epub+zip".getBytes(StandardCharsets.US_ASCII)) < 0) {
                        throw bad("This file isn't a valid EPUB.");
                    }
                    checkEntries(names);
                }
            }
        } catch (IOException e) {
            throw bad("Could not read the file.");
        }
        return declared;
    }

    /** The format a filename claims by its (last) extension, or null when not allowed. */
    static ProductFileFormat formatOf(String filename) {
        if (filename == null) {
            return null;
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return null;
        }
        return switch (filename.substring(dot + 1).toLowerCase(Locale.ROOT)) {
            case "pdf" -> ProductFileFormat.PDF;
            case "epub" -> ProductFileFormat.EPUB;
            case "zip" -> ProductFileFormat.ZIP;
            default -> null;
        };
    }

    /**
     * The author's filename made safe to store and to send back in
     * Content-Disposition: no directories, no control or reserved characters,
     * at most 200 characters with the extension kept.
     */
    public static String sanitizeFilename(String original, ProductFileFormat format) {
        String name = original == null ? "" : original;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = name.replaceAll("[\\p{Cntrl}\"<>:|?*]", "").replaceAll("\\s+", " ").trim();
        String ext = "." + format.extension();
        String base = name.toLowerCase(Locale.ROOT).endsWith(ext) ? name.substring(0, name.length() - ext.length()) : name;
        base = base.replaceAll("^\\.+", "").trim();
        if (base.isEmpty()) {
            base = "file";
        }
        if (base.length() > 200 - ext.length()) {
            base = base.substring(0, 200 - ext.length()).trim();
        }
        return base + ext;
    }

    static boolean isExecutable(byte[] h) {
        if (h.length >= 2 && h[0] == 'M' && h[1] == 'Z') {
            return true; // Windows PE
        }
        if (h.length >= 2 && h[0] == '#' && h[1] == '!') {
            return true; // script with a shebang
        }
        if (h.length >= 4) {
            int be = ((h[0] & 0xff) << 24) | ((h[1] & 0xff) << 16) | ((h[2] & 0xff) << 8) | (h[3] & 0xff);
            return be == 0x7f454c46 // ELF
                    || be == 0xfeedface || be == 0xfeedfacf || be == 0xcefaedfe || be == 0xcffaedfe // Mach-O
                    || be == 0xcafebabe; // Mach-O universal / Java class
        }
        return false;
    }

    private static boolean isZip(byte[] h) {
        return h.length >= 4 && ByteBuffer.wrap(h, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() == LOCAL_HEADER;
    }

    private static void checkEntries(List<String> names) {
        if (names.isEmpty()) {
            throw bad("The archive is empty.");
        }
        for (String name : names) {
            String n = name.replace('\\', '/');
            if (n.startsWith("/") || n.matches("^[A-Za-z]:.*") || ("/" + n + "/").contains("/../")) {
                throw bad("The archive contains an unsafe path (" + abbreviate(name) + ").");
            }
            for (String segment : n.split("/")) {
                int dot = segment.lastIndexOf('.');
                if (dot > 0 && EXECUTABLE_EXTENSIONS.contains(segment.substring(dot + 1).toLowerCase(Locale.ROOT))) {
                    throw bad("Archives can't contain programs or installers (" + abbreviate(name) + ").");
                }
            }
        }
    }

    /** Entry names from the ZIP central directory (ZIP64 aware). Reads names only — no data is inflated. */
    static List<String> readEntryNames(RandomAccessFile raf, long size) throws IOException {
        int tail = (int) Math.min(size, 22 + 65_535);
        byte[] buf = new byte[tail];
        raf.seek(size - tail);
        raf.readFully(buf);
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
        int eocd = -1;
        for (int i = tail - 22; i >= 0; i--) {
            if (bb.getInt(i) == END_OF_CENTRAL_DIR) {
                eocd = i;
                break;
            }
        }
        if (eocd < 0) {
            throw bad("This archive is damaged or incomplete.");
        }
        long eocdPos = size - tail + eocd;
        long entries = bb.getShort(eocd + 10) & 0xffff;
        long cdSize = bb.getInt(eocd + 12) & 0xffffffffL;
        long cdOffset = bb.getInt(eocd + 16) & 0xffffffffL;
        if (entries == 0xffff || cdSize == 0xffffffffL || cdOffset == 0xffffffffL) {
            if (eocd < 20 || bb.getInt(eocd - 20) != ZIP64_LOCATOR) {
                throw bad("This archive is damaged or incomplete.");
            }
            long zip64EndPos = bb.getLong(eocd - 20 + 8);
            if (zip64EndPos < 0 || zip64EndPos + 56 > size) {
                throw bad("This archive is damaged or incomplete.");
            }
            byte[] z = new byte[56];
            raf.seek(zip64EndPos);
            raf.readFully(z);
            ByteBuffer zb = ByteBuffer.wrap(z).order(ByteOrder.LITTLE_ENDIAN);
            if (zb.getInt(0) != ZIP64_END) {
                throw bad("This archive is damaged or incomplete.");
            }
            entries = zb.getLong(32);
            cdSize = zb.getLong(40);
            cdOffset = zb.getLong(48);
            eocdPos = zip64EndPos;
        }
        if (entries > MAX_ZIP_ENTRIES) {
            throw bad("The archive has too many files (max " + MAX_ZIP_ENTRIES + ").");
        }
        if (cdOffset < 0 || cdSize < 0 || cdSize > MAX_CENTRAL_DIRECTORY_BYTES || cdOffset + cdSize > eocdPos) {
            throw bad("This archive is damaged or incomplete.");
        }
        byte[] cd = new byte[(int) cdSize];
        raf.seek(cdOffset);
        raf.readFully(cd);
        ByteBuffer c = ByteBuffer.wrap(cd).order(ByteOrder.LITTLE_ENDIAN);
        List<String> names = new ArrayList<>();
        int pos = 0;
        for (long i = 0; i < entries; i++) {
            if (pos + 46 > cd.length || c.getInt(pos) != CENTRAL_HEADER) {
                throw bad("This archive is damaged or incomplete.");
            }
            int flags = c.getShort(pos + 8) & 0xffff;
            int nameLen = c.getShort(pos + 28) & 0xffff;
            int extraLen = c.getShort(pos + 30) & 0xffff;
            int commentLen = c.getShort(pos + 32) & 0xffff;
            if (pos + 46 + nameLen > cd.length) {
                throw bad("This archive is damaged or incomplete.");
            }
            names.add(new String(cd, pos + 46, nameLen,
                    (flags & 0x800) != 0 ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1));
            pos += 46 + nameLen + extraLen + commentLen;
        }
        return names;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static String abbreviate(String name) {
        return name.length() <= 80 ? name : name.substring(0, 77) + "...";
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
