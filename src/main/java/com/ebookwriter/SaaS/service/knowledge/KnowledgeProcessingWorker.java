package com.ebookwriter.SaaS.service.knowledge;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Runs a claimed knowledge-processing run in the background (its own executor). */
@Component
@RequiredArgsConstructor
public class KnowledgeProcessingWorker {

    private final KnowledgeProcessingService processingService;

    @Async("knowledgeExecutor")
    public void run(UUID ebookId) {
        processingService.process(ebookId);
    }
}
