package com.ebookwriter.SaaS.service.store;

import com.ebookwriter.SaaS.entity.*;
import com.ebookwriter.SaaS.repository.*;
import com.ebookwriter.SaaS.service.MailService;
import com.stripe.param.checkout.SessionCreateParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Creator-store payments end to end through the real HTTP API, services and
 * database (H2): checkout, the signed Connect webhook, orders, download grants
 * and refunds. Only Stripe's network calls ({@link StripeConnectGateway}) and the
 * mail transport are replaced. Webhook payloads are signed exactly like Stripe
 * signs them, so {@code Webhook.constructEvent} verifies them for real.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "stripe.connect.webhook-secret=" + CreatorStorePaymentsIntegrationTest.SECRET,
        "stripe.connect.v2-events-secret=" + CreatorStorePaymentsIntegrationTest.V2_SECRET})
class CreatorStorePaymentsIntegrationTest {

    static final String SECRET = "whsec_connect_test_secret";
    static final String V2_SECRET = "whsec_connect_v2_events_secret";
    static final long PRICE = 1299;
    static final byte[] PDF = "%PDF-1.4 test ebook".getBytes(StandardCharsets.UTF_8);

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired UserSubscriptionRepository subscriptionRepository;
    @Autowired EbookRepository ebookRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired StoreProductRepository productRepository;
    @Autowired StoreOrderRepository orderRepository;
    @Autowired DownloadGrantRepository grantRepository;
    @Autowired StripeWebhookEventRepository webhookEventRepository;

    @MockitoBean StripeConnectGateway stripe;
    @MockitoBean MailService mailService;
    /** H2 (PostgreSQL mode) can't create the ebook_pdfs BLOB table, so PDF storage is stubbed. */
    @MockitoBean EbookPdfRepository pdfRepository;

    MockMvc mvc;
    User author;
    Store store;
    StoreProduct product;
    String account;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        author = userRepository.save(User.builder().email("author-" + UUID.randomUUID() + "@example.com")
                .displayName("Author").password("x").enabled(true).build());
        setPro(author, "active");

        Ebook ebook = ebookRepository.save(Ebook.builder().user(author).topic("Sourdough")
                .title("Sourdough at Home").status(EbookStatus.COMPLETED).build());
        when(pdfRepository.existsById(ebook.getId())).thenReturn(true);
        when(pdfRepository.findById(ebook.getId())).thenReturn(java.util.Optional.of(new EbookPdf(ebook.getId(), PDF)));

        account = "acct_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        store = new Store();
        store.setOwnerId(author.getId());
        store.setSlug("s" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        store.setName("Author's Kitchen");
        store.setPublished(true);
        store.setStripeAccountId(account);
        store.setChargesEnabled(true);
        store.setDetailsSubmitted(true);
        store = storeRepository.save(store);

        product = new StoreProduct();
        product.setStoreId(store.getId());
        product.setEbookId(ebook.getId());
        product.setTitle("Sourdough at Home");
        product.setPriceCents(PRICE);
        product.setCurrency("usd");
        product = productRepository.save(product);

        when(stripe.createCheckoutSession(anyString(), any())).thenAnswer(inv ->
                new StripeConnectGateway.CreatedSession("cs_test_" + UUID.randomUUID(), "https://checkout.stripe.com/c/pay/test"));
    }

    // ---- Successful purchase ----------------------------------------------------

