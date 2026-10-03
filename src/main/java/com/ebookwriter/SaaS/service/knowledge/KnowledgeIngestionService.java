package com.ebookwriter.SaaS.service.knowledge;

import com.ebookwriter.SaaS.config.properties.KnowledgeProperties;
import com.ebookwriter.SaaS.dto.knowledge.KnowledgeSourceDTO;
import com.ebookwriter.SaaS.dto.knowledge.NormalizedDocument;
import com.ebookwriter.SaaS.dto.knowledge.SkippedFile;
import com.ebookwriter.SaaS.entity.BookKnowledge;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookStatus;
import com.ebookwriter.SaaS.entity.KnowledgeSource;
import com.ebookwriter.SaaS.entity.KnowledgeSourceStatus;
import com.ebookwriter.SaaS.entity.KnowledgeSourceType;
import com.ebookwriter.SaaS.entity.KnowledgeStatus;
import com.ebookwriter.SaaS.repository.BookKnowledgeRepository;
import com.ebookwriter.SaaS.repository.EbookRepository;
import com.ebookwriter.SaaS.repository.KnowledgeSourceRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Step one of "Tell Scrivetta what you know": accepting the author's materials.
 * Each upload is validated (size, format, per-book limits, same file twice),
 * extracted and normalised <b>immediately</b>, and stored as a
 * {@link KnowledgeSource} with its normalised documents — so a corrupt file is
 * reported straight away and processing later works only from stored text.
 * Nothing here calls an AI model.
 *
 * <p>Only a {@link EbookStatus#DRAFT} book accepts materials, and not while a
 * processing run is in flight. Any change marks existing knowledge as stale
 * ({@link KnowledgeStatus#MATERIALS_UPLOADING}) so it is re-processed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeIngestionService {

    public static final List<String> ACCEPTED_FORMATS = List.of("zip", "rar", "pdf", "docx", "txt", "md");

    private final EbookRepository ebookRepository;
    private final KnowledgeSourceRepository sourceRepository;
    private final BookKnowledgeRepository knowledgeRepository;
    private final ArchiveKnowledgeExtractor archiveExtractor;
    private final DocumentTextExtractor documentExtractor;
    private final KnowledgeProperties limits;

    /** Upload one file (ZIP / RAR / PDF / DOCX / TXT / MD). */
    public KnowledgeSourceDTO addFile(UUID ebookId, UUID userId, MultipartFile file) {
        Ebook ebook = requireEditableDraft(ebookId, userId);
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The file is empty.");
        }
        if (file.getSize() > limits.getMaxUploadBytes()) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE,
                    "The file is too large (max " + mb(limits.getMaxUploadBytes()) + " MB).");
        }
        String filename = sanitizeFilename(file.getOriginalFilename());
        KnowledgeSourceType type = KnowledgeSourceType.fromFilename(filename);
        if (type == null) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Unsupported file type. Upload ZIP, RAR, PDF, DOCX, TXT or MD files, or paste your notes.");
        }
        requireRoomForSource(ebookId);

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read the uploaded file.");
        }
        String sha = TextNormalizer.sha256(bytes);
        if (sourceRepository.existsByEbookIdAndRawSha256(ebookId, sha)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This file has already been added.");
        }

        ExtractionResult result = extract(type, filename, bytes);
        result = fitStorageBudget(ebookId, result);
        KnowledgeSource source = sourceRepository.save(toSource(ebook, type, filename, bytes.length, sha, result));
        log.info("Knowledge source {} added to ebook {}: {} {} ({} docs, {} skipped, {} chars, status {})",
                source.getId(), ebookId, type, filename, source.getDocumentCount(), source.getSkippedCount(),
                source.getExtractedChars(), source.getStatus());
        markMaterialsChanged(ebook);
        return toDto(source);
    }

    /**
     * Set the author's pasted notes (one notes source per book; saving again
     * replaces it, blank text removes it). Notes are taken exactly as written —
     * rough, unordered, misspelled notes are valid input.
     */
    public KnowledgeSourceDTO setNotes(UUID ebookId, UUID userId, String text) {
        Ebook ebook = requireEditableDraft(ebookId, userId);
        var existing = sourceRepository.findFirstByEbookIdAndSourceType(ebookId, KnowledgeSourceType.NOTES);
        String normalized = TextNormalizer.normalize(text);
        if (normalized.isBlank()) {
            existing.ifPresent(s -> {
                sourceRepository.delete(s);
                markMaterialsChanged(ebook);
            });
            return null;
        }
        if (normalized.length() > limits.getMaxNotesChars()) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE,
                    "Notes are too long (max " + limits.getMaxNotesChars() + " characters). Upload longer material as a file.");
        }
        if (existing.isEmpty()) requireRoomForSource(ebookId);

        NormalizedDocument doc = documentExtractor.document(KnowledgeChunker.NOTES_REF, NormalizedDocument.Kind.NOTES, normalized);
        ExtractionResult result = new ExtractionResult(List.of(doc), List.of(), null);
        byte[] raw = normalized.getBytes(StandardCharsets.UTF_8);
        KnowledgeSource source = existing.orElseGet(() -> KnowledgeSource.builder().ebook(ebook)
                .sourceType(KnowledgeSourceType.NOTES).originalFilename(KnowledgeChunker.NOTES_REF).build());
        KnowledgeSource filled = toSource(ebook, KnowledgeSourceType.NOTES, KnowledgeChunker.NOTES_REF, raw.length,
                TextNormalizer.sha256(raw), result);
        source.setSizeBytes(filled.getSizeBytes());
        source.setRawSha256(filled.getRawSha256());
        source.setStatus(filled.getStatus());
        source.setErrorMessage(null);
        source.setDocumentCount(filled.getDocumentCount());
        source.setSkippedCount(0);
        source.setExtractedChars(filled.getExtractedChars());
        source.setDocumentsJson(filled.getDocumentsJson());
        source.setSkippedJson(filled.getSkippedJson());
        source = sourceRepository.save(source);
        markMaterialsChanged(ebook);
        return toDto(source);
    }

    public void deleteSource(UUID ebookId, UUID userId, UUID sourceId) {
        Ebook ebook = requireEditableDraft(ebookId, userId);
        KnowledgeSource source = sourceRepository.findByIdAndEbookId(sourceId, ebookId)
                .orElseThrow(() -> new IllegalArgumentException("Source not found"));
        sourceRepository.delete(source);
        markMaterialsChanged(ebook);
    }

    /** The saved notes text, or null when the book has none. */
    public String notesText(UUID ebookId) {
        return sourceRepository.findFirstByEbookIdAndSourceType(ebookId, KnowledgeSourceType.NOTES)
                .map(KnowledgeSource::getDocumentsJson)
                .map(json -> {
                    try {
                        List<NormalizedDocument> docs = KnowledgeAssembler.MAPPER.readValue(json,
                                new TypeReference<List<NormalizedDocument>>() {
                                });
                        return docs.isEmpty() ? null : docs.get(0).content();
                    } catch (Exception e) {
                        return null;
                    }
                })
                .orElse(null);
    }

    public List<KnowledgeSourceDTO> listSources(UUID ebookId) {
        return sourceRepository.findByEbookIdOrderByCreatedAtAsc(ebookId).stream().map(KnowledgeIngestionService::toDto).toList();
    }

    // ---- internals ---------------------------------------------------------------

    ExtractionResult extract(KnowledgeSourceType type, String filename, byte[] bytes) {
        try {
            if (archiveExtractor.supports(type)) {
                // ZIP and RAR share one extraction path into the same documents.
                return archiveExtractor.extract(filename, bytes);
            }
            NormalizedDocument doc = documentExtractor.extract(filename, bytes, NormalizedDocument.Kind.DOCUMENT);
            return new ExtractionResult(List.of(doc), List.of(), null);
        } catch (DocumentTextExtractor.DocumentExtractionException e) {
            return ExtractionResult.failed("Could not read " + filename + ": " + e.getMessage() + ".");
        } catch (RuntimeException e) {
            log.warn("Extraction of {} failed unexpectedly", filename, e);
            return ExtractionResult.failed("Could not read " + filename + ".");
        }
    }

    /** Keep the book's stored text bounded: drop the lowest-priority documents that don't fit. */
    private ExtractionResult fitStorageBudget(UUID ebookId, ExtractionResult result) {
        long used = sourceRepository.findByEbookIdOrderByCreatedAtAsc(ebookId).stream()
                .mapToLong(KnowledgeSource::getExtractedChars).sum();
        long room = limits.getMaxStoredCharsPerBook() - used;
        if (result.totalChars() <= room) return result;
        List<NormalizedDocument> kept = new ArrayList<>();
        List<SkippedFile> skipped = new ArrayList<>(result.skipped());
        long total = 0;
        for (NormalizedDocument d : result.documents()) {
            if (total + d.chars() <= room) {
                kept.add(d);
                total += d.chars();
            } else {
                skipped.add(new SkippedFile(d.path(), "book storage limit reached"));
            }
        }
        String error = kept.isEmpty() ? "This book already holds the maximum amount of material." : result.error();
        return new ExtractionResult(kept, skipped, error);
    }

    private static KnowledgeSource toSource(Ebook ebook, KnowledgeSourceType type, String filename, long size,
                                            String sha, ExtractionResult result) {
        boolean failed = result.error() != null || result.documents().isEmpty();
        KnowledgeSourceStatus status = failed ? KnowledgeSourceStatus.FAILED
                : result.skipped().isEmpty() ? KnowledgeSourceStatus.EXTRACTED : KnowledgeSourceStatus.PARTIAL;
        return KnowledgeSource.builder()
                .ebook(ebook)
                .sourceType(type)
                .originalFilename(filename)
                .sizeBytes(size)
                .rawSha256(sha)
                .status(status)
                .errorMessage(failed ? (result.error() != null ? result.error() : "Nothing readable was found.") : null)
                .documentCount(result.documents().size())
                .skippedCount(result.skipped().size())
                .extractedChars(result.totalChars())
                .documentsJson(KnowledgeAssembler.write(result.documents()))
                .skippedJson(KnowledgeAssembler.write(result.skipped()))
                .build();
    }

    /** Ownership + "may still change materials" checks. */
    Ebook requireEditableDraft(UUID ebookId, UUID userId) {
        Ebook ebook = ebookRepository.findByIdAndUserId(ebookId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Ebook not found"));
        if (ebook.getStatus() != EbookStatus.DRAFT) {
            throw new IllegalStateException("Materials can only be added before the book is generated.");
        }
        knowledgeRepository.findByEbookId(ebookId).ifPresent(k -> {
            if (k.getStatus().isRunning()) {
                throw new IllegalStateException("Scrivetta is processing your materials — wait for it to finish.");
            }
        });
        return ebook;
    }

    private void requireRoomForSource(UUID ebookId) {
        if (sourceRepository.countByEbookId(ebookId) >= limits.getMaxSourcesPerBook()) {
            throw new IllegalStateException("A book can hold at most " + limits.getMaxSourcesPerBook()
                    + " sources. Combine files into a ZIP or RAR archive, or remove one first.");
        }
    }

    /** Any change to the materials makes stored knowledge stale (or resets to CREATED when none remain). */
    private void markMaterialsChanged(Ebook ebook) {
        BookKnowledge knowledge = findOrCreate(ebook);
        boolean any = sourceRepository.countByEbookId(ebook.getId()) > 0;
        knowledgeRepository.updateStatus(ebook.getId(),
                any ? KnowledgeStatus.MATERIALS_UPLOADING : KnowledgeStatus.CREATED, LocalDateTime.now());
        log.debug("Knowledge {} for ebook {} marked {}", knowledge.getId(), ebook.getId(),
                any ? KnowledgeStatus.MATERIALS_UPLOADING : KnowledgeStatus.CREATED);
    }

    BookKnowledge findOrCreate(Ebook ebook) {
        return knowledgeRepository.findByEbookId(ebook.getId()).orElseGet(() -> {
            try {
                return knowledgeRepository.saveAndFlush(BookKnowledge.builder().ebook(ebook)
                        .status(KnowledgeStatus.CREATED).schemaVersion(0).build());
            } catch (DataIntegrityViolationException e) {
                // A concurrent request created it first.
                return knowledgeRepository.findByEbookId(ebook.getId()).orElseThrow(() -> e);
            }
        });
    }

    static KnowledgeSourceDTO toDto(KnowledgeSource s) {
        List<SkippedFile> skipped = List.of();
        if (s.getSkippedJson() != null) {
            try {
                skipped = KnowledgeAssembler.MAPPER.readValue(s.getSkippedJson(), new TypeReference<List<SkippedFile>>() {
                });
            } catch (Exception ignored) {
                // display-only
            }
        }
        return new KnowledgeSourceDTO(s.getId(), s.getSourceType(), s.getOriginalFilename(), s.getSizeBytes(),
                s.getStatus(), s.getErrorMessage(), s.getDocumentCount(), s.getSkippedCount(), s.getExtractedChars(),
                skipped.size() > 50 ? skipped.subList(0, 50) : skipped, s.getCreatedAt());
    }

    static String sanitizeFilename(String name) {
        if (name == null || name.isBlank()) return "upload";
        String base = name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        base = base.replaceAll("[\\p{Cntrl}]", "").strip();
        if (base.length() > 200) base = base.substring(base.length() - 200);
        return base.isBlank() ? "upload" : base;
    }

    private static long mb(long bytes) {
        return bytes / (1024 * 1024);
    }
}
