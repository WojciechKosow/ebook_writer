package com.ebookwriter.SaaS.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One piece of material the author gave Scrivetta for a book: an uploaded file
 * (ZIP / RAR / PDF / DOCX / TXT / MD) or the pasted notes.
 *
 * <p>The file is extracted and normalised on upload; only the resulting text is
 * kept (the raw bytes are not stored). {@link #documentsJson} holds the
 * normalised documents — for an archive one per useful file, each with its path, so
 * every later fact can point back to where it came from. {@link #skippedJson}
 * lists what was left out and why (binary, too large, dependency folder, …).
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "knowledge_sources", indexes = @Index(name = "idx_knowledge_sources_ebook", columnList = "ebook_id"))
public class KnowledgeSource {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ebook_id")
    private Ebook ebook;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private KnowledgeSourceType sourceType;

    /** Original filename, or "user-notes" for pasted notes. */
    @Column(nullable = false)
    private String originalFilename;

    private long sizeBytes;

    /** SHA-256 of the uploaded bytes — the same file uploaded twice is rejected. */
    private String rawSha256;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private KnowledgeSourceStatus status;

    @Column(columnDefinition = "text")
    private String errorMessage;

    /** Normalised documents extracted from this source. */
    private int documentCount;

    /** Files left out during extraction (see {@link #skippedJson}). */
    private int skippedCount;

    /** Total characters of normalised text stored for this source. */
    private long extractedChars;

    /** JSON array of {@code NormalizedDocument}. */
    @Column(columnDefinition = "text")
    private String documentsJson;

    /** JSON array of {@code SkippedFile} ({path, reason}). */
    @Column(columnDefinition = "text")
    private String skippedJson;

    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
