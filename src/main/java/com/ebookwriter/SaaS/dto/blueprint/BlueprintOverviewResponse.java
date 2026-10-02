package com.ebookwriter.SaaS.dto.blueprint;

import com.ebookwriter.SaaS.entity.BlueprintStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Everything the blueprint step needs: status, the blueprint itself
 * ("Here's what Scrivetta understands"), the questions with answers, headline
 * counts, usage of the last build and warnings.
 *
 * @param knowledgeReady    the book has up-to-date BookKnowledge (a build can start)
 * @param knowledgeOutdated the blueprint was built from knowledge that has changed since
 * @param buildAvailable    OpenAI is configured on this server
 */
public record BlueprintOverviewResponse(
        UUID ebookId,
        BlueprintStatus status,
        String errorMessage,
        boolean knowledgeReady,
        boolean knowledgeOutdated,
        boolean buildAvailable,
        boolean userEdited,
        BlueprintData blueprint,
        List<BlueprintQuestionDTO> questions,
        Summary summary,
        Usage usage,
        List<String> warnings,
        LocalDateTime generatedAt,
        LocalDateTime readyAt) {

    public record Summary(int chapters, int groundedChapters, int knowledgeGaps, int openGaps,
                          int questions, int answered, int skipped, int open) {
    }

    public record Usage(String model, int openAiCalls, long inputTokens, long outputTokens, double estimatedCostUsd,
                        int generation, int maxGenerations) {
    }
}
