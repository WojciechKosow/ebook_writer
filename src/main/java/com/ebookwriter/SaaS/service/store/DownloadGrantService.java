package com.ebookwriter.SaaS.service.store;

import com.ebookwriter.SaaS.dto.store.DownloadInfoResponse;
import com.ebookwriter.SaaS.dto.store.ProductFileDTO;
import com.ebookwriter.SaaS.entity.*;
import com.ebookwriter.SaaS.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Guest download access for paid store orders (buyers need no account). The raw
 * token exists only in the buyer's email; the database keeps its SHA-256 hash. A
 * grant is valid for {@value #VALIDITY_DAYS} days and {@value #MAX_DOWNLOADS}
 * downloads per file the product had when it was bought (each file download
 * counts one), and a full refund revokes it. Files are only ever streamed by the
 * backend — there is no public path to them. Buyers with a verified account get
 * unlimited downloads in My Purchases instead ({@link PurchaseService}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DownloadGrantService {

    static final int VALIDITY_DAYS = 30;
    static final int MAX_DOWNLOADS = 5;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DownloadGrantRepository grantRepository;
    private final StoreOrderRepository orderRepository;
    private final StoreProductRepository productRepository;
    private final StoreRepository storeRepository;
    private final EbookRepository ebookRepository;
    private final StoreService storeService;
    private final ProductFileService fileService;

    /**
     * Create the grant for a just-paid order. Returns the raw token (to email;
     * never stored), or null when the order already has one — a retried webhook
     * never issues a second link.
     */
    @Transactional
    public String issue(UUID orderId) {
        if (grantRepository.findByOrderId(orderId).isPresent()) {
            log.info("[StoreDownload] Order {} already has a download grant — not issuing another", orderId);
            return null;
        }
        int files = orderRepository.findById(orderId)
                .flatMap(o -> productRepository.findById(o.getProductId()))
                .map(p -> fileService.deliverables(p).size())
                .orElse(1);
        String token = newToken();
        DownloadGrant grant = new DownloadGrant();
        grant.setOrderId(orderId);
        grant.setTokenHash(hash(token));
        grant.setExpiresAt(LocalDateTime.now().plusDays(VALIDITY_DAYS));
        grant.setMaxDownloads(MAX_DOWNLOADS * Math.max(1, files));
        grant.setDownloadsUsed(0);
        grantRepository.save(grant);
        return token;
    }

    /** Full refund: the buyer loses access. Idempotent. */
    @Transactional
    public void revoke(UUID orderId) {
        grantRepository.findByOrderId(orderId).ifPresent(grant -> {
            if (grant.getRevokedAt() == null) {
                grant.setRevokedAt(LocalDateTime.now());
                grantRepository.save(grant);
                log.info("[StoreDownload] Revoked download grant for order {}", orderId);
            }
        });
    }

    /** What the download page shows. Does not count as a download. */
    @Transactional(readOnly = true)
    public DownloadInfoResponse info(String token) {
        DownloadGrant grant = requireUsable(token);
        StoreOrder order = orderRepository.findById(grant.getOrderId()).orElseThrow(this::notFound);
        StoreProduct product = productRepository.findById(order.getProductId()).orElseThrow(this::notFound);
        Store store = storeRepository.findById(order.getStoreId()).orElse(null);
        Ebook ebook = storeService.ebookOf(product);
        List<ProductFileDTO> files = fileService.deliverables(product).stream().map(StoreService::toFileDTO).toList();
        long size = files.stream().mapToLong(ProductFileDTO::sizeBytes).sum();
        return new DownloadInfoResponse(product.getTitle(),
                store != null ? store.getName() : null,
                store != null ? store.getSlug() : null,
                store != null ? store.getAuthorName() : null,
                store != null ? store.getContactEmail() : null,
                store != null ? StoreImageService.url(store, StoreImageService.Kind.LOGO) : null,
                StoreService.coverUrl(product, ebook), size, files,
                grant.getMaxDownloads() - grant.getDownloadsUsed(), grant.getExpiresAt(),
                store != null ? storeService.moreFrom(store, product.getId()) : List.of());
    }

    /**
     * Consume one download and open one of the product's current files
     * ({@code fileId} as listed by {@link #info}; null = the first file, for links
     * from before products had several files). The file is opened before the
     * download is counted, so a missing file never uses one up.
     */
    @Transactional
    public ProductFileService.FileStream download(String token, String fileId) {
        DownloadGrant grant = requireUsable(token);
        StoreOrder order = orderRepository.findById(grant.getOrderId()).orElseThrow(this::notFound);
        StoreProduct product = productRepository.findById(order.getProductId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.GONE, "This product is no longer available."));
        ProductFileService.FileStream file = fileId == null ? fileService.openPrimary(product) : fileService.open(product, fileId);

        // Atomic: two concurrent requests can never exceed the download limit.
        if (grantRepository.consume(grant.getId(), LocalDateTime.now()) != 1) {
            closeQuietly(file);
            throw new ResponseStatusException(HttpStatus.GONE, "This download link has expired or been used up.");
        }
        log.info("[StoreDownload] Order {} downloaded {} ({} of {})",
                order.getId(), fileId == null ? "primary file" : fileId, grant.getDownloadsUsed() + 1, grant.getMaxDownloads());
        return file;
    }

    private static void closeQuietly(ProductFileService.FileStream file) {
        try {
            file.stream().close();
        } catch (java.io.IOException ignored) {
            // nothing to do
        }
    }

    private DownloadGrant requireUsable(String token) {
        if (token == null || token.isBlank() || token.length() > 200) {
            throw notFound();
        }
        DownloadGrant grant = grantRepository.findByTokenHash(hash(token)).orElseThrow(this::notFound);
        StoreOrder order = orderRepository.findById(grant.getOrderId()).orElseThrow(this::notFound);
        if (grant.getRevokedAt() != null || order.getStatus() != StoreOrderStatus.PAID) {
            throw new ResponseStatusException(HttpStatus.GONE, "This download link is no longer valid.");
        }
        if (!grant.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.GONE, "This download link has expired.");
        }
        if (grant.getDownloadsUsed() >= grant.getMaxDownloads()) {
            throw new ResponseStatusException(HttpStatus.GONE, "This download link has been used the maximum number of times.");
        }
        return grant;
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Download link not found.");
    }

    static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

}
