package com.ebookwriter.SaaS.controller;

import com.ebookwriter.SaaS.dto.EbookImageDTO;
import com.ebookwriter.SaaS.entity.EbookImageRole;
import com.ebookwriter.SaaS.entity.User;
import com.ebookwriter.SaaS.repository.UserRepository;
import com.ebookwriter.SaaS.request.EbookImageUpdateRequest;
import com.ebookwriter.SaaS.service.ebook.EbookImageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Image management for an ebook: upload the author's own images (a cover and/or
 * inline chapter images), list them, stream the bytes for preview (the R2 bucket
 * is private), pick the cover, and delete. All endpoints require a valid access
 * token and are scoped to the authenticated owner of the ebook.
 */
@RestController
@RequestMapping("/api/ebooks/{ebookId}/images")
@RequiredArgsConstructor
public class EbookImageController {

    private final EbookImageService imageService;
    private final UserRepository userRepository;

    /** Upload an image. {@code role} defaults to INLINE; pass COVER for the cover. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<EbookImageDTO> upload(
            @PathVariable UUID ebookId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "role", required = false) EbookImageRole role,
            Authentication authentication) {
        User user = currentUser(authentication);
        EbookImageDTO dto = imageService.upload(ebookId, user.getId(), file, role);
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    /** List all images attached to the ebook. */
    @GetMapping
    public ResponseEntity<List<EbookImageDTO>> list(@PathVariable UUID ebookId,
                                                    Authentication authentication) {
        User user = currentUser(authentication);
        return ResponseEntity.ok(imageService.list(ebookId, user.getId()));
    }

    /** Stream an image's raw bytes (the bucket is private, so previews proxy through here). */
    @GetMapping("/{imageId}/raw")
    public ResponseEntity<byte[]> raw(@PathVariable UUID ebookId,
                                      @PathVariable UUID imageId,
                                      Authentication authentication) {
        User user = currentUser(authentication);
        EbookImageService.RawImage image = imageService.getRaw(ebookId, user.getId(), imageId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePrivate())
                // The stored bytes are user-supplied (an SVG can carry script);
                // stop the browser from sniffing/upgrading the declared type.
                .header("X-Content-Type-Options", "nosniff")
                .body(image.bytes());
    }

    /** Update editor-adjustable metadata: the asset's role and/or inline display width. */
    @PatchMapping("/{imageId}")
    public ResponseEntity<EbookImageDTO> update(@PathVariable UUID ebookId,
                                                @PathVariable UUID imageId,
                                                @Valid @RequestBody EbookImageUpdateRequest request,
                                                Authentication authentication) {
        User user = currentUser(authentication);
        return ResponseEntity.ok(imageService.updateMetadata(
                ebookId, user.getId(), imageId, request.getRole(), request.getDisplayWidthPercent()));
    }

    /** Make this image the book's cover (demotes any current cover). */
    @PutMapping("/{imageId}/cover")
    public ResponseEntity<EbookImageDTO> setCover(@PathVariable UUID ebookId,
                                                  @PathVariable UUID imageId,
                                                  Authentication authentication) {
        User user = currentUser(authentication);
        return ResponseEntity.ok(imageService.setCover(ebookId, user.getId(), imageId));
    }

    /** Clear the book's cover (keeps the asset, just unsets it as the cover). */
    @DeleteMapping("/cover")
    public ResponseEntity<Void> clearCover(@PathVariable UUID ebookId,
                                           Authentication authentication) {
        User user = currentUser(authentication);
        imageService.clearCover(ebookId, user.getId());
        return ResponseEntity.noContent().build();
    }

    /** Delete an image (also removes its bytes from storage). */
    @DeleteMapping("/{imageId}")
    public ResponseEntity<Void> delete(@PathVariable UUID ebookId,
                                       @PathVariable UUID imageId,
                                       Authentication authentication) {
        User user = currentUser(authentication);
        imageService.delete(ebookId, user.getId(), imageId);
        return ResponseEntity.noContent().build();
    }

    private User currentUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IllegalStateException("Unauthorized");
        }
        return userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new IllegalStateException("User not found"));
    }
}
