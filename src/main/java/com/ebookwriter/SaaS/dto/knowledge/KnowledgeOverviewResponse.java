package com.ebookwriter.SaaS.dto.knowledge;

import com.ebookwriter.SaaS.entity.KnowledgeStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Everything the "Tell Scrivetta what you know" step needs: the status, the
 * uploaded sources, and — once processed — the headline numbers ("Topics found:
 * 8 · Processes found: 5 · Sources analysed: 14 · Knowledge gaps: 3"), the usage
 * of the last run and the upload limits.
 */
public record KnowledgeOverviewResponse(
        UUID ebookId,
        KnowledgeStatus status,
        String errorMessage,
        boolean hasKnowledge,
        boolean readyForBlueprint,
        boolean processingAvailable,
        List<KnowledgeSourceDTO> sources,
        /** The saved pasted notes (so the editor can show them again), or null. */
        String notes,
        Summary summary,
        Usage usage,
        Limits limits,
        List<String> warnings,
        LocalDateTime startedAt,
        LocalDateTime completedAt) {

    public record Summary(String projectName, String projectType, String overallSummary,
                          List<String> technologies, int topicsFound, int processesFound, int examplesFound,
                          int userInsightsFound, int termsFound, int importantDetailsFound,
                          int sourcesAnalyzed, int documentsAnalyzed, int documentsNotAnalyzed,
                          int duplicatesSkipped, int knowledgeGaps, List<String> topTopics,
                          List<String> intendedSequence, List<String> gapQuestions) {
    }

    public record Usage(String model, int openAiCalls, long inputTokens, long outputTokens,
                        double estimatedCostUsd, long analyzedChars, int chunks, int processingRuns,
                        int maxProcessingRuns) {
    }

    public record Limits(long maxUploadBytes, int maxSources, int maxNotesChars, List<String> acceptedFormats) {
    }
}
