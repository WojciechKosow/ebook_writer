package com.ebookwriter.SaaS.controller;

import com.ebookwriter.SaaS.dto.blueprint.BlueprintOverviewResponse;
import com.ebookwriter.SaaS.entity.User;
import com.ebookwriter.SaaS.repository.UserRepository;
import com.ebookwriter.SaaS.request.BlueprintAnswerRequest;
import com.ebookwriter.SaaS.request.BlueprintUpdateRequest;
import com.ebookwriter.SaaS.service.blueprint.BookBlueprintService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * The Book Blueprint of a draft book: build it from the book's knowledge, let
 * the author edit it and answer Scrivetta's questions, and approve it. All
 * endpoints are owner-scoped.
 *
 * <pre>
 *   GET  /api/ebooks/{id}/blueprint                       status, blueprint, questions, summary
 *   POST /api/ebooks/{id}/blueprint/build[?force=true]    build / rebuild (202; poll GET)
 *   PUT  /api/ebooks/{id}/blueprint                       edit title, fields, chapters
 *   PUT  /api/ebooks/{id}/blueprint/questions/{qid}       {"answer": "..."} or {"skip": true}
 *   POST /api/ebooks/{id}/blueprint/approve               BLUEPRINT_REVIEW → BLUEPRINT_READY
 * </pre>
 */
@RestController
@RequestMapping("/api/ebooks/{ebookId}/blueprint")
@RequiredArgsConstructor
public class BlueprintController {

    private final BookBlueprintService blueprintService;
    private final UserRepository userRepository;

    @GetMapping
    public ResponseEntity<BlueprintOverviewResponse> overview(@PathVariable UUID ebookId, Authentication authentication) {
        return ResponseEntity.ok(blueprintService.overview(ebookId, currentUser(authentication).getId()));
    }

    @PostMapping("/build")
    public ResponseEntity<BlueprintOverviewResponse> build(@PathVariable UUID ebookId,
                                                           @RequestParam(value = "force", defaultValue = "false") boolean force,
                                                           Authentication authentication) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(blueprintService.startBuild(ebookId, currentUser(authentication).getId(), force));
    }

    @PutMapping
    public ResponseEntity<BlueprintOverviewResponse> update(@PathVariable UUID ebookId,
                                                            @RequestBody BlueprintUpdateRequest request,
                                                            Authentication authentication) {
        return ResponseEntity.ok(blueprintService.update(ebookId, currentUser(authentication).getId(), request));
    }

    @PutMapping("/questions/{questionId}")
    public ResponseEntity<BlueprintOverviewResponse> answer(@PathVariable UUID ebookId, @PathVariable UUID questionId,
                                                            @RequestBody BlueprintAnswerRequest request,
                                                            Authentication authentication) {
        return ResponseEntity.ok(blueprintService.answer(ebookId, currentUser(authentication).getId(), questionId,
                request.getAnswer(), request.isSkip()));
    }

    @PostMapping("/approve")
    public ResponseEntity<BlueprintOverviewResponse> approve(@PathVariable UUID ebookId, Authentication authentication) {
        return ResponseEntity.ok(blueprintService.approve(ebookId, currentUser(authentication).getId()));
    }

    private User currentUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IllegalStateException("Unauthorized");
        }
        return userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new IllegalStateException("User not found"));
    }
}
