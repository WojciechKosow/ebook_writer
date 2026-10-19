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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Guest download access for paid store orders (buyers need no account). The raw
 * token exists only in the buyer's email; the database keeps its SHA-256 hash.
 * The success page reaches the same grant with the order id and its Stripe
 * Checkout Session id (only the buyer's browser has it, from Stripe's redirect).
 * A grant is valid for {@value #VALIDITY_DAYS} days and {@value #MAX_DOWNLOADS}
 * downloads of each file, and a full refund revokes it. Guests get every file
 * except the account perks. Files are only ever streamed by the backend — there
 * is no public path to them. Buyers with a verified account get unlimited
 * downloads, perks included, in My Purchases instead ({@link PurchaseService}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DownloadGrantService {

    public static final int VALIDITY_DAYS = 30;
    public static final int MAX_DOWNLOADS = 5;

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
        List<ProductFileDTO> files = guestFiles(product, grant);
        long size = files.stream().mapToLong(ProductFileDTO::sizeBytes).sum();
        int left = files.stream().mapToInt(f -> f.downloadsLeft() == null ? 0 : f.downloadsLeft()).sum();
        return new DownloadInfoResponse(product.getTitle(),
                store != null ? store.getName() : null,
                store != null ? store.getSlug() : null,
                store != null ? store.getAuthorName() : null,
                store != null ? store.getContactEmail() : null,
                store != null ? StoreImageService.url(store, StoreImageService.Kind.LOGO) : null,
                StoreService.coverUrl(product, ebook), size, files,
                left, grant.getExpiresAt(),
                store != null ? storeService.moreFrom(store, product.getId()) : List.of(),
                store != null ? storeService.profile(store) : null,
                store != null ? store.getThemeSettings() : null, product.getPageSettings(),
                perks(product), MAX_DOWNLOADS);
    }

    /** The order's guest downloads for the success page; null until the grant exists (the order is paid). */
    @Transactional(readOnly = true)
    public OrderFiles orderFiles(StoreOrder order) {
        DownloadGrant grant = grantRepository.findByOrderId(order.getId()).orElse(null);
        StoreProduct product = productRepository.findById(order.getProductId()).orElse(null);
        if (grant == null || product == null || order.getStatus() != StoreOrderStatus.PAID || grant.getRevokedAt() != null) {
            return null;
        }
        return new OrderFiles(guestFiles(product, grant), perks(product), grant.getExpiresAt());
    }

    public record OrderFiles(List<ProductFileDTO> files, List<ProductFileDTO> perks, LocalDateTime expiresAt) {
    }

    /**
     * Consume one download of one of the files a guest gets ({@code fileId} as
     * listed by {@link #info}; null = the first file, for links from before
     * products had several files). The file is opened before the download is
     * counted, so a missing file never uses one up.
     */
    @Transactional
    public ProductFileService.FileStream download(String token, String fileId) {
        DownloadGrant grant = requireUsable(token);
        StoreOrder order = orderRepository.findById(grant.getOrderId()).orElseThrow(this::notFound);
        return consume(grant, order, fileId);
    }

    /** The success page's download: the order id plus its Checkout Session id are the credential. */
    @Transactional
    public ProductFileService.FileStream downloadForOrder(UUID orderId, String sessionId, String fileId) {
        StoreOrder order = requireCheckoutSession(orderId, sessionId);
        DownloadGrant grant = grantRepository.findByOrderId(order.getId()).orElseThrow(this::notFound);
        requireUsable(grant, order);
        return consume(grant, order, fileId);
    }

    /**
     * The order, when {@code sessionId} is its Stripe Checkout Session — which only
     * the buyer's browser has, from Stripe's redirect. Anything else is the same 404.
     */
    @Transactional(readOnly = true)
    public StoreOrder requireCheckoutSession(UUID orderId, String sessionId) {
        StoreOrder order = orderRepository.findById(orderId).orElseThrow(this::notFound);
        if (!sameSession(order, sessionId)) {
            throw notFound();
        }
        return order;
    }

    static boolean sameSession(StoreOrder order, String sessionId) {
        return sessionId != null && order.getCheckoutSessionId() != null && MessageDigest.isEqual(
                sessionId.getBytes(StandardCharsets.UTF_8), order.getCheckoutSessionId().getBytes(StandardCharsets.UTF_8));
    }

    private ProductFileService.FileStream consume(DownloadGrant usable, StoreOrder order, String fileId) {
        StoreProduct product = productRepository.findById(order.getProductId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.GONE, "This product is no longer available."));
        List<ProductFileService.DeliverableFile> files = fileService.deliverables(product, false);
        if (files.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.GONE, "This file is no longer available.");
        }
        String id = fileId != null ? fileId : files.get(0).id();
        if (files.stream().noneMatch(f -> f.id().equals(id))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found");
        }
        // Locked: two concurrent requests can never exceed a file's limit.
        DownloadGrant grant = grantRepository.lockById(usable.getId()).orElseThrow(this::notFound);
        requireUsable(grant, order);
        Map<String, Integer> used = counts(grant);
        int n = used.getOrDefault(id, 0);
        if (n >= MAX_DOWNLOADS) {
            throw new ResponseStatusException(HttpStatus.GONE,
                    "This file has been downloaded " + MAX_DOWNLOADS + " times already — the most this link allows.");
        }
        ProductFileService.FileStream file = fileService.open(product, id);
        try {
            used.put(id, n + 1);
            grant.setFileDownloads(format(used));
            grant.setDownloadsUsed(grant.getDownloadsUsed() + 1);
            grantRepository.save(grant);
        } catch (RuntimeException e) {
            closeQuietly(file);
            throw e;
        }
        log.info("[StoreDownload] Order {} downloaded {} ({} of {})", order.getId(), id, n + 1, MAX_DOWNLOADS);
        return file;
    }

    /** The files a guest gets, each with its downloads left. */
    private List<ProductFileDTO> guestFiles(StoreProduct product, DownloadGrant grant) {
        Map<String, Integer> used = counts(grant);
        return fileService.deliverables(product, false).stream()
                .map(f -> StoreService.toFileDTO(f).withDownloadsLeft(Math.max(0, MAX_DOWNLOADS - used.getOrDefault(f.id(), 0))))
                .toList();
    }

    /** The account perks, which a guest sees but doesn't get. */
    private List<ProductFileDTO> perks(StoreProduct product) {
        return fileService.perks(product).stream().map(f -> StoreService.toFileDTO(f).withoutFilename()).toList();
    }

    static Map<String, Integer> counts(DownloadGrant grant) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (grant.getFileDownloads() == null || grant.getFileDownloads().isBlank()) {
            return out;
        }
        for (String pair : grant.getFileDownloads().split(",")) {
            int eq = pair.lastIndexOf('=');
            if (eq > 0) {
                try {
                    out.put(pair.substring(0, eq), Integer.parseInt(pair.substring(eq + 1)));
                } catch (NumberFormatException ignored) {
                    // a damaged pair counts as unused
                }
            }
        }
        return out;
    }

    static String format(Map<String, Integer> counts) {
        return counts.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                .collect(java.util.stream.Collectors.joining(","));
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
        requireUsable(grant, order);
        return grant;
    }

    private static void requireUsable(DownloadGrant grant, StoreOrder order) {
        if (grant.getRevokedAt() != null || order.getStatus() != StoreOrderStatus.PAID) {
            throw new ResponseStatusException(HttpStatus.GONE, "This download link is no longer valid.");
        }
        if (!grant.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.GONE, "This download link has expired.");
        }
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
