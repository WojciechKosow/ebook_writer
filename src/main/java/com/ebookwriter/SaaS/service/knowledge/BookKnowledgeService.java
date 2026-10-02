package com.ebookwriter.SaaS.service.knowledge;

import com.ebookwriter.SaaS.config.properties.KnowledgeProperties;
import com.ebookwriter.SaaS.dto.knowledge.BookKnowledgeData;
import com.ebookwriter.SaaS.dto.knowledge.KnowledgeOverviewResponse;
import com.ebookwriter.SaaS.entity.BookKnowledge;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.KnowledgeStatus;
import com.ebookwriter.SaaS.repository.BookKnowledgeRepository;
import com.ebookwriter.SaaS.repository.EbookRepository;
import com.ebookwriter.SaaS.repository.KnowledgeSourceRepository;
import com.ebookwriter.SaaS.service.ai.OpenAiTextClient;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The public face of a book's knowledge:
 * <ul>
 *   <li>{@link #getBookKnowledge(UUID)} / {@link #isKnowledgeReady(UUID)} — the
 *       contract for the next stage (Book Blueprint): "does this book have
 *       BookKnowledge, and what is it?";</li>
 *   <li>the author-facing overview, starting a processing run, and "Continue"
 *       (KNOWLEDGE_READY → READY_FOR_BLUEPRINT).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class BookKnowledgeService {

    private final EbookRepository ebookRepository;
    private final BookKnowledgeRepository knowledgeRepository;
    private final KnowledgeSourceRepository sourceRepository;
    private final KnowledgeIngestionService ingestionService;
    private final KnowledgeProcessingService processingService;
    private final KnowledgeProcessingWorker worker;
    private final OpenAiTextClient openAi;
    private final KnowledgeProperties limits;

    // ---- Contract for the next stage -------------------------------------------------

    /**
     * The stored, structured knowledge of a book — present only when processing
     * finished successfully and the materials have not changed since
     * (KNOWLEDGE_READY or READY_FOR_BLUEPRINT). Internal (no ownership check):
     * for pipeline code that already holds a verified ebook id.
     */
    public Optional<BookKnowledgeData> getBookKnowledge(UUID ebookId) {
        return knowledgeRepository.findByEbookId(ebookId)
                .filter(k -> k.getStatus().hasKnowledge() && k.getKnowledgeJson() != null)
                .map(k -> KnowledgeAssembler.read(k.getKnowledgeJson()));
    }

    /** Ownership-checked variant for the API. */
    public Optional<BookKnowledgeData> getBookKnowledge(UUID ebookId, UUID userId) {
        requireOwned(ebookId, userId);
        return getBookKnowledge(ebookId);
    }

    /** True when the book has up-to-date BookKnowledge. */
    public boolean isKnowledgeReady(UUID ebookId) {
        return knowledgeRepository.findByEbookId(ebookId).map(k -> k.getStatus().hasKnowledge()).orElse(false);
    }

    public KnowledgeStatus getStatus(UUID ebookId) {
        return knowledgeRepository.findByEbookId(ebookId).map(BookKnowledge::getStatus).orElse(KnowledgeStatus.CREATED);
    }

    // ---- Author-facing -------------------------------------------------------------

    public KnowledgeOverviewResponse overview(UUID ebookId, UUID userId) {
        requireOwned(ebookId, userId);
        Optional<BookKnowledge> knowledge = knowledgeRepository.findByEbookId(ebookId);
        KnowledgeStatus status = knowledge.map(BookKnowledge::getStatus).orElse(KnowledgeStatus.CREATED);

        KnowledgeOverviewResponse.Summary summary = null;
        if (status.hasKnowledge() && knowledge.get().getKnowledgeJson() != null) {
            summary = summarize(knowledge.get(), KnowledgeAssembler.read(knowledge.get().getKnowledgeJson()));
        }
        KnowledgeOverviewResponse.Usage usage = knowledge.map(k -> new KnowledgeOverviewResponse.Usage(
                k.getModel(), k.getOpenAiCalls(), k.getInputTokens(), k.getOutputTokens(), k.getEstimatedCostUsd(),
                k.getAnalyzedChars(), k.getChunks(), k.getProcessingRuns(), limits.getMaxProcessingRuns())).orElse(null);
        List<String> warnings = knowledge.map(k -> readWarnings(k.getWarningsJson())).orElse(List.of());

        return new KnowledgeOverviewResponse(ebookId, status, knowledge.map(BookKnowledge::getErrorMessage).orElse(null),
                status.hasKnowledge(), status == KnowledgeStatus.READY_FOR_BLUEPRINT, openAi.isConfigured(),
                ingestionService.listSources(ebookId), ingestionService.notesText(ebookId), summary, usage,
                new KnowledgeOverviewResponse.Limits(limits.getMaxUploadBytes(), limits.getMaxSourcesPerBook(),
                        limits.getMaxNotesChars(), KnowledgeIngestionService.ACCEPTED_FORMATS),
                warnings, knowledge.map(BookKnowledge::getStartedAt).orElse(null),
                knowledge.map(BookKnowledge::getCompletedAt).orElse(null));
    }

    /**
     * Start processing the book's materials in the background. 409 when already
     * running or the run limit is reached; 503 when OpenAI is not configured.
     */
    public KnowledgeOverviewResponse startProcessing(UUID ebookId, UUID userId) {
        Ebook ebook = ingestionService.requireEditableDraft(ebookId, userId);
        if (!openAi.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Knowledge processing is not available right now (OpenAI is not configured).");
        }
        if (sourceRepository.countByEbookId(ebookId) == 0) {
            throw new IllegalStateException("Add your notes or upload a file first.");
        }
        BookKnowledge knowledge = ingestionService.findOrCreate(ebook);
        if (knowledge.getProcessingRuns() >= limits.getMaxProcessingRuns()) {
            throw new IllegalStateException("This book's materials have been processed the maximum number of times ("
                    + limits.getMaxProcessingRuns() + ").");
        }
        if (knowledge.getStatus() == KnowledgeStatus.CREATED) {
            knowledgeRepository.updateStatus(ebookId, KnowledgeStatus.MATERIALS_UPLOADING, LocalDateTime.now());
        }
        if (!processingService.claim(ebookId)) {
            throw new IllegalStateException("Scrivetta is already processing your materials.");
        }
        worker.run(ebookId);
        return overview(ebookId, userId);
    }

    /** "Continue": the author accepts the knowledge; the book is now ready for the blueprint stage. */
    public KnowledgeOverviewResponse continueToBlueprint(UUID ebookId, UUID userId) {
        requireOwned(ebookId, userId);
        KnowledgeStatus status = getStatus(ebookId);
        if (status != KnowledgeStatus.READY_FOR_BLUEPRINT
                && knowledgeRepository.markReadyForBlueprint(ebookId, KnowledgeStatus.KNOWLEDGE_READY,
                KnowledgeStatus.READY_FOR_BLUEPRINT, LocalDateTime.now()) == 0) {
            throw new IllegalStateException("Scrivetta hasn't finished learning from your materials yet.");
        }
        return overview(ebookId, userId);
    }

    // ---- helpers -------------------------------------------------------------------

    private Ebook requireOwned(UUID ebookId, UUID userId) {
        return ebookRepository.findByIdAndUserId(ebookId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Ebook not found"));
    }

    static KnowledgeOverviewResponse.Summary summarize(BookKnowledge k, BookKnowledgeData d) {
        var project = d.project();
        return new KnowledgeOverviewResponse.Summary(
                project != null ? project.name() : null, project != null ? project.type() : null, d.overallSummary(),
                project != null ? project.technologies() : List.of(),
                d.topics().size(), d.processes().size(), d.examples().size(), d.userInsights().size(),
                d.terminology().size(), d.importantDetails().size(), k.getSourcesAnalyzed(), k.getDocumentsAnalyzed(),
                k.getDocumentsNotAnalyzed(), k.getDuplicatesSkipped(), d.knowledgeGaps().size(),
                d.topics().stream().limit(8).map(BookKnowledgeData.Topic::name).toList(),
                d.intendedSequence().stream().limit(12).map(BookKnowledgeData.SequenceStep::step).toList(),
                d.knowledgeGaps().stream().limit(5).map(BookKnowledgeData.KnowledgeGap::question).toList());
    }

    private static List<String> readWarnings(String json) {
        if (json == null) return List.of();
        try {
            return KnowledgeAssembler.MAPPER.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }
}
