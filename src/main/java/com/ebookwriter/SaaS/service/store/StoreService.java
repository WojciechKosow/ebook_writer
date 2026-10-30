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
import java.util.Map;
import java.util.Objects;
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

    private static final java.util.regex.Pattern SLUG =
            java.util.regex.Pattern.compile("^[a-z0-9](?:[a-z0-9-]{1,38}[a-z0-9])$");

    static final String REASON_NOT_PUBLISHED = "This store isn't open yet.";
    static final String REASON_NOT_SELLING = "This store isn't accepting payments right now.";
    static final String REASON_PAYMENTS_SETUP = "The author hasn't finished setting up payments yet.";

    private final StoreRepository storeRepository;
    private final StoreProductRepository productRepository;
    private final EbookRepository ebookRepository;
    private final EbookPdfRepository pdfRepository;
    private final SubscriptionService subscriptionService;
    private final StripeConnectGateway stripe;
    private final ProductFileService fileService;
    private final com.ebookwriter.SaaS.service.ebook.EbookContentRenderer contentRenderer;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    // ---- Author: store -----------------------------------------------------

    @Transactional(readOnly = true)
    public Optional<StoreDashboardResponse> getOwn(User user) {
        return storeRepository.findByOwnerId(user.getId()).map(this::toDashboard);
    }

    /** Whether a store address can be used — for the first-run form, before "Open my store". */
    @Transactional(readOnly = true)
    public SlugStatus slugStatus(String slug) {
        if (slug == null || !SLUG.matcher(slug).matches()) {
            return new SlugStatus(false, "Use 3–40 lowercase letters, digits or hyphens.");
        }
        if (storeRepository.existsBySlug(slug)) {
            return new SlugStatus(false, "That address is taken.");
        }
        return new SlugStatus(true, null);
    }

    public record SlugStatus(boolean available, String message) {
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

    /** Brand and about you — saved as a draft until the author publishes. */
    @Transactional
    public StoreDashboardResponse update(User user, StoreUpdateRequest request) {
        Store store = requireOwn(user);
        StoreDraft draft = StoreDraft.parse(store.getDraft());
        StoreDraft.Profile p = new StoreDraft.Profile();
        p.name = request.name().trim();
        p.description = clean(request.description());
        p.headline = clean(request.headline());
        p.authorName = clean(request.authorName());
        p.contactEmail = clean(request.contactEmail());
        p.instagramUrl = clean(request.instagramUrl());
        p.tiktokUrl = clean(request.tiktokUrl());
        p.websiteUrl = clean(request.websiteUrl());
        draft.profile = p;
        return saveDraft(store, draft);
    }

    /**
     * The store page design, as a draft. Blank = back to the default (Scrivetta) style. The
     * document is opaque to the backend apart from {@link StoreThemeValidator}'s checks.
     */
    @Transactional
    public StoreDashboardResponse updateTheme(User user, String settings) {
        Store store = requireOwn(user);
        StoreDraft draft = StoreDraft.parse(store.getDraft());
        String valid = StoreThemeValidator.validate(settings);
        draft.theme = valid == null ? "" : valid;
        return saveDraft(store, draft);
    }

    /**
     * "Publish changes": apply the draft and make the store public. Opening a
     * store requires Scrivetta PRO; a published store can always publish edits.
     */
    @Transactional
    public StoreDashboardResponse publish(User user) {
        Store store = requireOwn(user);
        if (!store.isPublished() && !subscriptionService.isPro(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Publishing a store requires Scrivetta PRO");
        }
        applyDraft(store);
        store.setPublished(true);
        return toDashboard(storeRepository.save(store));
    }

    /** Take the store offline. Buyers keep their downloads; the draft is kept. */
    @Transactional
    public StoreDashboardResponse unpublish(User user) {
        Store store = requireOwn(user);
        store.setPublished(false);
        return toDashboard(storeRepository.save(store));
    }

    /** Throw away every unpublished change. */
    @Transactional
    public StoreDashboardResponse discardDraft(User user) {
        Store store = requireOwn(user);
        store.setDraft(null);
        store.setDraftUpdatedAt(null);
        return toDashboard(storeRepository.save(store));
    }

    // ---- Author: products --------------------------------------------------

    /**
     * Put a product in the store: one of the author's finished ebooks
     * ({@code ebookId}), or — without an ebook — a standalone product that
     * delivers uploaded files only. It goes live with the next publish (a
     * standalone product once it also has a file).
     */
    @Transactional
    public StoreDashboardResponse addProduct(User user, StoreProductCreateRequest request) {
        Store store = requireOwn(user);
        StoreProduct product = new StoreProduct();
        String title = blankToNull(request.title());
        String description = blankToNull(request.description());
        if (request.ebookId() != null) {
            Ebook ebook = ebookRepository.findByIdAndUserId(request.ebookId(), user.getId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ebook not found"));
            if (ebook.getStatus() != EbookStatus.COMPLETED || !pdfRepository.existsById(ebook.getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Only finished ebooks can be sold");
            }
            Optional<StoreProduct> existing = productRepository.findByStoreIdAndEbookId(store.getId(), ebook.getId());
            if (existing.isPresent() && existing.get().getRemovedAt() == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This ebook is already in your store");
            }
            // An ebook removed earlier comes back as the same product, so its buyers' purchases stay one product.
            product = existing.orElse(product);
            product.setRemovedAt(null);
            product.setEbookId(ebook.getId());
            if (title == null) {
                title = ebook.getTitle() != null && !ebook.getTitle().isBlank() ? ebook.getTitle().trim() : "Untitled ebook";
            }
            if (description == null) {
                description = blankToNull(ebook.getDescription());
            }
        } else if (title == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the product a title");
        }
        product.setStoreId(store.getId());
        product.setActive(false);
        product.setTitle(truncate(title, 200));
        product.setDescription(description);
        product.setPriceCents(validPrice(request.priceCents()));
        product.setCurrency(CURRENCY);
        product.setSortOrder(products(store).stream().mapToInt(StoreProduct::getSortOrder).max().orElse(-1) + 1);
        UUID id = productRepository.save(product).getId();

        StoreDraft draft = StoreDraft.parse(store.getDraft());
        draft.products.computeIfAbsent(id, k -> new StoreDraft.ProductChanges()).active = true;
        if (Boolean.TRUE.equals(request.featured())) {
            List<UUID> order = new java.util.ArrayList<>(effectiveOrder(products(store), draft.order).stream()
                    .map(StoreProduct::getId).filter(x -> !x.equals(id)).toList());
            order.add(0, id);
            draft.order = order;
        }
        return saveDraft(store, draft);
    }

    /** Edit a product — saved as a draft until the author publishes. Null fields are left unchanged. */
    @Transactional
    public StoreDashboardResponse updateProduct(User user, UUID productId, StoreProductUpdateRequest request) {
        Store store = requireOwn(user);
        StoreProduct product = requireOwnProduct(store, productId);
        StoreDraft draft = StoreDraft.parse(store.getDraft());
        StoreDraft.ProductChanges c = draft.products.computeIfAbsent(productId, k -> new StoreDraft.ProductChanges());
        if (request.title() != null) {
            String title = blankToNull(request.title());
            if (title == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Title can't be empty");
            }
            c.title = title;
        }
        if (request.description() != null) {
            c.description = clean(request.description());
        }
        if (request.highlights() != null) {
            c.highlights = clean(request.highlights());
        }
        if (request.priceCents() != null) {
            c.priceCents = validPrice(request.priceCents());
        }
        if (request.pageSettings() != null) {
            String valid = ProductPageValidator.validate(request.pageSettings(), chaptersWithText(ebookOf(product)));
            c.pageSettings = valid == null ? "" : valid;
        }
        if (request.active() != null) {
            if (request.active() && !fileService.isDeliverable(product)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Add a downloadable file before showing this product");
            }
            c.active = request.active();
        }
        return saveDraft(store, draft);
    }

    /** Swap a product with its neighbour (draft); the first product is the store's featured one. */
    @Transactional
    public StoreDashboardResponse moveProduct(User user, UUID productId, boolean up) {
        Store store = requireOwn(user);
        StoreDraft draft = StoreDraft.parse(store.getDraft());
        List<UUID> order = new java.util.ArrayList<>(effectiveOrder(products(store), draft.order).stream()
                .map(StoreProduct::getId).toList());
        int i = order.indexOf(productId);
        if (i < 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found");
        }
        int j = up ? i - 1 : i + 1;
        if (j >= 0 && j < order.size()) {
            java.util.Collections.swap(order, i, j);
        }
        draft.order = order;
        return saveDraft(store, draft);
    }

    /** The order of all products (draft): first = featured. Must name every product once. */
    @Transactional
    public StoreDashboardResponse reorderProducts(User user, List<UUID> productIds) {
        Store store = requireOwn(user);
        java.util.Set<UUID> current = products(store).stream().map(StoreProduct::getId)
                .collect(java.util.stream.Collectors.toSet());
        if (productIds.size() != current.size() || !current.equals(new java.util.HashSet<>(productIds))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The list must name every product once");
        }
        StoreDraft draft = StoreDraft.parse(store.getDraft());
        draft.order = List.copyOf(productIds);
        return saveDraft(store, draft);
    }

    /**
     * Remove a product from the store, right away. Buyers keep what they bought;
     * the product is never shown or sold again (an ebook can be added back).
     */
    @Transactional
    public StoreDashboardResponse removeProduct(User user, UUID productId) {
        Store store = requireOwn(user);
        StoreProduct product = requireOwnProduct(store, productId);
        product.setRemovedAt(LocalDateTime.now());
        product.setActive(false);
        productRepository.save(product);
        StoreDraft draft = StoreDraft.parse(store.getDraft());
        draft.products.remove(productId);
        fileService.current(productId).forEach(f -> draft.files.remove(f.getId()));
        if (draft.order != null) {
            draft.order = draft.order.stream().filter(id -> !id.equals(productId)).toList();
        }
        return saveDraft(store, draft);
    }

    /** An extra's name for buyers, what it's for, and whether it's an account perk — as a draft. */
    @Transactional
    public StoreDashboardResponse updateFile(User user, UUID productId, UUID fileId, StoreFileUpdateRequest request) {
        Store store = requireOwn(user);
        requireOwnProduct(store, productId);
        if (fileService.current(productId).stream().noneMatch(f -> f.getId().equals(fileId))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found");
        }
        StoreDraft draft = StoreDraft.parse(store.getDraft());
        StoreDraft.FileChanges c = draft.files.computeIfAbsent(fileId, k -> new StoreDraft.FileChanges());
        if (request.label() != null) {
            c.label = clean(request.label());
        }
        if (request.note() != null) {
            c.note = clean(request.note());
        }
        if (request.accountOnly() != null) {
            c.accountOnly = request.accountOnly();
        }
        return saveDraft(store, draft);
    }

    // ---- The draft ---------------------------------------------------------

    private List<StoreProduct> products(Store store) {
        return productRepository.findByStoreIdAndRemovedAtIsNullOrderBySortOrderAscCreatedAtAsc(store.getId());
    }

    private StoreProduct requireOwnProduct(Store store, UUID productId) {
        return productRepository.findByIdAndStoreId(productId, store.getId())
                .filter(p -> p.getRemovedAt() == null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
    }

    private StoreDashboardResponse saveDraft(Store store, StoreDraft draft) {
        List<StoreProduct> all = products(store);
        prune(store, draft, all, currentFiles(all));
        String json = draft.toJson();
        store.setDraft(json);
        store.setDraftUpdatedAt(json == null ? null : LocalDateTime.now());
        return toDashboard(storeRepository.save(store));
    }

    private Map<UUID, ProductFile> currentFiles(List<StoreProduct> products) {
        Map<UUID, ProductFile> out = new java.util.HashMap<>();
        fileService.currentByProduct(products.stream().map(StoreProduct::getId).toList())
                .values().forEach(list -> list.forEach(f -> out.put(f.getId(), f)));
        return out;
    }

    /** Drop whatever matches the live store again, or no longer exists, so the draft holds real changes only. */
    private static void prune(Store store, StoreDraft d, List<StoreProduct> products, Map<UUID, ProductFile> files) {
        if (d.profile != null && profileChanges(store, d.profile).isEmpty()) {
            d.profile = null;
        }
        if (d.theme != null && Objects.equals(blankToNull(d.theme), blankToNull(store.getThemeSettings()))) {
            d.theme = null;
        }
        Map<UUID, StoreProduct> byId = new java.util.HashMap<>();
        products.forEach(p -> byId.put(p.getId(), p));
        d.products.entrySet().removeIf(e -> {
            StoreProduct p = byId.get(e.getKey());
            if (p == null) {
                return true;
            }
            StoreDraft.ProductChanges c = e.getValue();
            if (c.title != null && c.title.equals(p.getTitle())) {
                c.title = null;
            }
            if (c.description != null && Objects.equals(blankToNull(c.description), p.getDescription())) {
                c.description = null;
            }
            if (c.highlights != null && Objects.equals(blankToNull(c.highlights), p.getHighlights())) {
                c.highlights = null;
            }
            if (c.pageSettings != null && Objects.equals(blankToNull(c.pageSettings), p.getPageSettings())) {
                c.pageSettings = null;
            }
            if (c.priceCents != null && c.priceCents == p.getPriceCents()) {
                c.priceCents = null;
            }
            if (c.active != null && c.active == p.isActive()) {
                c.active = null;
            }
            return c.isEmpty();
        });
        d.files.entrySet().removeIf(e -> {
            ProductFile f = files.get(e.getKey());
            if (f == null) {
                return true;
            }
            StoreDraft.FileChanges c = e.getValue();
            if (c.label != null && Objects.equals(blankToNull(c.label), f.getLabel())) {
                c.label = null;
            }
            if (c.note != null && Objects.equals(blankToNull(c.note), f.getNote())) {
                c.note = null;
            }
            if (c.accountOnly != null && c.accountOnly == f.isAccountOnly()) {
                c.accountOnly = null;
            }
            return c.isEmpty();
        });
        if (d.order != null) {
            List<UUID> next = effectiveOrder(products, d.order).stream().map(StoreProduct::getId).toList();
            d.order = next.equals(products.stream().map(StoreProduct::getId).toList()) ? null : next;
        }
    }

    /** The products in the draft's order; ones the draft doesn't name keep their live order, after it. */
    private static List<StoreProduct> effectiveOrder(List<StoreProduct> live, List<UUID> order) {
        if (order == null) {
            return live;
        }
        List<StoreProduct> out = new java.util.ArrayList<>();
        for (UUID id : order) {
            live.stream().filter(p -> p.getId().equals(id) && !out.contains(p)).findFirst().ifPresent(out::add);
        }
        live.stream().filter(p -> !out.contains(p)).forEach(out::add);
        return out;
    }

    /** "Publish changes": copy the draft onto the live store. */
    private void applyDraft(Store store) {
        StoreDraft d = StoreDraft.parse(store.getDraft());
        List<StoreProduct> all = products(store);
        Map<UUID, ProductFile> files = currentFiles(all);
        prune(store, d, all, files);
        StoreDraft rest = new StoreDraft();

        if (d.profile != null) {
            StoreDraft.Profile p = d.profile;
            if (blankToNull(p.name) != null) {
                store.setName(p.name.trim());
            }
            store.setDescription(blankToNull(p.description));
            store.setHeadline(blankToNull(p.headline));
            store.setAuthorName(blankToNull(p.authorName));
            store.setContactEmail(blankToNull(p.contactEmail));
            store.setInstagramUrl(blankToNull(p.instagramUrl));
            store.setTiktokUrl(blankToNull(p.tiktokUrl));
            store.setWebsiteUrl(blankToNull(p.websiteUrl));
        }
        if (d.theme != null) {
            store.setThemeSettings(blankToNull(d.theme));
        }
        for (StoreProduct p : all) {
            StoreDraft.ProductChanges c = d.products.get(p.getId());
            if (c == null) {
                continue;
            }
            if (c.title != null) {
                p.setTitle(c.title);
            }
            if (c.description != null) {
                p.setDescription(blankToNull(c.description));
            }
            if (c.highlights != null) {
                p.setHighlights(blankToNull(c.highlights));
            }
            if (c.priceCents != null) {
                p.setPriceCents(c.priceCents);
            }
            if (c.pageSettings != null) {
                p.setPageSettings(blankToNull(c.pageSettings));
            }
            if (c.active != null) {
                if (c.active && !fileService.isDeliverable(p)) {
                    // Nothing to deliver yet: it goes live with the publish after its first file.
                    rest.products.computeIfAbsent(p.getId(), k -> new StoreDraft.ProductChanges()).active = true;
                } else {
                    p.setActive(c.active);
                }
            }
        }
        if (d.order != null) {
            List<StoreProduct> ordered = effectiveOrder(all, d.order);
            for (int i = 0; i < ordered.size(); i++) {
                ordered.get(i).setSortOrder(i);
            }
        }
        productRepository.saveAll(all);
        for (Map.Entry<UUID, StoreDraft.FileChanges> e : d.files.entrySet()) {
            ProductFile f = files.get(e.getKey());
            StoreDraft.FileChanges c = e.getValue();
            if (c.label != null) {
                f.setLabel(blankToNull(c.label));
            }
            if (c.note != null) {
                f.setNote(blankToNull(c.note));
            }
            if (c.accountOnly != null) {
                f.setAccountOnly(c.accountOnly);
            }
            fileService.save(f);
        }
        String json = rest.toJson();
        store.setDraft(json);
        store.setDraftUpdatedAt(json == null ? null : LocalDateTime.now());
    }

    private static List<PendingChange> profileChanges(Store store, StoreDraft.Profile p) {
        List<PendingChange> out = new java.util.ArrayList<>();
        diff(out, "profile", null, null, "name", store.getName(), p.name);
        diff(out, "profile", null, null, "headline", store.getHeadline(), p.headline);
        diff(out, "profile", null, null, "authorName", store.getAuthorName(), p.authorName);
        diff(out, "profile", null, null, "description", store.getDescription(), p.description);
        diff(out, "profile", null, null, "contactEmail", store.getContactEmail(), p.contactEmail);
        diff(out, "profile", null, null, "instagramUrl", store.getInstagramUrl(), p.instagramUrl);
        diff(out, "profile", null, null, "tiktokUrl", store.getTiktokUrl(), p.tiktokUrl);
        diff(out, "profile", null, null, "websiteUrl", store.getWebsiteUrl(), p.websiteUrl);
        return out;
    }

    private static void diff(List<PendingChange> out, String kind, UUID productId, UUID fileId, String field,
                             String live, String draft) {
        if (draft != null && !Objects.equals(blankToNull(live), blankToNull(draft))) {
            out.add(new PendingChange(kind, productId, fileId, field, blankToNull(live), blankToNull(draft)));
        }
    }

    /** What "Publish changes" would apply, for the author. A product without a file isn't counted until it has one. */
    private List<PendingChange> pending(Store store, StoreDraft d, List<StoreProduct> all, Map<UUID, ProductFile> files) {
        List<PendingChange> out = new java.util.ArrayList<>();
        if (d.profile != null) {
            out.addAll(profileChanges(store, d.profile));
        }
        diff(out, "theme", null, null, "themeSettings", store.getThemeSettings(), d.theme);
        if (d.order != null) {
            out.add(new PendingChange("order", null, null, "order",
                    all.stream().map(p -> p.getId().toString()).collect(java.util.stream.Collectors.joining(",")),
                    String.join(",", d.order.stream().map(UUID::toString).toList())));
        }
        for (StoreProduct p : all) {
            StoreDraft.ProductChanges c = d.products.get(p.getId());
            if (c == null) {
                continue;
            }
            UUID id = p.getId();
            diff(out, "product", id, null, "title", p.getTitle(), c.title);
            diff(out, "product", id, null, "priceCents", String.valueOf(p.getPriceCents()),
                    c.priceCents == null ? null : String.valueOf(c.priceCents));
            if (c.active != null && (!c.active || fileService.isDeliverable(p))) {
                out.add(new PendingChange("product", id, null, "active", String.valueOf(p.isActive()), String.valueOf(c.active)));
            }
            diff(out, "product", id, null, "description", p.getDescription(), c.description);
            diff(out, "product", id, null, "highlights", p.getHighlights(), c.highlights);
            diff(out, "product", id, null, "pageSettings", p.getPageSettings(), c.pageSettings);
        }
        for (Map.Entry<UUID, StoreDraft.FileChanges> e : d.files.entrySet()) {
            ProductFile f = files.get(e.getKey());
            if (f == null) {
                continue;
            }
            StoreDraft.FileChanges c = e.getValue();
            diff(out, "file", f.getProductId(), f.getId(), "label", f.getLabel(), c.label);
            diff(out, "file", f.getProductId(), f.getId(), "note", f.getNote(), c.note);
            if (c.accountOnly != null) {
                out.add(new PendingChange("file", f.getProductId(), f.getId(), "accountOnly",
                        String.valueOf(f.isAccountOnly()), String.valueOf(c.accountOnly)));
            }
        }
        return out;
    }

    /** A draft text value: "" stands for "cleared". */
    private static String clean(String s) {
        return s == null ? "" : s.trim();
    }

    /** The draft's value over the live one. */
    private static String over(String draft, String live) {
        return draft == null ? live : blankToNull(draft);
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
     * "Disconnect Stripe": forget the store's connected account so the author can
     * connect a different one (e.g. a live account after test mode). Only our link
     * is removed — the account stays the author's on Stripe, so no Stripe call is
     * made and it works even when the old account no longer exists for our key.
     * Orders already in Checkout keep their own account id, so their webhooks
     * still fulfil them.
     */
    @Transactional
    public StoreDashboardResponse disconnectStripe(User user) {
        Store store = requireOwn(user);
        if (store.getStripeAccountId() != null) {
            log.info("[StripeConnect] Store {} disconnected account {}", store.getId(), store.getStripeAccountId());
            store.setStripeAccountId(null);
            store.setChargesEnabled(false);
            store.setDetailsSubmitted(false);
            store.setRequirementsCurrentlyDue(null);
            store.setStripeSyncedAt(null);
            storeRepository.save(store);
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
        return new PublicStoreResponse(profile(store), products, store.getThemeSettings(), featuredChapters(store));
    }

    /** One ebook's page: details from the product and the finished book itself. */
    @Transactional(readOnly = true)
    public PublicProductResponse getPublicProduct(String slug, UUID productId) {
        Store store = requirePublished(slug);
        StoreProduct product = productRepository.findByIdAndStoreId(productId, store.getId())
                .filter(StoreProduct::isActive)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ebook not found"));
        Ebook ebook = ebookOf(product);
        List<PublicProductResponse.Chapter> chapters = ebook == null ? List.of() : chapters(ebook);
        List<String> highlights = product.getHighlights() == null ? List.of()
                : product.getHighlights().lines().map(String::trim).filter(l -> !l.isEmpty()).limit(12).toList();
        return new PublicProductResponse(profile(store), product.getId(), product.getTitle(),
                ebook != null ? blankToNull(ebook.getSubtitle()) : null, product.getDescription(), highlights,
                product.getPriceCents(), product.getCurrency(), pageCount(ebook),
                ebook != null ? blankToNull(ebook.getLanguage()) : null,
                ebook != null ? ebook.getUpdatedAt() : null, coverUrl(product, ebook), chapters,
                fileService.deliverables(product).stream().map(f -> f.format().name()).distinct().toList(),
                moreFrom(store, product.getId()), store.getThemeSettings(), product.getPageSettings(),
                sampleRefs(product, ebook),
                fileService.deliverables(product).stream().map(f -> toFileDTO(f).withoutFilename()).toList(),
                DownloadGrantService.MAX_DOWNLOADS, DownloadGrantService.VALIDITY_DAYS);
    }

    /**
     * A free-preview chapter of a product on sale. Only chapters the author put in
     * the free preview are served; anything else is the same 404.
     */
    @Transactional(readOnly = true)
    public SampleChapterResponse publicSample(String slug, UUID productId, int number) {
        Store store = requirePublished(slug);
        StoreProduct product = productRepository.findByIdAndStoreId(productId, store.getId())
                .filter(StoreProduct::isActive)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Preview not found"));
        if (!ProductPageValidator.sampleChapters(product.getPageSettings()).contains(number)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Preview not found");
        }
        return sample(ebookOf(product), number);
    }

    /** Any chapter of the author's own product — for the page editor, before it is put in the preview. */
    @Transactional(readOnly = true)
    public SampleChapterResponse ownSample(User user, UUID productId, int number) {
        Store store = requireOwn(user);
        StoreProduct product = productRepository.findByIdAndStoreId(productId, store.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
        return sample(ebookOf(product), number);
    }

    private SampleChapterResponse sample(Ebook ebook, int number) {
        EbookChapter chapter = ebook == null ? null : ebook.getChapters().stream()
                .filter(c -> c.getChapterNumber() == number && c.getContent() != null && !c.getContent().isBlank())
                .findFirst().orElse(null);
        if (chapter == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Preview not found");
        }
        // The book's own HTML, reduced to safe text markup: no scripts, no styles, no
        // images (those live in the private bucket and stay part of the paid book).
        String html = org.jsoup.Jsoup.clean(contentRenderer.toHtml(chapter.getContent()),
                org.jsoup.safety.Safelist.relaxed().removeTags("img"));
        return new SampleChapterResponse(chapter.getChapterNumber(),
                chapter.getTitle() != null ? chapter.getTitle().trim() : "Chapter " + number, html);
    }

    private static List<PublicProductResponse.Chapter> sampleRefs(StoreProduct product, Ebook ebook) {
        List<Integer> picked = ProductPageValidator.sampleChapters(product.getPageSettings());
        if (ebook == null || picked.isEmpty()) {
            return List.of();
        }
        return chapters(ebook).stream().filter(c -> picked.contains(c.number())).toList();
    }

    static java.util.Set<Integer> chaptersWithText(Ebook ebook) {
        if (ebook == null) {
            return java.util.Set.of();
        }
        java.util.Set<Integer> out = new java.util.HashSet<>();
        for (EbookChapter c : ebook.getChapters()) {
            if (c.getContent() != null && !c.getContent().isBlank()) {
                out.add(c.getChapterNumber());
            }
        }
        return out;
    }

    /** Chapters of the store's first active ebook (the one designs feature). */
    private List<PublicProductResponse.Chapter> featuredChapters(Store store) {
        return productRepository.findByStoreIdAndActiveTrueOrderBySortOrderAscCreatedAtAsc(store.getId()).stream()
                .findFirst()
                .map(this::ebookOf)
                .filter(java.util.Objects::nonNull)
                .map(StoreService::chapters)
                .orElse(List.of());
    }

    private static List<PublicProductResponse.Chapter> chapters(Ebook ebook) {
        return ebook.getChapters().stream()
                .filter(c -> c.getTitle() != null && !c.getTitle().isBlank())
                .sorted(java.util.Comparator.comparingInt(EbookChapter::getChapterNumber))
                .map(c -> new PublicProductResponse.Chapter(c.getChapterNumber(), c.getTitle().trim()))
                .toList();
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
        Ebook ebook = ebookOf(p);
        return new PublicStoreResponse.Product(p.getId(), p.getTitle(),
                ebook != null ? blankToNull(ebook.getSubtitle()) : null, p.getDescription(),
                p.getPriceCents(), p.getCurrency(), pageCount(ebook),
                ebook != null ? blankToNull(ebook.getLanguage()) : null, coverUrl(p, ebook));
    }

    /**
     * Relative public URL of a product's cover: its own uploaded cover, else the
     * ebook's first page; null for a standalone product without a cover.
     */
    static String coverUrl(StoreProduct product, Ebook ebook) {
        if (product.getCoverKey() != null) {
            return "/api/public/products/" + product.getId() + "/cover?v="
                    + Integer.toHexString(product.getCoverKey().hashCode());
        }
        if (product.getEbookId() == null) {
            return null;
        }
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

    /** The author's view: the draft over the live store, plus what differs. */
    private StoreDashboardResponse toDashboard(Store store) {
        StoreDraft d = StoreDraft.parse(store.getDraft());
        List<StoreProduct> all = products(store);
        Map<UUID, List<ProductFile>> files = fileService.currentByProduct(all.stream().map(StoreProduct::getId).toList());
        Map<UUID, ProductFile> filesById = new java.util.HashMap<>();
        files.values().forEach(list -> list.forEach(f -> filesById.put(f.getId(), f)));
        List<StoreProductDTO> products = effectiveOrder(all, d.order).stream().map(p -> {
                    Ebook ebook = ebookOf(p);
                    StoreDraft.ProductChanges c = d.products.getOrDefault(p.getId(), new StoreDraft.ProductChanges());
                    List<ProductFileDTO> own = files.getOrDefault(p.getId(), List.of()).stream()
                            .map(f -> toFileDTO(f, d.files.get(f.getId()))).toList();
                    boolean hasPdf = p.getEbookId() != null && pdfRepository.existsById(p.getEbookId());
                    return new StoreProductDTO(p.getId(), p.getEbookId(), c.title != null ? c.title : p.getTitle(),
                            over(c.description, p.getDescription()), over(c.highlights, p.getHighlights()),
                            c.priceCents != null ? c.priceCents : p.getPriceCents(), p.getCurrency(),
                            c.active != null ? c.active : p.isActive(), p.getSortOrder(),
                            pageCount(ebook), coverUrl(p, ebook), p.getCoverKey() != null, own,
                            hasPdf || !own.isEmpty(), over(c.pageSettings, p.getPageSettings()), chapterRefs(ebook),
                            ebook != null ? blankToNull(ebook.getSubtitle()) : null,
                            ebook != null ? blankToNull(ebook.getLanguage()) : null,
                            p.isActive(), p.getPriceCents());
                }).toList();
        StoreDraft.Profile p = d.profile != null ? d.profile : new StoreDraft.Profile();
        List<PublicProductResponse.Chapter> featured = products.stream()
                .filter(StoreProductDTO::active).findFirst()
                .flatMap(x -> all.stream().filter(s -> s.getId().equals(x.id())).findFirst())
                .map(this::ebookOf).map(StoreService::chapters).orElse(List.of());
        return new StoreDashboardResponse(store.getId(), store.getSlug(),
                p.name != null && !p.name.isBlank() ? p.name.trim() : store.getName(),
                over(p.description, store.getDescription()), over(p.headline, store.getHeadline()),
                over(p.authorName, store.getAuthorName()), over(p.contactEmail, store.getContactEmail()),
                over(p.instagramUrl, store.getInstagramUrl()), over(p.tiktokUrl, store.getTiktokUrl()),
                over(p.websiteUrl, store.getWebsiteUrl()),
                StoreImageService.url(store, StoreImageService.Kind.LOGO),
                StoreImageService.url(store, StoreImageService.Kind.PHOTO),
                store.isPublished(), subscriptionService.isPro(store.getOwnerId()), stripeStatus(store), products,
                over(d.theme, store.getThemeSettings()), featured, pending(store, d, all, filesById),
                store.getDraftUpdatedAt());
    }

    /** An uploaded file with the draft's name, note and perk applied. */
    private static ProductFileDTO toFileDTO(ProductFile f, StoreDraft.FileChanges c) {
        if (c == null) {
            return toFileDTO(f);
        }
        return new ProductFileDTO(f.getId().toString(), f.getOriginalFilename(), f.getFormat(), f.getSizeBytes(),
                f.getVersion(), f.getCreatedAt(), over(c.label, f.getLabel()), over(c.note, f.getNote()),
                c.accountOnly != null ? c.accountOnly : f.isAccountOnly(), null, false);
    }

    private static List<ChapterRef> chapterRefs(Ebook ebook) {
        if (ebook == null) {
            return List.of();
        }
        return ebook.getChapters().stream()
                .filter(c -> c.getTitle() != null && !c.getTitle().isBlank())
                .sorted(java.util.Comparator.comparingInt(EbookChapter::getChapterNumber))
                .map(c -> new ChapterRef(c.getChapterNumber(), c.getTitle().trim(),
                        c.getContent() != null && !c.getContent().isBlank()))
                .toList();
    }

    static ProductFileDTO toFileDTO(ProductFile f) {
        return new ProductFileDTO(f.getId().toString(), f.getOriginalFilename(), f.getFormat(), f.getSizeBytes(),
                f.getVersion(), f.getCreatedAt(), f.getLabel(), f.getNote(), f.isAccountOnly(), null, false);
    }

    static ProductFileDTO toFileDTO(ProductFileService.DeliverableFile f) {
        return new ProductFileDTO(f.id(), f.filename(), f.format(), f.sizeBytes(), f.version(), f.updatedAt(),
                f.label(), f.note(), f.accountOnly(), null, false);
    }

    /** The product's ebook, or null for a standalone product (or a missing ebook). */
    Ebook ebookOf(StoreProduct product) {
        return product.getEbookId() == null ? null : ebookRepository.findById(product.getEbookId()).orElse(null);
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
