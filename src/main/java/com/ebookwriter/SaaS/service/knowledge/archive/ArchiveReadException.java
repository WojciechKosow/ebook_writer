package com.ebookwriter.SaaS.service.knowledge.archive;

/**
 * The archive as a whole cannot be read (not an archive of this format, damaged,
 * password-protected, split into volumes, …). The message is shown to the author
 * as is, so it says what happened and what to do.
 */
public class ArchiveReadException extends Exception {

    public ArchiveReadException(String userMessage) {
        super(userMessage);
    }
}
