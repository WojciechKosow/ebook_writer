package com.ebookwriter.SaaS.service.knowledge;

import com.ebookwriter.SaaS.config.properties.KnowledgeProperties;
import com.ebookwriter.SaaS.config.properties.OpenAiProperties;
import com.ebookwriter.SaaS.dto.knowledge.BookKnowledgeData;
import com.ebookwriter.SaaS.dto.knowledge.NormalizedDocument;
import com.ebookwriter.SaaS.dto.knowledge.SkippedFile;
import com.ebookwriter.SaaS.entity.BookKnowledge;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.KnowledgeSource;
import com.ebookwriter.SaaS.entity.KnowledgeSourceStatus;
import com.ebookwriter.SaaS.entity.KnowledgeStatus;
import com.ebookwriter.SaaS.prompt.KnowledgePrompts;
import com.ebookwriter.SaaS.repository.BookKnowledgeRepository;
import com.ebookwriter.SaaS.repository.EbookRepository;
import com.ebookwriter.SaaS.repository.KnowledgeSourceRepository;
import com.ebookwriter.SaaS.service.ai.OpenAiTextClient;
import com.ebookwriter.SaaS.service.ai.OpenAiTextException;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * The knowledge pipeline proper:
 * <pre>
 *   stored sources ─▶ documents ─▶ dedupe + batches (KnowledgeChunker)
 *                 ─▶ OpenAI extraction per batch ─▶ resolve sources ─▶ merge
 *                 ─▶ OpenAI consolidation (only if &gt;1 batch) ─▶ BookKnowledge (DB)
 * </pre>
 * Status: PROCESSING while preparing, ANALYZING while OpenAI reads, then
 * KNOWLEDGE_READY (or FAILED with a reason). OpenAI only — Claude is never
 * called here.
 *
 * <p>Resilience: a batch that fails after retries is skipped with a warning
 * and the rest still produces knowledge; only when every batch fails does the
 * run fail. A failed consolidation falls back to the deterministic merge.
 * Usage (calls, tokens, estimated cost) is recorded per run and in lifetime
 * totals.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeProcessingService {

    private static final EnumSet<KnowledgeStatus> CLAIMABLE = EnumSet.of(
            KnowledgeStatus.MATERIALS_UPLOADING, KnowledgeStatus.FAILED,
            KnowledgeStatus.KNOWLEDGE_READY, KnowledgeStatus.READY_FOR_BLUEPRINT);
    private static final EnumSet<KnowledgeStatus> RUNNING = EnumSet.of(
            KnowledgeStatus.PROCESSING, KnowledgeStatus.ANALYZING);

    private final EbookRepository ebookRepository;
    private final KnowledgeSourceRepository sourceRepository;
    private final BookKnowledgeRepository knowledgeRepository;
    private final KnowledgeChunker chunker;
    private final OpenAiTextClient openAi;
    private final OpenAiProperties openAiProperties;
    private final KnowledgeProperties limits;

    /**
     * Atomically claim the book for a run (→ PROCESSING). Returns false when it is
     * already running (or has nothing to process). One winner per run, so a double
     * click never pays for two runs.
     */
    public boolean claim(UUID ebookId) {
        LocalDateTime now = LocalDateTime.now();
        return knowledgeRepository.claimForProcessing(ebookId, CLAIMABLE, RUNNING,
                now.minusMinutes(Math.max(1, limits.getStaleProcessingMinutes())), KnowledgeStatus.PROCESSING, now) == 1;
    }

    /** Run the pipeline for a claimed book. Never throws: failures end in FAILED with a reason. */
    public void process(UUID ebookId) {
        long started = System.currentTimeMillis();
        Usage usage = new Usage();
        try {
            Ebook ebook = ebookRepository.findById(ebookId)
                    .orElseThrow(() -> new IllegalStateException("Ebook not found"));
            List<KnowledgeSource> sources = sourceRepository.findByEbookIdOrderByCreatedAtAsc(ebookId);
            List<KnowledgeSource> usable = sources.stream()
                    .filter(s -> s.getStatus() != KnowledgeSourceStatus.FAILED).toList();

            List<AnalysisDocument> documents = new ArrayList<>();
            int skippedAtExtraction = 0;
            for (KnowledgeSource s : usable) {
                for (NormalizedDocument d : readDocuments(s)) {
                    documents.add(new AnalysisDocument(s.getOriginalFilename(), s.getSourceType(), d));
                }
                skippedAtExtraction += readSkipped(s).size();
            }
            if (documents.isEmpty()) {
                fail(ebookId, "Scrivetta found nothing readable in your materials. Add notes or another file.", usage, null);
                return;
            }

            KnowledgeChunker.Plan plan = chunker.plan(documents);
            List<String> warnings = new ArrayList<>(plan.warnings());
            sources.stream().filter(s -> s.getStatus() == KnowledgeSourceStatus.FAILED)
                    .forEach(s -> warnings.add(s.getOriginalFilename() + " could not be read and was ignored."));
            KnowledgeAssembler.SourceResolver resolver = new KnowledgeAssembler.SourceResolver(plan.refs().keySet());

            knowledgeRepository.updateStatus(ebookId, KnowledgeStatus.ANALYZING, LocalDateTime.now());
            log.info("Knowledge run for ebook {}: {} documents → {} batch(es), {} chars, {} duplicate(s), {} not analysed, model {}",
                    ebookId, documents.size(), plan.chunks().size(), plan.analyzedChars(), plan.duplicates(),
                    plan.notAnalyzed(), openAi.model());

            // Map: one extraction call per batch.
            List<BookKnowledgeData> partials = new ArrayList<>();
            String lastError = null;
            int batchCount = plan.chunks().size();
            for (KnowledgeChunker.Chunk chunk : plan.chunks()) {
                String notesContext = chunk.index() > 1 ? plan.notesExcerpt() : null;
                try {
                    OpenAiTextClient.JsonCompletion completion = openAi.completeJson(
                            KnowledgePrompts.extractionSystem(),
                            KnowledgePrompts.extractionUser(ebook, chunk.index(), batchCount, notesContext, chunk.text()));
                    usage.add(completion);
                    partials.add(KnowledgeAssembler.resolveSources(KnowledgeAssembler.parse(completion.json()), resolver));
                } catch (OpenAiTextException | IllegalArgumentException e) {
                    lastError = e.getMessage();
                    usage.failedCalls++;
                    warnings.add("Batch " + chunk.index() + " of " + batchCount + " could not be analysed.");
                    log.warn("Knowledge batch {}/{} for ebook {} failed: {}", chunk.index(), batchCount, ebookId, e.getMessage());
                    if (e instanceof OpenAiTextException ote && !ote.isRetryable() && isConfigError(ote)) {
                        break; // bad key / model: every other batch would fail the same way
                    }
                }
            }
            if (partials.isEmpty()) {
                fail(ebookId, "Scrivetta could not analyse your materials right now. Please try again."
                        + (lastError != null ? " (" + abbreviate(lastError) + ")" : ""), usage, warnings);
                return;
            }

            // Reduce: deterministic merge, then (only for several batches) one consolidation call.
            BookKnowledgeData merged = KnowledgeAssembler.merge(partials);
            BookKnowledgeData result = merged;
            if (partials.size() > 1) {
                result = consolidate(ebook, merged, resolver, usage, warnings);
            }

            BookKnowledgeData.Coverage coverage = new BookKnowledgeData.Coverage(
                    usable.size(), documents.size(),
                    (int) plan.sources().stream().filter(BookKnowledgeData.SourceRef::analyzed).count(),
                    plan.notAnalyzed(), plan.duplicates(), skippedAtExtraction, batchCount, plan.analyzedChars());
            result = result.withBookkeeping(bookInfo(ebook), plan.sources(), coverage);

            BookKnowledge knowledge = knowledgeRepository.findByEbookId(ebookId).orElseThrow();
            knowledge.setKnowledgeJson(KnowledgeAssembler.write(result));
            knowledge.setSchemaVersion(BookKnowledgeData.CURRENT_SCHEMA_VERSION);
            knowledge.setStatus(KnowledgeStatus.KNOWLEDGE_READY);
            knowledge.setErrorMessage(null);
            knowledge.setSourcesAnalyzed(usable.size());
            knowledge.setDocumentsAnalyzed(coverage.documentsAnalyzed());
            knowledge.setDocumentsNotAnalyzed(plan.notAnalyzed());
            knowledge.setDuplicatesSkipped(plan.duplicates());
            knowledge.setChunks(batchCount);
            knowledge.setAnalyzedChars(plan.analyzedChars());
            knowledge.setWarningsJson(KnowledgeAssembler.write(warnings));
            knowledge.setCompletedAt(LocalDateTime.now());
            applyUsage(knowledge, usage);
            knowledgeRepository.save(knowledge);
            log.info("Knowledge ready for ebook {} in {} ms: {} topics, {} processes, {} gaps; {} calls, {} in / {} out tokens (~${})",
                    ebookId, System.currentTimeMillis() - started, result.topics().size(), result.processes().size(),
                    result.knowledgeGaps().size(), usage.calls, usage.inputTokens, usage.outputTokens,
                    String.format("%.4f", usage.costUsd(openAiProperties)));
        } catch (RuntimeException e) {
            log.error("Knowledge processing failed for ebook {}", ebookId, e);
            fail(ebookId, "Processing failed unexpectedly. Please try again.", usage, null);
        }
    }

    private BookKnowledgeData consolidate(Ebook ebook, BookKnowledgeData merged, KnowledgeAssembler.SourceResolver resolver,
                                          Usage usage, List<String> warnings) {
        String json = KnowledgeAssembler.write(merged);
        int maxInput = limits.getMaxCharsPerChunk() * 2;
        if (json.length() > maxInput) {
            json = KnowledgeAssembler.write(KnowledgeAssembler.capLists(merged, 30));
        }
        if (json.length() > maxInput) {
            warnings.add("The knowledge was merged without a final consolidation pass (too large).");
            return merged;
        }
        try {
            OpenAiTextClient.JsonCompletion completion = openAi.completeJson(
                    KnowledgePrompts.consolidationSystem(), KnowledgePrompts.consolidationUser(ebook, json));
            usage.add(completion);
            BookKnowledgeData consolidated = KnowledgeAssembler.resolveSources(
                    KnowledgeAssembler.parse(completion.json()), resolver);
            if (consolidated.topics().isEmpty() && !merged.topics().isEmpty()) {
                warnings.add("The consolidation pass returned no topics; the merged batch results were kept.");
                return merged;
            }
            return consolidated;
        } catch (OpenAiTextException | IllegalArgumentException e) {
            usage.failedCalls++;
            log.warn("Knowledge consolidation for ebook {} failed, keeping merged batches: {}", ebook.getId(), e.getMessage());
            warnings.add("The final consolidation pass failed; the merged batch results were kept.");
            return merged;
        }
    }

    private void fail(UUID ebookId, String message, Usage usage, List<String> warnings) {
        knowledgeRepository.findByEbookId(ebookId).ifPresent(k -> {
            k.setStatus(KnowledgeStatus.FAILED);
            k.setErrorMessage(message);
            k.setCompletedAt(LocalDateTime.now());
            if (warnings != null) k.setWarningsJson(KnowledgeAssembler.write(warnings));
            applyUsage(k, usage);
            knowledgeRepository.save(k);
        });
    }

    private void applyUsage(BookKnowledge k, Usage usage) {
        double cost = usage.costUsd(openAiProperties);
        k.setModel(usage.model != null ? usage.model : openAi.model());
        k.setOpenAiCalls(usage.calls + usage.failedCalls);
        k.setInputTokens(usage.inputTokens);
        k.setOutputTokens(usage.outputTokens);
        k.setEstimatedCostUsd(cost);
        k.setTotalInputTokens(k.getTotalInputTokens() + usage.inputTokens);
        k.setTotalOutputTokens(k.getTotalOutputTokens() + usage.outputTokens);
        k.setTotalEstimatedCostUsd(k.getTotalEstimatedCostUsd() + cost);
    }

    static BookKnowledgeData.BookInfo bookInfo(Ebook e) {
        return new BookKnowledgeData.BookInfo(e.getTopic(), e.getLanguage(), e.getTargetAudience(), e.getBookGoal(),
                e.getStyle(), e.getApproxPageCount() > 0 ? e.getApproxPageCount() : null);
    }

    private static List<NormalizedDocument> readDocuments(KnowledgeSource s) {
        if (s.getDocumentsJson() == null) return List.of();
        try {
            return KnowledgeAssembler.MAPPER.readValue(s.getDocumentsJson(), new TypeReference<List<NormalizedDocument>>() {
            });
        } catch (Exception e) {
            log.warn("Stored documents of source {} are unreadable: {}", s.getId(), e.getMessage());
            return List.of();
        }
    }

    private static List<SkippedFile> readSkipped(KnowledgeSource s) {
        if (s.getSkippedJson() == null) return List.of();
        try {
            return KnowledgeAssembler.MAPPER.readValue(s.getSkippedJson(), new TypeReference<List<SkippedFile>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private static boolean isConfigError(OpenAiTextException e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        return m.contains("HTTP 401") || m.contains("HTTP 403") || m.contains("HTTP 404") || m.contains("not configured");
    }

    private static String abbreviate(String s) {
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }

    /** Token / call accounting for one run. */
    static final class Usage {
        int calls;
        int failedCalls;
        long inputTokens;
        long outputTokens;
        String model;

        void add(OpenAiTextClient.JsonCompletion c) {
            calls++;
            inputTokens += c.inputTokens();
            outputTokens += c.outputTokens();
            if (c.model() != null) model = c.model();
        }

        double costUsd(OpenAiProperties p) {
            return inputTokens / 1_000_000.0 * p.getKnowledgeInputUsdPerMillion()
                    + outputTokens / 1_000_000.0 * p.getKnowledgeOutputUsdPerMillion();
        }
    }
}
