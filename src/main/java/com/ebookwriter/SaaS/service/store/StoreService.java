package com.ebookwriter.SaaS.service.store;

import com.ebookwriter.SaaS.dto.store.*;
import com.ebookwriter.SaaS.entity.*;
import com.ebookwriter.SaaS.repository.EbookPdfRepository;
import com.ebookwriter.SaaS.repository.EbookRepository;
import com.ebookwriter.SaaS.repository.StoreProductRepository;
import com.ebookwriter.SaaS.repository.StoreRepository;
import com.ebookwriter.SaaS.request.*;
import com.ebookwriter.SaaS.service.payment.SubscriptionService;
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
        product.setDescription(blankToNull(request.description()));
        product.setPriceCents(validPrice(request.priceCents()));
        product.setCurrency(CURRENCY);
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
        if (request.priceCents() != null) {
            product.setPriceCents(validPrice(request.priceCents()));
        }
        if (request.active() != null) {
            product.setActive(request.active());
        }
        productRepository.save(product);
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
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not reach Stripe. Please try again.");
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
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not reach Stripe. Please try again.");
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
        Store store = storeRepository.findBySlug(slug)
                .filter(Store::isPublished)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Store not found"));
        String reason = unavailableReason(store);
        List<PublicStoreResponse.Product> products = productRepository
                .findByStoreIdAndActiveTrueOrderByCreatedAtAsc(store.getId()).stream()
                .map(p -> new PublicStoreResponse.Product(p.getId(), p.getTitle(), p.getDescription(),
                        p.getPriceCents(), p.getCurrency()))
                .toList();
        return new PublicStoreResponse(store.getSlug(), store.getName(), store.getDescription(),
                reason == null, reason, products);
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
        List<StoreProductDTO> products = productRepository.findByStoreIdOrderByCreatedAtAsc(store.getId())
                .stream().map(StoreProductDTO::from).toList();
        return new StoreDashboardResponse(store.getId(), store.getSlug(), store.getName(), store.getDescription(),
                store.isPublished(), subscriptionService.isPro(store.getOwnerId()), stripeStatus(store), products);
    }

    static StripeStatusDTO stripeStatus(Store store) {
        StripeConnectState state = store.getStripeAccountId() == null ? StripeConnectState.NOT_CONNECTED
                : store.isChargesEnabled() ? StripeConnectState.READY
                : StripeConnectState.ACTION_REQUIRED;
        List<String> due = store.getRequirementsCurrentlyDue() == null || store.getRequirementsCurrentlyDue().isBlank()
                ? List.of()
                : Arrays.asList(store.getRequirementsCurrentlyDue().split(","));
        return new StripeStatusDTO(state, store.isChargesEnabled(), store.isDetailsSubmitted(), due,
                store.getStripeSyncedAt());
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
