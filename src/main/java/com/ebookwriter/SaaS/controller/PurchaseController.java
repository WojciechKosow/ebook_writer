package com.ebookwriter.SaaS.controller;

import com.ebookwriter.SaaS.dto.store.PurchaseResponse;
import com.ebookwriter.SaaS.security.CurrentUserService;
import com.ebookwriter.SaaS.service.store.PurchaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * My Purchases — the signed-in buyer's store purchases. The file itself is
 * served by {@code GET /api/public/purchase-files?token=…} (see
 * {@link PublicStoreController}), reached through a short-lived link issued here.
 */
@RestController
@RequestMapping("/api/purchases")
@RequiredArgsConstructor
public class PurchaseController {

    private final PurchaseService purchaseService;
    private final CurrentUserService currentUserService;

    @GetMapping
    public ResponseEntity<List<PurchaseResponse>> list(Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(purchaseService.list(currentUserService.require(authentication)));
    }

    /** A short-lived link to download one file of a purchase. */
    @PostMapping("/{orderId}/files/{fileId}/download-link")
    public Map<String, String> downloadLink(Authentication authentication, @PathVariable UUID orderId,
                                            @PathVariable String fileId) {
        return Map.of("url", purchaseService.downloadLink(currentUserService.require(authentication), orderId, fileId));
    }
}
