package com.ebookwriter.SaaS.controller;

import com.ebookwriter.SaaS.dto.knowledge.BookKnowledgeData;
import com.ebookwriter.SaaS.dto.knowledge.KnowledgeOverviewResponse;
import com.ebookwriter.SaaS.dto.knowledge.KnowledgeSourceDTO;
import com.ebookwriter.SaaS.entity.User;
import com.ebookwriter.SaaS.repository.UserRepository;
import com.ebookwriter.SaaS.request.KnowledgeNotesRequest;
import com.ebookwriter.SaaS.service.knowledge.BookKnowledgeService;
import com.ebookwriter.SaaS.service.knowledge.KnowledgeIngestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * "Tell Scrivetta what you know" — the author's materials for a draft book and
 * the structured knowledge learned from them. All endpoints are scoped to the
 * authenticated owner.
 *
 * <pre>
 *   GET    /api/ebooks/{id}/knowledge                 status, sources, summary, usage, limits
 *   GET    /api/ebooks/{id}/knowledge/full            the structured BookKnowledge (404 until ready)
 *   POST   /api/ebooks/{id}/knowledge/sources         upload ZIP / RAR / PDF / DOCX / TXT / MD (multipart "file")
 *   PUT    /api/ebooks/{id}/knowledge/notes           set pasted notes ({"text": "..."}; blank removes)
 *   DELETE /api/ebooks/{id}/knowledge/sources/{sid}   remove a source
 *   POST   /api/ebooks/{id}/knowledge/process         start processing (202; poll GET)
 *   POST   /api/ebooks/{id}/knowledge/continue        accept → READY_FOR_BLUEPRINT
 * </pre>
 */
@RestController
@RequestMapping("/api/ebooks/{ebookId}/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeIngestionService ingestionService;
    private final BookKnowledgeService knowledgeService;
    private final UserRepository userRepository;

    @GetMapping
    public ResponseEntity<KnowledgeOverviewResponse> overview(@PathVariable UUID ebookId, Authentication authentication) {
        return ResponseEntity.ok(knowledgeService.overview(ebookId, currentUser(authentication).getId()));
    }

    @GetMapping("/full")
    public ResponseEntity<BookKnowledgeData> full(@PathVariable UUID ebookId, Authentication authentication) {
        return knowledgeService.getBookKnowledge(ebookId, currentUser(authentication).getId())
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new IllegalArgumentException("No knowledge has been processed for this book yet"));
    }

    @PostMapping(value = "/sources", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<KnowledgeSourceDTO> upload(@PathVariable UUID ebookId,
                                                     @RequestParam("file") MultipartFile file,
                                                     Authentication authentication) {
        KnowledgeSourceDTO dto = ingestionService.addFile(ebookId, currentUser(authentication).getId(), file);
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    @PutMapping("/notes")
    public ResponseEntity<KnowledgeSourceDTO> notes(@PathVariable UUID ebookId,
                                                    @RequestBody KnowledgeNotesRequest request,
                                                    Authentication authentication) {
        KnowledgeSourceDTO dto = ingestionService.setNotes(ebookId, currentUser(authentication).getId(),
                request == null ? null : request.getText());
        return dto == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(dto);
    }

    @DeleteMapping("/sources/{sourceId}")
    public ResponseEntity<Void> delete(@PathVariable UUID ebookId, @PathVariable UUID sourceId,
                                       Authentication authentication) {
        ingestionService.deleteSource(ebookId, currentUser(authentication).getId(), sourceId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/process")
    public ResponseEntity<KnowledgeOverviewResponse> process(@PathVariable UUID ebookId, Authentication authentication) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(knowledgeService.startProcessing(ebookId, currentUser(authentication).getId()));
    }

    @PostMapping("/continue")
    public ResponseEntity<KnowledgeOverviewResponse> continueToBlueprint(@PathVariable UUID ebookId,
                                                                         Authentication authentication) {
        return ResponseEntity.ok(knowledgeService.continueToBlueprint(ebookId, currentUser(authentication).getId()));
    }

    private User currentUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IllegalStateException("Unauthorized");
        }
        return userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new IllegalStateException("User not found"));
    }
}
