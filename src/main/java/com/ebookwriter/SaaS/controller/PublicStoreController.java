package com.ebookwriter.SaaS.controller;

import com.ebookwriter.SaaS.dto.store.DownloadInfoResponse;
import com.ebookwriter.SaaS.dto.store.PublicProductResponse;
import com.ebookwriter.SaaS.dto.store.PublicStoreResponse;
import com.ebookwriter.SaaS.dto.store.StoreCheckoutResponse;
import com.ebookwriter.SaaS.dto.store.StoreOrderStatusResponse;
import com.ebookwriter.SaaS.entity.User;
import com.ebookwriter.SaaS.request.StoreCheckoutRequest;
import com.ebookwriter.SaaS.repository.StoreProductRepository;
import com.ebookwriter.SaaS.repository.UserRepository;
import com.ebookwriter.SaaS.service.store.DownloadGrantService;
import com.ebookwriter.SaaS.service.store.PurchaseService;
import com.ebookwriter.SaaS.service.store.ProductCoverService;
import com.ebookwriter.SaaS.service.store.StoreImageService;
import com.ebookwriter.SaaS.service.store.StoreCheckoutService;
import com.ebookwriter.SaaS.service.store.StoreService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Buyer-facing, unauthenticated store endpoints (permitAll under /api/public).
 * Buyers need no Scrivetta account; the emailed download token (guests) or a
 * short-lived signed link from My Purchases (signed-in buyers) is the only
 * credential for a file.
 */
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicStoreController {

    private final StoreService storeService;
    private final StoreCheckoutService checkoutService;
    private final DownloadGrantService downloadGrantService;
    private final StoreImageService imageService;
    private final ProductCoverService coverService;
    private final StoreProductRepository productRepository;
    private final PurchaseService purchaseService;
    private final UserRepository userRepository;

    @GetMapping("/stores/{slug}")
    public PublicStoreResponse store(@PathVariable String slug) {
        return storeService.getPublic(slug);
    }

    @GetMapping("/stores/{slug}/products/{productId}")
    public PublicProductResponse product(@PathVariable String slug, @PathVariable UUID productId) {
        return storeService.getPublicProduct(slug, productId);
    }

    /** The store's logo or author photo. */
    @GetMapping("/stores/{slug}/images/{kind}")
    public ResponseEntity<byte[]> storeImage(@PathVariable String slug, @PathVariable String kind) {
        StoreImageService.Image image = imageService.get(slug, StoreController.kind(kind))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Image not found"));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic())
                .header("X-Content-Type-Options", "nosniff")
                .body(image.bytes());
    }

    /** The product's cover: the author's own cover, else the first page of the ebook's PDF. */
    @GetMapping("/products/{productId}/cover")
    public ResponseEntity<byte[]> cover(@PathVariable UUID productId) {
        StoreImageService.Image image = productRepository.findById(productId)
                .flatMap(p -> imageService.productCover(p)
                        .or(() -> p.getEbookId() == null ? java.util.Optional.empty()
                                : coverService.cover(p.getEbookId()).map(jpeg -> new StoreImageService.Image(jpeg, "image/jpeg"))))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cover not found"));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic())
                .header("X-Content-Type-Options", "nosniff")
                .body(image.bytes());
    }

    /**
     * The client sends only the product id. A signed-in buyer (optional bearer
     * token) gets the order linked to their account.
     */
    @PostMapping("/checkout")
    public StoreCheckoutResponse checkout(@Valid @RequestBody StoreCheckoutRequest request,
                                          Authentication authentication) {
        User buyer = authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)
                ? userRepository.findByEmail(authentication.getName()).orElse(null) : null;
        return checkoutService.checkout(request.productId(), buyer);
    }

    @GetMapping("/orders/{orderId}")
    public ResponseEntity<StoreOrderStatusResponse> order(@PathVariable UUID orderId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(checkoutService.status(orderId));
    }

    @GetMapping("/downloads/{token}")
    public ResponseEntity<DownloadInfoResponse> downloadInfo(@PathVariable String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(downloadGrantService.info(token));
    }

    /** The product's first file (download links from before products had several files). Counts one download. */
    @GetMapping("/downloads/{token}/file")
    public ResponseEntity<Resource> download(@PathVariable String token) {
        return FileResponses.attachment(downloadGrantService.download(token, null));
    }

    /** One of the product's files, as listed by the download info. Counts one download. */
    @GetMapping("/downloads/{token}/files/{fileId}")
    public ResponseEntity<Resource> downloadFile(@PathVariable String token, @PathVariable String fileId) {
        return FileResponses.attachment(downloadGrantService.download(token, fileId));
    }

    /** A My Purchases download: the short-lived signed link is the credential; access is re-checked. */
    @GetMapping("/purchase-files")
    public ResponseEntity<Resource> purchaseFile(@RequestParam(value = "token", required = false) String token) {
        return FileResponses.attachment(purchaseService.download(token));
    }
}
