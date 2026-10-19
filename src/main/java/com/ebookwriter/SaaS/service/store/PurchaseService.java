package com.ebookwriter.SaaS.service.store;

import com.ebookwriter.SaaS.dto.store.ProductFileDTO;
import com.ebookwriter.SaaS.dto.store.PurchaseResponse;
import com.ebookwriter.SaaS.entity.*;
import com.ebookwriter.SaaS.repository.StoreOrderRepository;
import com.ebookwriter.SaaS.repository.StoreProductRepository;
import com.ebookwriter.SaaS.repository.StoreRepository;
import com.ebookwriter.SaaS.repository.UserRepository;
import com.ebookwriter.SaaS.security.JwtProvider;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * My Purchases: the store products a signed-in buyer owns, and their downloads.
 *
 * <p><b>Entitlement</b> — a user may download an order's files only while the
 * order is {@link StoreOrderStatus#PAID} (set solely by the signed Connect
 * webhook — never by the success redirect) and the order belongs to them:
 * {@code buyerUserId} is theirs, or it is unclaimed and was paid with the
 * user's email and the account is verified ({@code enabled}). In that case the
 * order is claimed for the account on first sight. A refund ends access; the
 * author hiding the product, unpublishing the store or losing PRO does not.
 *
 * <p><b>Downloads</b> are unlimited. Like the author's PDF downloads they go
 * through a short-lived signed link ({@value #TTL_MILLIS} ms), so the browser can
 * download natively; the link is re-checked against the entitlement when used.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PurchaseService {

    static final long TTL_MILLIS = 5 * 60 * 1000L;
    private static final String TYPE_CLAIM = "typ";
    private static final String TYPE = "purchase-download";
    private static final String ORDER_CLAIM = "order";
    private static final String FILE_CLAIM = "file";

    private final StoreOrderRepository orderRepository;
    private final StoreProductRepository productRepository;
    private final StoreRepository storeRepository;
    private final UserRepository userRepository;
    private final StoreService storeService;
    private final ProductFileService fileService;
    private final JwtProvider jwtProvider;

    /** The user's paid purchases, newest first. Claims unclaimed orders paid with their verified email. */
    @Transactional
    public List<PurchaseResponse> list(User user) {
        claimByEmail(user);
        return orderRepository.findByBuyerUserIdAndStatusOrderByPaidAtDesc(user.getId(), StoreOrderStatus.PAID)
                .stream().map(this::toResponse).toList();
    }

    /** A short-lived link to one file of a purchase (relative API path). */
    @Transactional
    public String downloadLink(User user, UUID orderId, String fileId) {
        StoreOrder order = requireEntitled(user, orderId);
        StoreProduct product = requireProduct(order);
        // Fail now (404 / 410) rather than hand out a link to nothing.
        if (fileService.deliverables(product).stream().noneMatch(f -> f.id().equals(fileId))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found");
        }
        long now = System.currentTimeMillis();
        String token = Jwts.builder()
                .setSubject(user.getId().toString())
                .claim(TYPE_CLAIM, TYPE)
                .claim(ORDER_CLAIM, order.getId().toString())
                .claim(FILE_CLAIM, fileId)
                .setIssuedAt(new Date(now))
                .setExpiration(new Date(now + TTL_MILLIS))
                .signWith(jwtProvider.getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
        return "/api/public/purchase-files?token=" + token;
    }

    /** Serve the file a signed link names, after re-checking the entitlement. */
    @Transactional
    public ProductFileService.FileStream download(String token) {
        Claims claims = verify(token);
        User user = userRepository.findById(UUID.fromString(claims.getSubject()))
                .orElseThrow(PurchaseService::invalidLink);
        StoreOrder order = requireEntitled(user, UUID.fromString(claims.get(ORDER_CLAIM, String.class)));
        String fileId = claims.get(FILE_CLAIM, String.class);
        ProductFileService.FileStream file = fileService.open(requireProduct(order), fileId);
        log.info("[Purchases] User {} downloaded {} of order {}", user.getId(), fileId, order.getId());
        return file;
    }

    /**
     * The order, if this user may download it. Anything else — unknown, someone
     * else's, unpaid, refunded — is the same 404, so order ids can't be probed.
     */
    StoreOrder requireEntitled(User user, UUID orderId) {
        StoreOrder order = orderRepository.findById(orderId).orElseThrow(PurchaseService::notFound);
        if (order.getStatus() != StoreOrderStatus.PAID || !user.isEnabled()) {
            throw notFound();
        }
        if (order.getBuyerUserId() == null && order.getBuyerEmail() != null
                && order.getBuyerEmail().equalsIgnoreCase(user.getEmail())) {
            order.setBuyerUserId(user.getId());
            orderRepository.save(order);
            log.info("[Purchases] Order {} claimed by verified account {}", order.getId(), user.getId());
        }
        if (!Objects.equals(order.getBuyerUserId(), user.getId())) {
            throw notFound();
        }
        return order;
    }

    private void claimByEmail(User user) {
        if (!user.isEnabled() || user.getEmail() == null) {
            return;
        }
        for (StoreOrder order : orderRepository.findUnclaimedByEmail(user.getEmail(), StoreOrderStatus.PAID)) {
            order.setBuyerUserId(user.getId());
            orderRepository.save(order);
            log.info("[Purchases] Order {} claimed by verified account {}", order.getId(), user.getId());
        }
    }

    private PurchaseResponse toResponse(StoreOrder order) {
        StoreProduct product = productRepository.findById(order.getProductId()).orElse(null);
        Store store = storeRepository.findById(order.getStoreId()).orElse(null);
        // A file replaced since the purchase is marked, so the buyer knows they have the new version.
        List<ProductFileDTO> files = product == null ? List.of()
                : fileService.deliverables(product).stream()
                .map(f -> StoreService.toFileDTO(f).withUpdated(f.version() > 1 && f.updatedAt() != null
                        && order.getPaidAt() != null && f.updatedAt().isAfter(order.getPaidAt())))
                .toList();
        java.time.LocalDateTime updatedAt = files.stream().filter(ProductFileDTO::updated)
                .map(ProductFileDTO::updatedAt).max(java.util.Comparator.naturalOrder()).orElse(null);
        return new PurchaseResponse(order.getId(), order.getProductId(),
                product != null ? product.getTitle() : "Unavailable product",
                store != null ? store.getName() : null,
                store != null && store.isPublished() ? store.getSlug() : null,
                store != null ? store.getAuthorName() : null,
                store != null ? store.getContactEmail() : null,
                product != null ? StoreService.coverUrl(product, storeService.ebookOf(product)) : null,
                order.getAmount(), order.getCurrency(), order.getPaidAt(),
                product != null && product.isActive() && store != null && store.isPublished(),
                files, updatedAt);
    }

    private StoreProduct requireProduct(StoreOrder order) {
        return productRepository.findById(order.getProductId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.GONE, "This product is no longer available."));
    }

    private Claims verify(String token) {
        if (token == null || token.isBlank()) {
            throw invalidLink();
        }
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(jwtProvider.getSigningKey())
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            if (!TYPE.equals(claims.get(TYPE_CLAIM, String.class)) || claims.getSubject() == null
                    || claims.get(ORDER_CLAIM, String.class) == null || claims.get(FILE_CLAIM, String.class) == null) {
                throw invalidLink();
            }
            UUID.fromString(claims.getSubject());
            UUID.fromString(claims.get(ORDER_CLAIM, String.class));
            return claims;
        } catch (JwtException | IllegalArgumentException e) {
            throw invalidLink();
        }
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Purchase not found");
    }

    private static ResponseStatusException invalidLink() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "This download link is invalid or has expired.");
    }
}
