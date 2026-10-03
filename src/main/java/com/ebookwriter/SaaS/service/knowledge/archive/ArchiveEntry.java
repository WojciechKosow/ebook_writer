package com.ebookwriter.SaaS.service.knowledge.archive;

/**
 * One entry of an opened archive, as its format reader reports it — before any
 * path is trusted. {@code name} is the raw name from the archive (it may be
 * absolute or contain {@code ..}); the shared extractor normalises or rejects it.
 *
 * @param name           raw entry name from the archive
 * @param directory      a folder entry (nothing to read)
 * @param size           declared unpacked size in bytes, or -1 when unknown
 * @param compressedSize declared packed size in bytes, or -1 when unknown
 * @param encrypted      the entry's data is password-protected
 * @param link           a symbolic/hard link or other redirection — never followed
 * @param handle         the format's own entry object, for {@link OpenedArchive#read}
 */
public record ArchiveEntry(String name, boolean directory, long size, long compressedSize,
                           boolean encrypted, boolean link, Object handle) {
}
