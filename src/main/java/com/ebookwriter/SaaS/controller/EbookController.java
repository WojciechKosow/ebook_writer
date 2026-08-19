package com.ebookwriter.SaaS.controller;

import com.ebookwriter.SaaS.dto.EbookStatusResponse;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.User;
import com.ebookwriter.SaaS.repository.UserRepository;
import com.ebookwriter.SaaS.request.EbookRequest;
import com.ebookwriter.SaaS.service.ebook.EbookService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/ebooks")
@RequiredArgsConstructor
public class EbookController {

    private final EbookService ebookService;
    private final UserRepository userRepository;

    /** Submit a new ebook request; generation runs in the background. */
    @PostMapping
    public ResponseEntity<EbookStatusResponse> create(@Valid @RequestBody EbookRequest request,
                                                      Authentication authentication) {
        User user = currentUser(authentication);
        Ebook ebook = ebookService.createAndStart(user, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ebookService.getStatus(ebook.getId(), user.getId()));
    }

    /** Poll generation status / progress. */
    @GetMapping("/{id}")
    public ResponseEntity<EbookStatusResponse> status(@PathVariable UUID id,
                                                      Authentication authentication) {
        User user = currentUser(authentication);
        return ResponseEntity.ok(ebookService.getStatus(id, user.getId()));
    }

    /** List the current user's ebooks. */
    @GetMapping
    public ResponseEntity<List<EbookStatusResponse>> list(Authentication authentication) {
        User user = currentUser(authentication);
        return ResponseEntity.ok(ebookService.list(user.getId()));
    }

    /** Download the finished PDF. */
    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable UUID id,
                                           Authentication authentication) {
        User user = currentUser(authentication);
        EbookService.PdfDownload pdf = ebookService.getPdf(id, user.getId());

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + pdf.filename() + "\"")
                .body(pdf.bytes());
    }

    private User currentUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IllegalStateException("Unauthorized");
        }
        return userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new IllegalStateException("User not found"));
    }
}
