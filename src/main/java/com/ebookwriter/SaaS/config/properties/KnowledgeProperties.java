package com.ebookwriter.SaaS.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Limits for knowledge ingestion ("Tell Scrivetta what you know"): what an
 * author may upload, how far an archive is unpacked, and how much of it is sent
 * to OpenAI per processing run. Together they bound both the server work and the
 * OpenAI cost of a single book — a huge archive is read only up to these limits and
 * the remainder is recorded as "not analysed" rather than silently sent.
 *
 * <p>Rough cost math with the defaults: {@link #maxAnalysisChars} 600k chars ≈
 * 150k input tokens per run, plus one consolidation call — a few cents on a
 * mini model. {@link #maxProcessingRuns} caps re-runs per book.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "knowledge")
public class KnowledgeProperties {

    // ---- Upload --------------------------------------------------------------

    /** Largest single upload (bytes). Keep ≤ spring.servlet.multipart.max-file-size. */
    private long maxUploadBytes = 25L * 1024 * 1024;

    /** Most sources (files + the notes) one book may hold. */
    private int maxSourcesPerBook = 20;

    /** Longest pasted notes text (chars). */
    private int maxNotesChars = 100_000;

    // ---- Extraction ------------------------------------------------------------

    /** Most entries read from one archive — ZIP or RAR (protection against archives of millions of files). */
    private int archiveMaxEntries = 5_000;

    /**
     * Total bytes actually unpacked from one archive before extraction stops
     * (zip/rar-bomb guard). For a solid RAR this also counts the files that
     * must be decoded to reach a later one, even if they are skipped.
     */
    private long archiveMaxTotalUncompressedBytes = 200L * 1024 * 1024;

    /** An entry that unpacks to more than this many times its compressed size is skipped. */
    private int archiveMaxCompressionRatio = 100;

    /**
     * Largest RAR compression dictionary accepted. The decoder allocates a
     * window this big, so it bounds the memory one upload can claim (RAR5
     * allows up to 4 GB; WinRAR's default is 32 MB).
     */
    private long rarMaxDictionaryBytes = 128L * 1024 * 1024;

    /** Wall-clock limit for extracting one upload (ms). */
    private long extractionTimeoutMs = 60_000;

    /** Largest single file read (inside an archive or a DOCX); bigger files are skipped. */
    private long maxFileBytes = 5L * 1024 * 1024;

    /** Pages read from one PDF; the rest is skipped (and noted). */
    private int maxPdfPages = 500;

    /** Text kept per normalised document; longer documents are truncated (and flagged). */
    private int maxDocumentChars = 100_000;

    /** Text stored per book across all its sources. */
    private long maxStoredCharsPerBook = 3_000_000;

    // ---- Analysis (OpenAI) -----------------------------------------------------

    /** Material per OpenAI request (chars, ~4 chars/token). Larger documents are split. */
    private int maxCharsPerChunk = 60_000;

    /** Material sent to OpenAI per processing run; the rest is recorded as not analysed. */
    private long maxAnalysisChars = 600_000;

    /** Most extraction requests per run (the consolidation call is extra). */
    private int maxChunks = 15;

    /** How many times one book's materials may be (re)processed. */
    private int maxProcessingRuns = 10;

    /** Excerpt of the author's notes repeated as context in every later batch. */
    private int notesContextChars = 4_000;

    /** A run stuck in PROCESSING/ANALYZING longer than this may be restarted (minutes). */
    private int staleProcessingMinutes = 30;
}
