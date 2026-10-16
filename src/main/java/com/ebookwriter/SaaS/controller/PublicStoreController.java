package com.ebookwriter.SaaS.controller;

import com.ebookwriter.SaaS.dto.store.DownloadInfoResponse;
import com.ebookwriter.SaaS.dto.store.PublicProductResponse;
import com.ebookwriter.SaaS.dto.store.PublicStoreResponse;
import com.ebookwriter.SaaS.dto.store.StoreCheckoutResponse;
import com.ebookwriter.SaaS.dto.store.StoreOrderStatusResponse;
import com.ebookwriter.SaaS.request.StoreCheckoutRequest;
import com.ebookwriter.SaaS.repository.StoreProductRepository;
import com.ebookwriter.SaaS.service.store.DownloadGrantService;
import com.ebookwriter.SaaS.service.store.ProductCoverService;
import com.ebookwriter.SaaS.service.store.StoreImageService;
import com.ebookwriter.SaaS.service.store.StoreCheckoutService;
import com.ebookwriter.SaaS.service.store.StoreService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Buyer-facing, unauthenticated store endpoints (permitAll under /api/public).
 * Buyers need no Scrivetta account; the download token is the only credential
 * for a file.
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

    /** The ebook's cover: the first page of its PDF. */
    @GetMapping("/products/{productId}/cover")
    public ResponseEntity<byte[]> cover(@PathVariable UUID productId) {
        byte[] jpeg = productRepository.findById(productId)
                .flatMap(p -> coverService.cover(p.getEbookId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cover not found"));
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic())
                .header("X-Content-Type-Options", "nosniff")
                .body(jpeg);
    }

    /** The client sends only the product id. */
    @PostMapping("/checkout")
    public StoreCheckoutResponse checkout(@Valid @RequestBody StoreCheckoutRequest request) {
        return checkoutService.checkout(request.productId());
    }

    @GetMapping("/orders/{orderId}")
    public ResponseEntity<StoreOrderStatusResponse> order(@PathVariable UUID orderId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(checkoutService.status(orderId));
    }

    @GetMapping("/downloads/{token}")
    public ResponseEntity<DownloadInfoResponse> downloadInfo(@PathVariable String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(downloadGrantService.info(token));
    }

    /** Counts one download. */
    @GetMapping("/downloads/{token}/file")
    public ResponseEntity<byte[]> download(@PathVariable String token) {
        DownloadGrantService.FileDownload file = downloadGrantService.download(token);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.filename() + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(file.bytes().length)
                .body(file.bytes());
    }
}
