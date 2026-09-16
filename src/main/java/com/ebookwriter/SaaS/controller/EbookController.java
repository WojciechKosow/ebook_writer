package com.ebookwriter.SaaS.controller;

import com.ebookwriter.SaaS.dto.EbookContentResponse;
import com.ebookwriter.SaaS.dto.EbookStatusResponse;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.User;
import com.ebookwriter.SaaS.repository.UserRepository;
import com.ebookwriter.SaaS.request.EbookContentUpdateRequest;
import com.ebookwriter.SaaS.request.EbookRequest;
import com.ebookwriter.SaaS.service.ebook.EbookPreviewService;
import com.ebookwriter.SaaS.service.ebook.EbookService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
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
    private final EbookPreviewService previewService;
    private final UserRepository userRepository;

    /**
     * Create a new ebook as a draft. No credits are held and generation does not
     * start yet — the user can now upload assets to it (see the images API) and
     * then call {@code POST /api/ebooks/{id}/start}.
     */
    @PostMapping
    public ResponseEntity<EbookStatusResponse> create(@Valid @RequestBody EbookRequest request,
                                                      Authentication authentication) {
        User user = currentUser(authentication);
        Ebook ebook = ebookService.createDraft(user, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ebookService.getStatus(ebook.getId(), user.getId()));
    }

    /**
     * Start generating a draft: reserve the credit hold and run the pipeline in
     * the background. Returns {@code 409} if the ebook has already been started.
     */
    @PostMapping("/{id}/start")
    public ResponseEntity<EbookStatusResponse> start(@PathVariable UUID id,
                                                     Authentication authentication) {
        User user = currentUser(authentication);
        ebookService.start(id, user.getId());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ebookService.getStatus(id, user.getId()));
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

    /** Load the full editable manuscript (all chapters + Markdown bodies). */
    @GetMapping("/{id}/content")
    public ResponseEntity<EbookContentResponse> content(@PathVariable UUID id,
                                                        Authentication authentication) {
        User user = currentUser(authentication);
        return ResponseEntity.ok(ebookService.getContent(id, user.getId()));
    }

    /** Save edited chapters and re-render the PDF so the download stays in sync. */
    @PutMapping("/{id}/content")
    public ResponseEntity<EbookContentResponse> updateContent(
            @PathVariable UUID id,
            @Valid @RequestBody EbookContentUpdateRequest request,
            Authentication authentication) {
        User user = currentUser(authentication);
        return ResponseEntity.ok(ebookService.updateContent(id, user.getId(), request));
    }

    /**
     * Render a browser-ready HTML preview of the book — the same layout the PDF
     * uses, with images inlined so it is self-contained. The editor renders this
     * (in a sandboxed iframe, paginated client-side) to show a faithful "what the
     * download looks like" view instead of a Word-style approximation.
     */
    @GetMapping(value = "/{id}/preview", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> preview(@PathVariable UUID id,
                                          Authentication authentication) {
        User user = currentUser(authentication);
        String html = previewService.renderPreview(id, user.getId());
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                // Reflects the current manuscript/assets; never cache a stale preview.
                .cacheControl(CacheControl.noStore())
                .body(html);
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