    @Test
    void successfulPurchaseMarksPaidGrantsDownloadAndEmailsTheBuyer() throws Exception {
        StoreOrder order = checkout();
        assertEquals(StoreOrderStatus.PENDING, order.getStatus());
        mvc.perform(get("/api/public/orders/" + order.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));

        // Checkout Session: direct charge on the author's account, our price, cards
        // only, order id in metadata + client_reference_id, consent required, no fee.
        ArgumentCaptor<SessionCreateParams> params = ArgumentCaptor.forClass(SessionCreateParams.class);
        verify(stripe).createCheckoutSession(eq(account), params.capture());
        SessionCreateParams p = params.getValue();
        assertEquals(PRICE, p.getLineItems().get(0).getPriceData().getUnitAmount());
        assertEquals("usd", p.getLineItems().get(0).getPriceData().getCurrency());
        assertEquals(List.of(SessionCreateParams.PaymentMethodType.CARD), p.getPaymentMethodTypes());
        assertEquals(order.getId().toString(), p.getMetadata().get("order_id"));
        assertEquals(order.getId().toString(), p.getClientReferenceId());
        assertEquals(SessionCreateParams.ConsentCollection.TermsOfService.REQUIRED,
                p.getConsentCollection().getTermsOfService());
        assertNotNull(p.getCustomText().getTermsOfServiceAcceptance(), "consent custom_text set");
        assertNull(p.getPaymentIntentData().getApplicationFeeAmount(), "no commission, ever");
        assertNull(p.getPaymentIntentData().getTransferData(), "direct charge, not destination");

        // No file before the webhook confirms payment.
        assertTrue(grantRepository.findByOrderId(order.getId()).isEmpty());

        webhook(completed(evt(), account, order, "paid")).andExpect(status().isOk());

        StoreOrder paid = orderRepository.findById(order.getId()).orElseThrow();
        assertEquals(StoreOrderStatus.PAID, paid.getStatus());
        assertEquals("buyer@example.com", paid.getBuyerEmail());
        assertEquals("pi_" + order.getId().toString().substring(0, 8), paid.getPaymentIntentId());
        assertNotNull(paid.getConsentAcceptedAt(), "consent timestamp stored");
        mvc.perform(get("/api/public/orders/" + order.getId())).andExpect(jsonPath("$.status").value("PAID"));

        String token = sentToken();
        DownloadGrant grant = grantRepository.findByOrderId(order.getId()).orElseThrow();
        assertNotEquals(token, grant.getTokenHash(), "only the hash is stored");
        assertEquals(DownloadGrantService.hash(token), grant.getTokenHash());
        assertEquals(5, grant.getMaxDownloads());
        assertTrue(grant.getExpiresAt().isAfter(LocalDateTime.now().plusDays(29)));

        mvc.perform(get("/api/public/downloads/" + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.downloadsRemaining").value(5));
        byte[] file = mvc.perform(get("/api/public/downloads/" + token + "/file"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(PDF, file);
        assertEquals(1, grantRepository.findByOrderId(order.getId()).orElseThrow().getDownloadsUsed());
    }

    @Test
    void unpaidCompletedSessionDeliversNothing() throws Exception {
        StoreOrder order = checkout();
        webhook(completed(evt(), account, order, "unpaid")).andExpect(status().isOk());
        assertEquals(StoreOrderStatus.PENDING, orderRepository.findById(order.getId()).orElseThrow().getStatus());
        assertTrue(grantRepository.findByOrderId(order.getId()).isEmpty());
        verifyNoInteractions(mailService);
    }

    // ---- Expired session ----------------------------------------------------------

    @Test
    void expiredSessionMarksOrderExpired() throws Exception {
        StoreOrder order = checkout();
        webhook(sessionEvent(evt(), "checkout.session.expired", account, order, "unpaid")).andExpect(status().isOk());
        assertEquals(StoreOrderStatus.EXPIRED, orderRepository.findById(order.getId()).orElseThrow().getStatus());
        assertTrue(grantRepository.findByOrderId(order.getId()).isEmpty());
        verifyNoInteractions(mailService);
    }

    // ---- Duplicate webhook --------------------------------------------------------

    @Test
    void duplicateWebhookIsANoOp() throws Exception {
        StoreOrder order = checkout();
        String payload = completed(evt(), account, order, "paid");
        webhook(payload).andExpect(status().isOk());
        String hashAfterFirst = grantRepository.findByOrderId(order.getId()).orElseThrow().getTokenHash();

        webhook(payload).andExpect(status().isOk());

        assertEquals(hashAfterFirst, grantRepository.findByOrderId(order.getId()).orElseThrow().getTokenHash());
        verify(mailService, times(1)).sendStorePurchaseEmail(anyString(), any(), any(), anyString(), anyBoolean());
    }

    // ---- Mismatched account -------------------------------------------------------

    @Test
    void webhookFromAnotherConnectedAccountIsIgnored() throws Exception {
        StoreOrder order = checkout();
        webhook(completed(evt(), "acct_attacker", order, "paid")).andExpect(status().isOk());
        assertEquals(StoreOrderStatus.PENDING, orderRepository.findById(order.getId()).orElseThrow().getStatus());
        assertTrue(grantRepository.findByOrderId(order.getId()).isEmpty());
        verifyNoInteractions(mailService);

        webhook(sessionEvent(evt(), "checkout.session.expired", "acct_attacker", order, "unpaid"));
        assertEquals(StoreOrderStatus.PENDING, orderRepository.findById(order.getId()).orElseThrow().getStatus());
    }

    @Test
    void forgedSignatureIsRejected() throws Exception {
        StoreOrder order = checkout();
        mvc.perform(post("/api/stripe/connect/webhook").contentType(MediaType.APPLICATION_JSON)
                        .content(completed(evt(), account, order, "paid"))
                        .header("Stripe-Signature", "t=1,v1=deadbeef"))
                .andExpect(status().isBadRequest());
        assertEquals(StoreOrderStatus.PENDING, orderRepository.findById(order.getId()).orElseThrow().getStatus());
    }

    @Test
    void liveModeEventIsIgnoredWhileLiveIsDisabled() throws Exception {
        StoreOrder order = checkout();
        webhook(completed(evt(), account, order, "paid").replace("\"livemode\":false", "\"livemode\":true"))
                .andExpect(status().isOk());
        assertEquals(StoreOrderStatus.PENDING, orderRepository.findById(order.getId()).orElseThrow().getStatus());
    }

    // ---- Forged price / product ---------------------------------------------------

    @Test
    void clientCannotChooseThePrice() throws Exception {
        mvc.perform(post("/api/public/checkout").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + product.getId() + "\",\"priceCents\":1,\"amount\":1,"
                                + "\"currency\":\"jpy\",\"status\":\"PAID\",\"stripeAccountId\":\"acct_attacker\"}"))
                .andExpect(status().isOk());
        ArgumentCaptor<SessionCreateParams> params = ArgumentCaptor.forClass(SessionCreateParams.class);
        verify(stripe).createCheckoutSession(eq(account), params.capture());
        assertEquals(PRICE, params.getValue().getLineItems().get(0).getPriceData().getUnitAmount());
        assertEquals("usd", params.getValue().getLineItems().get(0).getPriceData().getCurrency());
        StoreOrder order = latestOrder();
        assertEquals(PRICE, order.getAmount());
        assertEquals(StoreOrderStatus.PENDING, order.getStatus());
        assertEquals(account, order.getStripeAccountId());
    }

    @Test
    void unknownInactiveOrUnpublishedProductsCannotBeBought() throws Exception {
        long before = orderRepository.count();
        checkoutRequest(UUID.randomUUID()).andExpect(status().isNotFound());

        product.setActive(false);
        productRepository.save(product);
        checkoutRequest(product.getId()).andExpect(status().isNotFound());

        product.setActive(true);
        productRepository.save(product);
        store.setPublished(false);
        storeRepository.save(store);
        checkoutRequest(product.getId()).andExpect(status().isNotFound());

        assertEquals(before, orderRepository.count());
        verify(stripe, never()).createCheckoutSession(anyString(), any());
    }

    @Test
    void webhookAmountThatDiffersFromTheOrderIsNotFulfilled() throws Exception {
        StoreOrder order = checkout();
        webhook(completed(evt(), account, order, "paid").replace("\"amount_total\":" + PRICE, "\"amount_total\":1"))
                .andExpect(status().isOk());
        assertEquals(StoreOrderStatus.PENDING, orderRepository.findById(order.getId()).orElseThrow().getStatus());
    }

    // ---- Seller eligibility -------------------------------------------------------

    @Test
    void checkoutRefusedWhenAuthorsProHasExpired() throws Exception {
        setPro(author, "canceled");
        long before = orderRepository.count();
        checkoutRequest(product.getId()).andExpect(status().isConflict());
        mvc.perform(get("/api/public/stores/" + store.getSlug()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.store.purchasable").value(false));
        assertEquals(before, orderRepository.count());
        verify(stripe, never()).createCheckoutSession(anyString(), any());
    }

    @Test
    void checkoutRefusedWhenChargesAreNotEnabled() throws Exception {
        store.setChargesEnabled(false);
        storeRepository.save(store);
        long before = orderRepository.count();
        checkoutRequest(product.getId()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(StoreService.REASON_PAYMENTS_SETUP));
        assertEquals(before, orderRepository.count());
        verify(stripe, never()).createCheckoutSession(anyString(), any());
    }

    @Test
    void publishingRequiresPro() throws Exception {
        setPro(author, "past_due");
        mvc.perform(post("/api/store/publish").with(asAuthor())).andExpect(status().isForbidden());
    }

    // ---- Download tokens ----------------------------------------------------------

    @Test
    void wrongExpiredRevokedOrUsedUpTokensAreRefused() throws Exception {
        mvc.perform(get("/api/public/downloads/not-a-real-token/file")).andExpect(status().isNotFound());

        // Expired.
        String token = paidOrderToken();
        DownloadGrant grant = grantRepository.findByTokenHash(DownloadGrantService.hash(token)).orElseThrow();
        grant.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        grantRepository.save(grant);
        mvc.perform(get("/api/public/downloads/" + token + "/file")).andExpect(status().isGone());

        // Revoked.
        grant.setExpiresAt(LocalDateTime.now().plusDays(1));
        grant.setRevokedAt(LocalDateTime.now());
        grantRepository.save(grant);
        mvc.perform(get("/api/public/downloads/" + token + "/file")).andExpect(status().isGone());

        // Used up: 5 downloads, then refused.
        reset(mailService);
        String fresh = paidOrderToken();
        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/api/public/downloads/" + fresh + "/file")).andExpect(status().isOk());
        }
        mvc.perform(get("/api/public/downloads/" + fresh + "/file")).andExpect(status().isGone());
    }

    // ---- Refunds ------------------------------------------------------------------

    @Test
    void fullRefundRevokesAccess() throws Exception {
        StoreOrder order = checkout();
        webhook(completed(evt(), account, order, "paid"));
        String token = sentToken();

        // A partial refund keeps access.
        webhook(chargeRefunded(evt(), account, order, PRICE / 2, false)).andExpect(status().isOk());
        assertEquals(StoreOrderStatus.PAID, orderRepository.findById(order.getId()).orElseThrow().getStatus());

        // A refund event from another account is ignored.
        webhook(chargeRefunded(evt(), "acct_attacker", order, PRICE, true)).andExpect(status().isOk());
        assertEquals(StoreOrderStatus.PAID, orderRepository.findById(order.getId()).orElseThrow().getStatus());

        webhook(chargeRefunded(evt(), account, order, PRICE, true)).andExpect(status().isOk());
        assertEquals(StoreOrderStatus.REFUNDED, orderRepository.findById(order.getId()).orElseThrow().getStatus());
        assertNotNull(grantRepository.findByOrderId(order.getId()).orElseThrow().getRevokedAt());
        mvc.perform(get("/api/public/downloads/" + token + "/file")).andExpect(status().isGone());
    }

    // ---- Account status sync ------------------------------------------------------

    @Test
    void accountUpdatedFetchesTheAccountFromStripe() throws Exception {
        store.setChargesEnabled(false);
        storeRepository.save(store);
        when(stripe.retrieveAccount(account)).thenReturn(
                new StripeConnectGateway.AccountState(account, true, true, List.of()));

        webhook(accountUpdated(evt(), account)).andExpect(status().isOk());

        verify(stripe).retrieveAccount(account);
        Store synced = storeRepository.findById(store.getId()).orElseThrow();
        assertTrue(synced.isChargesEnabled());
        assertNotNull(synced.getStripeSyncedAt());
    }

    @Test
    void accountsV2EventFetchesTheAccountOnceAndRejectsForgeries() throws Exception {
        store.setChargesEnabled(false);
        storeRepository.save(store);
        when(stripe.retrieveAccount(account)).thenReturn(
                new StripeConnectGateway.AccountState(account, true, true, List.of()));
        String payload = """
                {"id":"%s","object":"v2.core.event","type":"v2.core.account[configuration.merchant].capability_status_updated",
                 "livemode":false,"created":"2026-10-14T20:00:00.000Z",
                 "related_object":{"id":"%s","type":"v2.core.account","url":"/v2/core/accounts/%s"}}
                """.formatted(evt(), account, account);

        mvc.perform(post("/api/stripe/connect/account-events").contentType(MediaType.APPLICATION_JSON)
                        .content(payload).header("Stripe-Signature", "t=1,v1=deadbeef"))
                .andExpect(status().isBadRequest());
        verify(stripe, never()).retrieveAccount(anyString());

        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/stripe/connect/account-events").contentType(MediaType.APPLICATION_JSON)
                            .content(payload).header("Stripe-Signature", sign(payload, V2_SECRET)))
                    .andExpect(status().isOk());
        }
        verify(stripe, times(1)).retrieveAccount(account);
        assertTrue(storeRepository.findById(store.getId()).orElseThrow().isChargesEnabled());
    }

    @Test
    void returningFromStripeIsNotEnoughToBeReady() throws Exception {
        store.setChargesEnabled(false);
        storeRepository.save(store);
        when(stripe.retrieveAccount(account)).thenReturn(
                new StripeConnectGateway.AccountState(account, false, true, List.of("external_account")));

        mvc.perform(post("/api/store/stripe/sync").with(asAuthor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stripe.state").value("ACTION_REQUIRED"))
                .andExpect(jsonPath("$.stripe.currentlyDue[0]").value("external_account"));
    }

    @Test
    void submittedButNotYetActiveIsPendingVerification() throws Exception {
        store.setChargesEnabled(false);
        storeRepository.save(store);
        when(stripe.retrieveAccount(account)).thenReturn(
                new StripeConnectGateway.AccountState(account, false, true, List.of()));

        mvc.perform(post("/api/store/stripe/sync").with(asAuthor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stripe.state").value("PENDING_VERIFICATION"))
                .andExpect(jsonPath("$.stripe.chargesEnabled").value(false));
    }

    @Test
    void connectCreatesTheAccountOnceAndReturnsAFreshLink() throws Exception {
        store.setStripeAccountId(null);
        store.setChargesEnabled(false);
        storeRepository.save(store);
        when(stripe.createAccount(anyString(), any(), anyString())).thenReturn("acct_new_" + store.getId().toString().substring(0, 8));
        when(stripe.createAccountLink(anyString(), anyString(), anyString())).thenReturn("https://connect.stripe.com/setup/x");

        mvc.perform(post("/api/store/stripe/connect").with(asAuthor())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"country\":\"US\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.url").value("https://connect.stripe.com/setup/x"));
        mvc.perform(post("/api/store/stripe/connect").with(asAuthor())).andExpect(status().isOk());

        verify(stripe, times(1)).createAccount(eq(author.getEmail()), eq("US"), eq(store.getId().toString()));
        verify(stripe, times(2)).createAccountLink(anyString(), anyString(), anyString());
        Store connected = storeRepository.findById(store.getId()).orElseThrow();
        assertNotNull(connected.getStripeAccountId());
        assertFalse(connected.isChargesEnabled(), "never ready just because a link was created");
    }

    // ---- Helpers ------------------------------------------------------------------

    private StoreOrder checkout() throws Exception {
        checkoutRequest(product.getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.checkoutUrl").value("https://checkout.stripe.com/c/pay/test"));
        return latestOrder();
    }

    private ResultActions checkoutRequest(UUID productId) throws Exception {
        return mvc.perform(post("/api/public/checkout").contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":\"" + productId + "\"}"));
    }

    private StoreOrder latestOrder() {
        return orderRepository.findAll().stream()
                .filter(o -> o.getProductId().equals(product.getId()))
                .max((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                .orElseThrow();
    }

    private String paidOrderToken() throws Exception {
        StoreOrder order = checkout();
        webhook(completed(evt(), account, order, "paid")).andExpect(status().isOk());
        return sentToken();
    }

    private String sentToken() {
        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(mailService, atLeastOnce()).sendStorePurchaseEmail(eq("buyer@example.com"), any(), any(), url.capture(), anyBoolean());
        String link = url.getValue();
        assertTrue(link.startsWith("http://localhost:5173/download#"), link);
        return link.substring(link.indexOf('#') + 1);
    }

    private void setPro(User user, String status) {
        UserSubscription sub = subscriptionRepository.findById(user.getId())
                .orElseGet(() -> UserSubscription.builder().userId(user.getId()).build());
        sub.setStatus(status);
        sub.setStripeSubscriptionId("sub_test");
        subscriptionRepository.save(sub);
    }

    private RequestPostProcessor asAuthor() {
        return SecurityMockMvcRequestPostProcessors.user(author.getEmail());
    }

    private ResultActions webhook(String payload) throws Exception {
        return mvc.perform(post("/api/stripe/connect/webhook").contentType(MediaType.APPLICATION_JSON)
                .content(payload).header("Stripe-Signature", sign(payload)));
    }

    private static String evt() {
        return "evt_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String completed(String eventId, String eventAccount, StoreOrder order, String paymentStatus) {
        return sessionEvent(eventId, "checkout.session.completed", eventAccount, order, paymentStatus);
    }

    private String sessionEvent(String eventId, String type, String eventAccount, StoreOrder order, String paymentStatus) {
        String id = order.getId().toString();
        String session = """
                {"id":"%s","object":"checkout.session","mode":"payment","payment_status":"%s",
                 "status":"%s","amount_total":%d,"currency":"usd","client_reference_id":"%s",
                 "metadata":{"order_id":"%s"},"payment_intent":"pi_%s",
                 "customer_details":{"email":"buyer@example.com"},"consent":{"terms_of_service":"accepted"}}
                """.formatted(order.getCheckoutSessionId(), paymentStatus,
                type.endsWith("expired") ? "expired" : "complete", PRICE, id, id, id.substring(0, 8));
        return envelope(eventId, type, eventAccount, session);
    }

    private String chargeRefunded(String eventId, String eventAccount, StoreOrder order, long refunded, boolean full) {
        String charge = """
                {"id":"ch_%s","object":"charge","amount":%d,"amount_refunded":%d,"refunded":%s,
                 "currency":"usd","payment_intent":"pi_%s"}
                """.formatted(UUID.randomUUID().toString().substring(0, 8), PRICE, refunded, full,
                order.getId().toString().substring(0, 8));
        return envelope(eventId, "charge.refunded", eventAccount, charge);
    }

    private String accountUpdated(String eventId, String eventAccount) {
        return envelope(eventId, "account.updated", eventAccount,
                "{\"id\":\"" + eventAccount + "\",\"object\":\"account\",\"charges_enabled\":true}");
    }

    private static String envelope(String eventId, String type, String eventAccount, String object) {
        return """
                {"id":"%s","object":"event","api_version":"2026-06-24.dahlia","account":"%s",
                 "created":%d,"livemode":false,"type":"%s","data":{"object":%s}}
                """.formatted(eventId, eventAccount, System.currentTimeMillis() / 1000L, type, object);
    }

    private static String sign(String payload) {
        return sign(payload, SECRET);
    }

    private static String sign(String payload, String secret) {
        long timestamp = System.currentTimeMillis() / 1000L;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String v1 = HexFormat.of().formatHex(mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8)));
            return "t=" + timestamp + ",v1=" + v1;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
