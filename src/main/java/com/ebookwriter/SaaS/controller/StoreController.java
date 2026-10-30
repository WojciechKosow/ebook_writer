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

    /** Whether a store address is free — for the first-run form. */
    @GetMapping("/slug-available")
    public StoreService.SlugStatus slugAvailable(Authentication authentication, @RequestParam String slug) {
        currentUserService.require(authentication);
        return storeService.slugStatus(slug);
    }

    @PostMapping
    public StoreDashboardResponse create(Authentication authentication, @Valid @RequestBody StoreCreateRequest request) {
        return storeService.create(currentUserService.require(authentication), request);
    }

    @PutMapping
    public StoreDashboardResponse update(Authentication authentication, @Valid @RequestBody StoreUpdateRequest request) {
        return storeService.update(currentUserService.require(authentication), request);
    }

    /** Save the store page design (style, texts, colors, custom CSS) as a draft. */
    @PutMapping("/theme")
    public StoreDashboardResponse updateTheme(Authentication authentication, @Valid @RequestBody StoreThemeRequest request) {
        return storeService.updateTheme(currentUserService.require(authentication), request.settings());
    }

    /** "Publish changes": apply every draft change and make the store public. */
    @PostMapping("/publish")
    public StoreDashboardResponse publish(Authentication authentication) {
        return storeService.publish(currentUserService.require(authentication));
    }

    @PostMapping("/unpublish")
    public StoreDashboardResponse unpublish(Authentication authentication) {
        return storeService.unpublish(currentUserService.require(authentication));
    }

    /** Throw away every change that isn't published yet. */
    @PostMapping("/draft/discard")
    public StoreDashboardResponse discardDraft(Authentication authentication) {
        return storeService.discardDraft(currentUserService.require(authentication));
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

    /** Remove a product from the store (buyers keep their purchases). */
    @DeleteMapping("/products/{productId}")
    public StoreDashboardResponse removeProduct(Authentication authentication, @PathVariable UUID productId) {
        return storeService.removeProduct(currentUserService.require(authentication), productId);
    }

    /** The order of every product (first = featured), as a draft. */
    @PutMapping("/products/order")
    public StoreDashboardResponse reorderProducts(Authentication authentication,
                                                  @Valid @RequestBody StoreProductOrderRequest request) {
        return storeService.reorderProducts(currentUserService.require(authentication), request.productIds());
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

    /** An extra's name for buyers, what it's for and whether it's an account perk (draft). */
    @PatchMapping("/products/{productId}/files/{fileId}")
    public StoreDashboardResponse updateFile(Authentication authentication, @PathVariable UUID productId,
                                             @PathVariable UUID fileId, @Valid @RequestBody StoreFileUpdateRequest request) {
        return storeService.updateFile(currentUserService.require(authentication), productId, fileId, request);
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

    /** "Disconnect Stripe": unlink the connected account so another one can be connected. */
    @DeleteMapping("/stripe")
    public StoreDashboardResponse disconnectStripe(Authentication authentication) {
        return storeService.disconnectStripe(currentUserService.require(authentication));
    }
}
