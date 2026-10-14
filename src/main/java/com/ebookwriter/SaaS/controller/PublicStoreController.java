package com.ebookwriter.SaaS.controller;

import com.ebookwriter.SaaS.dto.store.DownloadInfoResponse;
import com.ebookwriter.SaaS.dto.store.PublicStoreResponse;
import com.ebookwriter.SaaS.dto.store.StoreCheckoutResponse;
import com.ebookwriter.SaaS.dto.store.StoreOrderStatusResponse;
import com.ebookwriter.SaaS.request.StoreCheckoutRequest;
import com.ebookwriter.SaaS.service.store.DownloadGrantService;
import com.ebookwriter.SaaS.service.store.StoreCheckoutService;
import com.ebookwriter.SaaS.service.store.StoreService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

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

    @GetMapping("/stores/{slug}")
    public PublicStoreResponse store(@PathVariable String slug) {
        return storeService.getPublic(slug);
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
