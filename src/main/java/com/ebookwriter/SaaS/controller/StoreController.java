package com.ebookwriter.SaaS.controller;

import com.ebookwriter.SaaS.dto.store.StoreDashboardResponse;
import com.ebookwriter.SaaS.entity.User;
import com.ebookwriter.SaaS.request.*;
import com.ebookwriter.SaaS.security.CurrentUserService;
import com.ebookwriter.SaaS.service.store.ProductFileService;
import com.ebookwriter.SaaS.service.store.StoreImageService;
import com.ebookwriter.SaaS.service.store.StoreService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

/**
 * The author's own creator store. Every endpoint resolves the store from the
 * authenticated user, so an author can only ever read or change their own.
 */
@RestController
@RequestMapping("/api/store")
@RequiredArgsConstructor
public class StoreController {

    private final StoreService storeService;
    private final StoreImageService imageService;
    private final ProductFileService fileService;
    private final CurrentUserService currentUserService;

    /** 204 when the author has no store yet. */
    @GetMapping
    public ResponseEntity<StoreDashboardResponse> get(Authentication authentication) {
        User user = currentUserService.require(authentication);
        return storeService.getOwn(user).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping
    public StoreDashboardResponse create(Authentication authentication, @Valid @RequestBody StoreCreateRequest request) {
        return storeService.create(currentUserService.require(authentication), request);
    }

    @PutMapping
    public StoreDashboardResponse update(Authentication authentication, @Valid @RequestBody StoreUpdateRequest request) {
        return storeService.update(currentUserService.require(authentication), request);
    }

    /** Save the store page design (style, texts, colors, custom CSS). */
    @PutMapping("/theme")
    public StoreDashboardResponse updateTheme(Authentication authentication, @Valid @RequestBody StoreThemeRequest request) {
        return storeService.updateTheme(currentUserService.require(authentication), request.settings());
    }

    @PostMapping("/publish")
    public StoreDashboardResponse publish(Authentication authentication) {
        return storeService.setPublished(currentUserService.require(authentication), true);
    }

    @PostMapping("/unpublish")
    public StoreDashboardResponse unpublish(Authentication authentication) {
        return storeService.setPublished(currentUserService.require(authentication), false);
    }

    @PostMapping("/products")
    public StoreDashboardResponse addProduct(Authentication authentication,
                                             @Valid @RequestBody StoreProductCreateRequest request) {
        return storeService.addProduct(currentUserService.require(authentication), request);
    }

    @PatchMapping("/products/{productId}")
    public StoreDashboardResponse updateProduct(Authentication authentication, @PathVariable UUID productId,
                                                @Valid @RequestBody StoreProductUpdateRequest request) {
        return storeService.updateProduct(currentUserService.require(authentication), productId, request);
    }

    @PostMapping("/products/{productId}/move")
    public StoreDashboardResponse moveProduct(Authentication authentication, @PathVariable UUID productId,
                                              @Valid @RequestBody StoreProductMoveRequest request) {
        return storeService.moveProduct(currentUserService.require(authentication), productId,
                "up".equals(request.direction()));
    }

    // ---- Product files (PDF, EPUB, ZIP) and cover ---------------------------------

    /** Add a downloadable file to a product. */
    @PostMapping(value = "/products/{productId}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public StoreDashboardResponse uploadFile(Authentication authentication, @PathVariable UUID productId,
                                             @RequestParam("file") MultipartFile file) {
        User user = currentUserService.require(authentication);
        fileService.upload(user, productId, file);
        return storeService.getOwn(user).orElseThrow();
    }

    /** Replace a file with a new version; buyers get the new one, existing purchases keep working. */
    @PostMapping(value = "/products/{productId}/files/{fileId}/replace", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public StoreDashboardResponse replaceFile(Authentication authentication, @PathVariable UUID productId,
                                              @PathVariable UUID fileId, @RequestParam("file") MultipartFile file) {
        User user = currentUserService.require(authentication);
        fileService.replace(user, productId, fileId, file);
        return storeService.getOwn(user).orElseThrow();
    }

    @DeleteMapping("/products/{productId}/files/{fileId}")
    public StoreDashboardResponse removeFile(Authentication authentication, @PathVariable UUID productId,
                                             @PathVariable UUID fileId) {
        User user = currentUserService.require(authentication);
        fileService.remove(user, productId, fileId);
        return storeService.getOwn(user).orElseThrow();
    }

    /** Any chapter of the author's ebook as the free preview would show it — for the page editor. */
    @GetMapping("/products/{productId}/preview/{number}")
    public com.ebookwriter.SaaS.dto.store.SampleChapterResponse sample(Authentication authentication,
                                                                       @PathVariable UUID productId,
                                                                       @PathVariable int number) {
        return storeService.ownSample(currentUserService.require(authentication), productId, number);
    }

    /** The product's own cover/thumbnail (PNG, JPG or WebP) — separate from its files. */
    @PostMapping(value = "/products/{productId}/cover", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public StoreDashboardResponse uploadCover(Authentication authentication, @PathVariable UUID productId,
                                              @RequestParam("file") MultipartFile file) {
        User user = currentUserService.require(authentication);
        imageService.uploadProductCover(user, productId, file);
        return storeService.getOwn(user).orElseThrow();
    }

    @PostMapping("/products/{productId}/cover/from-library/{assetId}")
    public StoreDashboardResponse useLibraryCover(Authentication authentication, @PathVariable UUID productId,
                                                  @PathVariable UUID assetId) {
        User user = currentUserService.require(authentication);
        imageService.useLibraryProductCover(user, productId, assetId);
        return storeService.getOwn(user).orElseThrow();
    }

    @DeleteMapping("/products/{productId}/cover")
    public StoreDashboardResponse removeCover(Authentication authentication, @PathVariable UUID productId) {
        User user = currentUserService.require(authentication);
        imageService.removeProductCover(user, productId);
        return storeService.getOwn(user).orElseThrow();
    }

    /** Upload the store logo or author photo ({@code kind} = logo | photo). */
    @PostMapping(value = "/images/{kind}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public StoreDashboardResponse uploadImage(Authentication authentication, @PathVariable String kind,
                                              @RequestParam("file") MultipartFile file) {
        User user = currentUserService.require(authentication);
        imageService.upload(user, kind(kind), file);
        return storeService.getOwn(user).orElseThrow();
    }

    /** Use an image from the asset library as the store logo or author photo. */
    @PostMapping("/images/{kind}/from-library/{assetId}")
    public StoreDashboardResponse useLibraryImage(Authentication authentication, @PathVariable String kind,
                                                  @PathVariable UUID assetId) {
        User user = currentUserService.require(authentication);
        imageService.useFromLibrary(user, kind(kind), assetId);
        return storeService.getOwn(user).orElseThrow();
    }

    @DeleteMapping("/images/{kind}")
    public StoreDashboardResponse removeImage(Authentication authentication, @PathVariable String kind) {
        User user = currentUserService.require(authentication);
        imageService.remove(user, kind(kind));
        return storeService.getOwn(user).orElseThrow();
    }

    static StoreImageService.Kind kind(String kind) {
        return switch (kind) {
            case "logo" -> StoreImageService.Kind.LOGO;
            case "photo" -> StoreImageService.Kind.PHOTO;
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown image");
        };
    }

    /** "Connect Stripe" / "Continue in Stripe": returns a fresh Stripe-hosted onboarding link. */
    @PostMapping("/stripe/connect")
    public Map<String, String> connect(Authentication authentication,
                                       @Valid @RequestBody(required = false) StripeConnectRequest request) {
        String country = request != null ? request.country() : null;
        return Map.of("url", storeService.connect(currentUserService.require(authentication), country));
    }

    /** On return from Stripe: re-fetch the account server-side. */
    @PostMapping("/stripe/sync")
    public StoreDashboardResponse sync(Authentication authentication) {
        return storeService.syncOwn(currentUserService.require(authentication));
    }
}
