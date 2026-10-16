package com.ebookwriter.SaaS.service.store;

import com.ebookwriter.SaaS.dto.store.*;
import com.ebookwriter.SaaS.entity.*;
import com.ebookwriter.SaaS.repository.EbookPdfRepository;
import com.ebookwriter.SaaS.repository.EbookRepository;
import com.ebookwriter.SaaS.repository.StoreProductRepository;
import com.ebookwriter.SaaS.repository.StoreRepository;
import com.ebookwriter.SaaS.request.*;
import com.ebookwriter.SaaS.service.payment.SubscriptionService;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.StripeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Creator stores: the author's own store and products, the Stripe Connect
 * onboarding/status sync, and the public, read-only view buyers see.
 *
 * <p>Every author method resolves the store from the authenticated user
 * ({@code ownerId}), never from an id in the request, so an author can only
 * ever touch their own store.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StoreService {

    /** Store prices are in USD for the English-speaking market. */
    public static final String CURRENCY = "usd";
    /** Stripe's minimum charge for USD is $0.50. */
    public static final long MIN_PRICE_CENTS = 50;
    public static final long MAX_PRICE_CENTS = 100_000;

    static final String REASON_NOT_PUBLISHED = "This store isn't open yet.";
    static final String REASON_NOT_SELLING = "This store isn't accepting payments right now.";
    static final String REASON_PAYMENTS_SETUP = "The author hasn't finished setting up payments yet.";

    private final StoreRepository storeRepository;
    private final StoreProductRepository productRepository;
    private final EbookRepository ebookRepository;
    private final EbookPdfRepository pdfRepository;
    private final SubscriptionService subscriptionService;
    private final StripeConnectGateway stripe;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    // ---- Author: store -----------------------------------------------------

    @Transactional(readOnly = true)
    public Optional<StoreDashboardResponse> getOwn(User user) {
        return storeRepository.findByOwnerId(user.getId()).map(this::toDashboard);
    }

    @Transactional
    public StoreDashboardResponse create(User user, StoreCreateRequest request) {
        if (storeRepository.findByOwnerId(user.getId()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You already have a store");
        }
        if (storeRepository.existsBySlug(request.slug())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That store address is taken");
        }
        Store store = new Store();
        store.setOwnerId(user.getId());
        store.setSlug(request.slug());
        store.setName(request.name().trim());
        store.setDescription(blankToNull(request.description()));
        return toDashboard(storeRepository.save(store));
    }

    @Transactional
    public StoreDashboardResponse update(User user, StoreUpdateRequest request) {
        Store store = requireOwn(user);
        store.setName(request.name().trim());
        store.setDescription(blankToNull(request.description()));
        store.setHeadline(blankToNull(request.headline()));
        store.setAuthorName(blankToNull(request.authorName()));
        store.setContactEmail(blankToNull(request.contactEmail()));
        store.setInstagramUrl(blankToNull(request.instagramUrl()));
        store.setTiktokUrl(blankToNull(request.tiktokUrl()));
        store.setWebsiteUrl(blankToNull(request.websiteUrl()));
        return toDashboard(storeRepository.save(store));
    }

    /** Publishing requires Scrivetta PRO. */
    @Transactional
    public StoreDashboardResponse setPublished(User user, boolean published) {
        Store store = requireOwn(user);
        if (published && !subscriptionService.isPro(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Publishing a store requires Scrivetta PRO");
        }
        store.setPublished(published);
        return toDashboard(storeRepository.save(store));
    }

    // ---- Author: products --------------------------------------------------

    @Transactional
    public StoreDashboardResponse addProduct(User user, StoreProductCreateRequest request) {
        Store store = requireOwn(user);
        Ebook ebook = ebookRepository.findByIdAndUserId(request.ebookId(), user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ebook not found"));
        if (ebook.getStatus() != EbookStatus.COMPLETED || !pdfRepository.existsById(ebook.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only finished ebooks can be sold");
        }
        if (productRepository.existsByStoreIdAndEbookId(store.getId(), ebook.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This ebook is already in your store");
        }
        StoreProduct product = new StoreProduct();
        product.setStoreId(store.getId());
        product.setEbookId(ebook.getId());
        String title = blankToNull(request.title());
        if (title == null) {
            title = ebook.getTitle() != null && !ebook.getTitle().isBlank() ? ebook.getTitle().trim() : "Untitled ebook";
        }
        product.setTitle(truncate(title, 200));
        product.setDescription(blankToNull(request.description()) != null
                ? blankToNull(request.description())
                : blankToNull(ebook.getDescription()));
        product.setPriceCents(validPrice(request.priceCents()));
        product.setCurrency(CURRENCY);
        product.setSortOrder(productRepository.findByStoreIdOrderBySortOrderAscCreatedAtAsc(store.getId()).stream()
                .mapToInt(StoreProduct::getSortOrder).max().orElse(-1) + 1);
        productRepository.save(product);
        return toDashboard(store);
    }

    @Transactional
    public StoreDashboardResponse updateProduct(User user, UUID productId, StoreProductUpdateRequest request) {
        Store store = requireOwn(user);
        StoreProduct product = productRepository.findByIdAndStoreId(productId, store.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
        if (request.title() != null) {
            String title = blankToNull(request.title());
            if (title == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Title can't be empty");
            }
            product.setTitle(title);
        }
        if (request.description() != null) {
            product.setDescription(blankToNull(request.description()));
        }
        if (request.highlights() != null) {
            product.setHighlights(blankToNull(request.highlights()));
        }
        if (request.priceCents() != null) {
            product.setPriceCents(validPrice(request.priceCents()));
        }
        if (request.active() != null) {
            product.setActive(request.active());
        }
        productRepository.save(product);
        return toDashboard(store);
    }

    /** Swap a product with its neighbour; the first active product is the store's featured one. */
    @Transactional
    public StoreDashboardResponse moveProduct(User user, UUID productId, boolean up) {
        Store store = requireOwn(user);
        List<StoreProduct> products = new java.util.ArrayList<>(
                productRepository.findByStoreIdOrderBySortOrderAscCreatedAtAsc(store.getId()));
        int i = -1;
        for (int k = 0; k < products.size(); k++) {
            if (products.get(k).getId().equals(productId)) {
                i = k;
            }
        }
        if (i < 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found");
        }
        int j = up ? i - 1 : i + 1;
        if (j >= 0 && j < products.size()) {
            java.util.Collections.swap(products, i, j);
        }
        for (int k = 0; k < products.size(); k++) {
            products.get(k).setSortOrder(k);
        }
        productRepository.saveAll(products);
        return toDashboard(store);
    }

    // ---- Author: Stripe Connect --------------------------------------------

    /**
     * "Connect Stripe" / "Continue in Stripe": creates the connected account if
     * the store has none, then returns a fresh Stripe-hosted Account Link.
     * Coming back from that link does NOT mark anything ready — see {@link #syncOwn}.
     *
     * <p>Not one transaction on purpose: a newly created account id is committed
     * immediately, so a failing link request can't orphan the account on Stripe.
     */
    public String connect(User user, String country) {
        Store store = requireOwn(user);
        try {
            if (store.getStripeAccountId() == null) {
                String accountId = stripe.createAccount(user.getEmail(), country, store.getId().toString());
                store.setStripeAccountId(accountId);
                storeRepository.saveAndFlush(store);
                log.info("[StripeConnect] Created account {} for store {}", accountId, store.getId());
            }
            return stripe.createAccountLink(store.getStripeAccountId(),
                    frontendUrl + "/store?stripe=refresh",
                    frontendUrl + "/store?stripe=return");
        } catch (StripeException e) {
            log.error("[StripeConnect] Connect failed for store {}: {}", store.getId(), e.getMessage());
            throw stripeFailure(e);
        }
    }

    /** On return from Stripe: fetch the account server-side and store its real state. */
    @Transactional
    public StoreDashboardResponse syncOwn(User user) {
        Store store = requireOwn(user);
        if (store.getStripeAccountId() != null) {
            try {
                apply(store, stripe.retrieveAccount(store.getStripeAccountId()));
            } catch (StripeException e) {
                log.error("[StripeConnect] Sync failed for account {}: {}", store.getStripeAccountId(), e.getMessage());
                throw stripeFailure(e);
            }
        }
        return toDashboard(store);
    }

    /**
     * {@code account.updated} from the Connect webhook: re-fetch the account from
     * Stripe (the event payload is not trusted as the latest state) and store it.
     * Throws on a Stripe failure so the webhook returns 500 and Stripe retries.
     */
    @Transactional
    public void syncAccount(String accountId) throws StripeException {
        Optional<Store> store = storeRepository.findByStripeAccountId(accountId);
        if (store.isEmpty()) {
            log.warn("[StripeConnect] account.updated for unknown account {} — ignoring", accountId);
            return;
        }
        apply(store.get(), stripe.retrieveAccount(accountId));
    }

    private void apply(Store store, StripeConnectGateway.AccountState state) {
        store.setChargesEnabled(state.chargesEnabled());
        store.setDetailsSubmitted(state.detailsSubmitted());
        store.setRequirementsCurrentlyDue(state.currentlyDue().isEmpty() ? null : String.join(",", state.currentlyDue()));
        store.setStripeSyncedAt(LocalDateTime.now());
        storeRepository.save(store);
        log.info("[StripeConnect] Synced account {}: charges_enabled={} details_submitted={} currently_due={}",
                store.getStripeAccountId(), state.chargesEnabled(), state.detailsSubmitted(), state.currentlyDue().size());
    }

    // ---- Public ------------------------------------------------------------

    @Transactional(readOnly = true)
    public PublicStoreResponse getPublic(String slug) {
        Store store = requirePublished(slug);
        List<PublicStoreResponse.Product> products = productRepository
                .findByStoreIdAndActiveTrueOrderBySortOrderAscCreatedAtAsc(store.getId()).stream()
                .map(this::toPublicProduct)
                .toList();
        return new PublicStoreResponse(profile(store), products);
    }

    /** One ebook's page: details from the product and the finished book itself. */
    @Transactional(readOnly = true)
    public PublicProductResponse getPublicProduct(String slug, UUID productId) {
        Store store = requirePublished(slug);
        StoreProduct product = productRepository.findByIdAndStoreId(productId, store.getId())
                .filter(StoreProduct::isActive)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ebook not found"));
        Ebook ebook = ebookRepository.findById(product.getEbookId()).orElse(null);
        List<PublicProductResponse.Chapter> chapters = ebook == null ? List.of() : ebook.getChapters().stream()
                .filter(c -> c.getTitle() != null && !c.getTitle().isBlank())
                .sorted(java.util.Comparator.comparingInt(EbookChapter::getChapterNumber))
                .map(c -> new PublicProductResponse.Chapter(c.getChapterNumber(), c.getTitle().trim()))
                .toList();
        List<String> highlights = product.getHighlights() == null ? List.of()
                : product.getHighlights().lines().map(String::trim).filter(l -> !l.isEmpty()).limit(12).toList();
        return new PublicProductResponse(profile(store), product.getId(), product.getTitle(),
                ebook != null ? blankToNull(ebook.getSubtitle()) : null, product.getDescription(), highlights,
                product.getPriceCents(), product.getCurrency(), pageCount(ebook),
                ebook != null ? blankToNull(ebook.getLanguage()) : null,
                ebook != null ? ebook.getUpdatedAt() : null, coverUrl(product, ebook), chapters,
                moreFrom(store, product.getId()));
    }

    /** Other active ebooks of a store, for "More from …". */
    @Transactional(readOnly = true)
    public List<PublicStoreResponse.Product> moreFrom(Store store, UUID exceptProductId) {
        if (!store.isPublished()) {
            return List.of();
        }
        return productRepository.findByStoreIdAndActiveTrueOrderBySortOrderAscCreatedAtAsc(store.getId()).stream()
                .filter(p -> !p.getId().equals(exceptProductId))
                .map(this::toPublicProduct)
                .toList();
    }

    public StoreProfile profile(Store store) {
        String reason = unavailableReason(store);
        return new StoreProfile(store.getSlug(), store.getName(), store.getDescription(), store.getHeadline(),
                store.getAuthorName(), store.getContactEmail(), store.getInstagramUrl(), store.getTiktokUrl(),
                store.getWebsiteUrl(), StoreImageService.url(store, StoreImageService.Kind.LOGO),
                StoreImageService.url(store, StoreImageService.Kind.PHOTO), reason == null, reason);
    }

    private PublicStoreResponse.Product toPublicProduct(StoreProduct p) {
        Ebook ebook = ebookRepository.findById(p.getEbookId()).orElse(null);
        return new PublicStoreResponse.Product(p.getId(), p.getTitle(),
                ebook != null ? blankToNull(ebook.getSubtitle()) : null, p.getDescription(),
                p.getPriceCents(), p.getCurrency(), pageCount(ebook),
                ebook != null ? blankToNull(ebook.getLanguage()) : null, coverUrl(p, ebook));
    }

    static String coverUrl(StoreProduct product, Ebook ebook) {
        return "/api/public/products/" + product.getId() + "/cover?v="
                + (ebook != null ? ProductCoverService.version(ebook) : "0");
    }

    private static int pageCount(Ebook ebook) {
        return ebook == null ? 0 : Math.max(0, ebook.getActualPageCount());
    }

    private Store requirePublished(String slug) {
        return storeRepository.findBySlug(slug)
                .filter(Store::isPublished)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Store not found"));
    }

    /**
     * Why this store can't sell right now, or null when it can: it must be
     * published, its author must have active PRO, and Stripe must have enabled
     * charges on the author's account. Checkout re-runs this server-side.
     */
    String unavailableReason(Store store) {
        if (!store.isPublished()) {
            return REASON_NOT_PUBLISHED;
        }
        if (!subscriptionService.isPro(store.getOwnerId())) {
            return REASON_NOT_SELLING;
        }
        if (store.getStripeAccountId() == null || !store.isChargesEnabled()) {
            return REASON_PAYMENTS_SETUP;
        }
        return null;
    }

    // ------------------------------------------------------------------------

    private Store requireOwn(User user) {
        return storeRepository.findByOwnerId(user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "You don't have a store yet"));
    }

    private StoreDashboardResponse toDashboard(Store store) {
        List<StoreProductDTO> products = productRepository.findByStoreIdOrderBySortOrderAscCreatedAtAsc(store.getId())
                .stream().map(p -> {
                    Ebook ebook = ebookRepository.findById(p.getEbookId()).orElse(null);
                    return new StoreProductDTO(p.getId(), p.getEbookId(), p.getTitle(), p.getDescription(),
                            p.getHighlights(), p.getPriceCents(), p.getCurrency(), p.isActive(), p.getSortOrder(),
                            pageCount(ebook), coverUrl(p, ebook));
                }).toList();
        return new StoreDashboardResponse(store.getId(), store.getSlug(), store.getName(), store.getDescription(),
                store.getHeadline(), store.getAuthorName(), store.getContactEmail(), store.getInstagramUrl(),
                store.getTiktokUrl(), store.getWebsiteUrl(),
                StoreImageService.url(store, StoreImageService.Kind.LOGO),
                StoreImageService.url(store, StoreImageService.Kind.PHOTO),
                store.isPublished(), subscriptionService.isPro(store.getOwnerId()), stripeStatus(store), products);
    }

    static StripeStatusDTO stripeStatus(Store store) {
        List<String> due = store.getRequirementsCurrentlyDue() == null || store.getRequirementsCurrentlyDue().isBlank()
                ? List.of()
                : Arrays.asList(store.getRequirementsCurrentlyDue().split(","));
        StripeConnectState state = store.getStripeAccountId() == null ? StripeConnectState.NOT_CONNECTED
                : store.isChargesEnabled() ? StripeConnectState.READY
                : store.isDetailsSubmitted() && due.isEmpty() ? StripeConnectState.PENDING_VERIFICATION
                : StripeConnectState.ACTION_REQUIRED;
        return new StripeStatusDTO(state, store.isChargesEnabled(), store.isDetailsSubmitted(), due,
                store.getStripeSyncedAt());
    }

    /**
     * A network failure is worth retrying; anything else is Stripe rejecting the
     * request (e.g. Connect not enabled on the platform) — say so honestly instead
     * of "could not reach Stripe". The exact reason is in the log.
     */
    private static ResponseStatusException stripeFailure(StripeException e) {
        if (e instanceof ApiConnectionException) {
            return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not reach Stripe. Please try again.");
        }
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Stripe payments aren't available right now. Please try again later.");
    }

    private static long validPrice(Long cents) {
        if (cents == null || cents < MIN_PRICE_CENTS || cents > MAX_PRICE_CENTS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Price must be between $0.50 and $1,000");
        }
        return cents;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
