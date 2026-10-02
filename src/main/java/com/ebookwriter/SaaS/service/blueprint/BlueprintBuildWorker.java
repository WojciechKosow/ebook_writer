package com.ebookwriter.SaaS.service.blueprint;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Runs a claimed blueprint build in the background (shares the knowledge executor). */
@Component
@RequiredArgsConstructor
public class BlueprintBuildWorker {

    private final BookBlueprintService blueprintService;

    @Async("knowledgeExecutor")
    public void run(UUID ebookId) {
        blueprintService.build(ebookId);
    }
}
