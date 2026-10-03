package com.ebookwriter.SaaS.service.knowledge.archive;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;

/**
 * An archive opened by an {@link ArchiveReader}. Entries are read <b>in memory</b>
 * — nothing is ever written to a path taken from the archive.
 */
public interface OpenedArchive extends Closeable {

    /**
     * Entries in archive order, at most {@code limit + 1} of them (one extra so
     * the caller can tell the listing was cut).
     */
    List<ArchiveEntry> entries(int limit) throws ArchiveReadException;

    /**
     * A solid archive compresses files as one stream: reaching an entry means
     * decoding every entry before it. The extractor then budgets declared sizes
     * cumulatively and must read entries in archive order.
     */
    boolean solid();

    /**
     * The entry's bytes, at most {@code maxBytes}; a larger entry throws instead
     * of being inflated further. Read failures of one entry are an
     * {@link IOException} with a short, author-readable message.
     */
    byte[] read(ArchiveEntry entry, long maxBytes) throws IOException;

    @Override
    void close();
}
