package com.ebookwriter.SaaS.controller;

import com.ebookwriter.SaaS.dto.AssetLibraryResponse;
import com.ebookwriter.SaaS.dto.LibraryAssetDTO;
import com.ebookwriter.SaaS.security.CurrentUserService;
import com.ebookwriter.SaaS.service.ebook.EbookImageService;
import com.ebookwriter.SaaS.service.library.AssetLibraryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * The user's asset library: every image they have uploaded, reusable anywhere
 * that takes an image. Add one to a book with
 * {@code POST /api/ebooks/{ebookId}/images/from-library/{assetId}} or to the
 * store with {@code POST /api/store/images/{kind}/from-library/{assetId}}.
 */
@RestController
@RequestMapping("/api/library")
@RequiredArgsConstructor
public class AssetLibraryController {

    private final AssetLibraryService libraryService;
    private final CurrentUserService currentUserService;

    @GetMapping
    public AssetLibraryResponse list(Authentication authentication) {
        return libraryService.list(currentUserService.require(authentication).getId());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<LibraryAssetDTO> upload(@RequestParam("file") MultipartFile file,
                                                  Authentication authentication) {
        UUID userId = currentUserService.require(authentication).getId();
        return ResponseEntity.status(HttpStatus.CREATED).body(libraryService.upload(userId, file));
    }

    /** Stream an asset's bytes (the bucket is private). */
    @GetMapping("/{assetId}/raw")
    public ResponseEntity<byte[]> raw(@PathVariable UUID assetId, Authentication authentication) {
        EbookImageService.RawImage image =
                libraryService.raw(currentUserService.require(authentication).getId(), assetId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePrivate())
                // User-supplied bytes (an SVG can carry script): no type sniffing.
                .header("X-Content-Type-Options", "nosniff")
                .body(image.bytes());
    }

    @DeleteMapping("/{assetId}")
    public ResponseEntity<Void> delete(@PathVariable UUID assetId, Authentication authentication) {
        libraryService.delete(currentUserService.require(authentication).getId(), assetId);
        return ResponseEntity.noContent().build();
    }
}
