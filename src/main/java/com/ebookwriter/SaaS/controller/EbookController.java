package com.ebookwriter.SaaS.controller;

import com.ebookwriter.SaaS.dto.EbookContentResponse;
import com.ebookwriter.SaaS.dto.EbookStatusResponse;
import com.ebookwriter.SaaS.dto.GenerationBudgetResponse;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.User;
import com.ebookwriter.SaaS.repository.UserRepository;
import com.ebookwriter.SaaS.request.EbookContentUpdateRequest;
import com.ebookwriter.SaaS.request.EbookRequest;
import com.ebookwriter.SaaS.service.ebook.EbookPreviewService;
import com.ebookwriter.SaaS.service.ebook.PdfDownloadLinkService;
import com.ebookwriter.SaaS.service.ebook.EbookService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/ebooks")
@RequiredArgsConstructor
public class EbookController {

    private final EbookService ebookService;
    private final EbookPreviewService previewService;
    private final UserRepository userRepository;
    private final PdfDownloadLinkService downloadLinkService;

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

    /**
     * Describe the generation budget for the creation form: minimum credits to
     * start, the orientational page range, the current balance and whether the
     * user can generate now. Lets the UI show "Estimated usage ~20–30 credits",
     * the balance, and an enough/not-enough message — a budget, not a page order.
     */
    @GetMapping("/generation-budget")
    public ResponseEntity<GenerationBudgetResponse> generationBudget(Authentication authentication) {
        User user = currentUser(authentication);
        return ResponseEntity.ok(ebookService.getGenerationBudget(user.getId()));
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

    /**
     * Render a <b>live</b> preview from the editor's current (unsaved) content.
     * Same output as {@link #preview}, but built from the posted chapters rather
     * than the stored manuscript — nothing is persisted — so the editor can show
     * edits as they happen. Images are resolved from the book's stored assets.
     */
    @PostMapping(value = "/{id}/preview",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> previewDraft(@PathVariable UUID id,
                                               @Valid @RequestBody EbookContentUpdateRequest request,
                                               Authentication authentication) {
        User user = currentUser(authentication);
        String html = previewService.renderPreviewFromContent(id, user.getId(), request);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .cacheControl(CacheControl.noStore())
                .body(html);
    }

    /** Download the finished PDF. */
    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable UUID id,
                                           Authentication authentication) {
        User user = currentUser(authentication);
        long started = System.currentTimeMillis();
        EbookService.PdfDownload pdf = ebookService.getPdf(id, user.getId());
        // Logged so a download that fails in the browser can be told apart from one
        // that never reached (or never left) the server.
        log.info("Serving PDF for ebook {} ({} KB, loaded in {} ms)",
                id, pdf.bytes().length / 1024, System.currentTimeMillis() - started);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(pdf.bytes().length)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + pdf.filename() + "\"")
                .body(pdf.bytes());
    }

    /**
     * Issue a short-lived signed link to the finished PDF. The frontend navigates
     * to it so the browser downloads the file natively (see
     * {@link PdfDownloadLinkService} for why this beats a fetch → blob download).
     * Checks ownership and completion up front, so a link is only issued for a
     * downloadable book.
     */
    @PostMapping("/{id}/download-link")
    public ResponseEntity<Map<String, String>> downloadLink(@PathVariable UUID id,
                                                            Authentication authentication) {
        User user = currentUser(authentication);
        ebookService.requireDownloadable(id, user.getId());
        String token = downloadLinkService.issue(id, user.getId());
        return ResponseEntity.ok(Map.of("url", "/api/ebooks/" + id + "/file?token=" + token));
    }

    /**
     * Serve the PDF for a signed link (public route — the token is the
     * credential). Sent as an attachment, uncached.
     */
    @GetMapping("/{id}/file")
    public ResponseEntity<byte[]> file(@PathVariable UUID id, @RequestParam("token") String token) {
        UUID userId = downloadLinkService.verify(token, id)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.FORBIDDEN, "This download link is invalid or has expired."));
        long started = System.currentTimeMillis();
        EbookService.PdfDownload pdf = ebookService.getPdf(id, userId);
        log.info("Serving PDF via signed link for ebook {} ({} KB, loaded in {} ms)",
                id, pdf.bytes().length / 1024, System.currentTimeMillis() - started);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(pdf.bytes().length)
                .cacheControl(CacheControl.noStore())
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
