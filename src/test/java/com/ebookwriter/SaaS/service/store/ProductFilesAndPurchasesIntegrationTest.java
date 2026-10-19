package com.ebookwriter.SaaS.service.store;

import com.ebookwriter.SaaS.entity.*;
import com.ebookwriter.SaaS.repository.*;
import com.ebookwriter.SaaS.service.MailService;
import com.ebookwriter.SaaS.service.storage.R2StorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.param.checkout.SessionCreateParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
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
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Product file delivery end to end through the real HTTP API, services and
 * database (H2): author uploads (validation, permissions, replacement, removal),
 * verified purchases via the signed Connect webhook (and its retries), My
 * Purchases with unlimited repeat downloads, guest token downloads of several
 * files, and every way access must be refused. Only Stripe's network calls, the
 * mail transport and object storage (an in-memory map) are replaced.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "stripe.connect.webhook-secret=" + ProductFilesAndPurchasesIntegrationTest.SECRET,
        "store.files.max-file-bytes=1048576",
        "store.files.max-files-per-product=3"})
class ProductFilesAndPurchasesIntegrationTest {

    static final String SECRET = "whsec_product_files_secret";
    static final long PRICE = 1500;
    static final byte[] EBOOK_PDF = "%PDF-1.4 the generated ebook".getBytes(StandardCharsets.UTF_8);
    static final byte[] GUIDE_PDF = "%PDF-1.7 workbook v1".getBytes(StandardCharsets.UTF_8);
    static final byte[] GUIDE_PDF_V2 = "%PDF-1.7 workbook v2 (fixed typos)".getBytes(StandardCharsets.UTF_8);

    @Autowired WebApplicationContext context;
    final ObjectMapper json = new ObjectMapper();
    @Autowired UserRepository userRepository;
    @Autowired UserSubscriptionRepository subscriptionRepository;
    @Autowired EbookRepository ebookRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired StoreProductRepository productRepository;
    @Autowired StoreOrderRepository orderRepository;
    @Autowired ProductFileRepository fileRepository;
    @Autowired DownloadGrantRepository grantRepository;

    @MockitoBean StripeConnectGateway stripe;
    @MockitoBean MailService mailService;
    @MockitoBean EbookPdfRepository pdfRepository;
    @MockitoBean R2StorageService storage;

    /** The "bucket". */
    final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    MockMvc mvc;
    User author;
    User otherAuthor;
    Store store;
    StoreProduct ebookProduct;
    String account;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        objects.clear();
        when(storage.isConfigured()).thenReturn(true);
        doAnswer(inv -> {
            objects.put(inv.getArgument(0), Files.readAllBytes(inv.<Path>getArgument(1)));
            return null;
        }).when(storage).upload(anyString(), any(Path.class), anyLong(), anyString());
        doAnswer(inv -> {
            objects.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(storage).upload(anyString(), any(byte[].class), anyString());
        when(storage.openStream(anyString())).thenAnswer(inv -> Optional.ofNullable(objects.get(inv.<String>getArgument(0)))
                .map(b -> new R2StorageService.StoredObject(new ByteArrayInputStream(b), b.length)));
        when(storage.download(anyString())).thenAnswer(inv -> Optional.ofNullable(objects.get(inv.<String>getArgument(0))));
        doAnswer(inv -> objects.remove(inv.<String>getArgument(0))).when(storage).delete(anyString());

        author = user("author", true);
        setPro(author);
        otherAuthor = user("other-author", true);
        setPro(otherAuthor);
        Store other = new Store();
        other.setOwnerId(otherAuthor.getId());
        other.setSlug("o" + suffix());
        other.setName("Other store");
        storeRepository.save(other);

        Ebook ebook = ebookRepository.save(Ebook.builder().user(author).topic("Sourdough")
                .title("Sourdough at Home").status(EbookStatus.COMPLETED).build());
        when(pdfRepository.existsById(ebook.getId())).thenReturn(true);
        when(pdfRepository.findById(ebook.getId())).thenReturn(Optional.of(new EbookPdf(ebook.getId(), EBOOK_PDF)));

        account = "acct_" + suffix();
        store = new Store();
        store.setOwnerId(author.getId());
        store.setSlug("s" + suffix());
        store.setName("Author's Kitchen");
        store.setPublished(true);
        store.setStripeAccountId(account);
        store.setChargesEnabled(true);
        store.setDetailsSubmitted(true);
        store = storeRepository.save(store);

        ebookProduct = new StoreProduct();
        ebookProduct.setStoreId(store.getId());
        ebookProduct.setEbookId(ebook.getId());
        ebookProduct.setTitle("Sourdough at Home");
        ebookProduct.setPriceCents(PRICE);
        ebookProduct.setCurrency("usd");
        ebookProduct = productRepository.save(ebookProduct);

        when(stripe.createCheckoutSession(anyString(), any())).thenAnswer(inv ->
                new StripeConnectGateway.CreatedSession("cs_test_" + UUID.randomUUID(), "https://checkout.stripe.com/c/pay/test"));
    }

    // ---- Author: uploads and validation -------------------------------------------

    @Test
    void authorUploadsPdfEpubAndZipAndStorageKeysNeverLeak() throws Exception {
        String body = upload(ebookProduct, "Workbook.pdf", GUIDE_PDF).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        upload(ebookProduct, "Sourdough.epub", ProductFileValidatorTest.epub()).andExpect(status().isOk());
        String last = upload(ebookProduct, "templates.zip", ProductFileValidatorTest.zip(Map.of("a.txt", "a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products[0].files.length()").value(3))
                .andExpect(jsonPath("$.products[0].files[0].format").value("PDF"))
                .andExpect(jsonPath("$.products[0].files[1].format").value("EPUB"))
                .andExpect(jsonPath("$.products[0].files[2].format").value("ZIP"))
                .andExpect(jsonPath("$.products[0].files[2].filename").value("templates.zip"))
                .andExpect(jsonPath("$.products[0].deliverable").value(true))
                .andReturn().getResponse().getContentAsString();

        List<ProductFile> files = fileRepository.findByProductIdAndRemovedAtIsNullOrderBySortOrderAscCreatedAtAsc(ebookProduct.getId());
        assertEquals(3, files.size());
        ProductFile pdf = files.get(0);
        assertEquals("Workbook.pdf", pdf.getOriginalFilename());
        assertEquals("application/pdf", pdf.getContentType());
        assertEquals(GUIDE_PDF.length, pdf.getSizeBytes());
        assertEquals(1, pdf.getVersion());
        assertEquals(author.getId(), pdf.getUploadedBy());
        assertEquals(64, pdf.getSha256().length());
        assertTrue(pdf.getStorageKey().startsWith("products/" + ebookProduct.getId() + "/files/"));
        assertArrayEquals(GUIDE_PDF, objects.get(pdf.getStorageKey()), "stored unmodified");
        for (ProductFile f : files) {
            assertFalse(body.contains(f.getStorageKey()) || last.contains(f.getStorageKey()), "storage key exposed");
            assertFalse(last.contains("products/" + ebookProduct.getId() + "/files"), "no storage path in the API");
        }

        // The public product page tells buyers what they get.
        mvc.perform(get("/api/public/stores/" + store.getSlug() + "/products/" + ebookProduct.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.formats[0]").value("PDF"))
                .andExpect(jsonPath("$.formats[1]").value("EPUB"))
                .andExpect(jsonPath("$.formats[2]").value("ZIP"));

        // Per-product limit (3 in this test).
        upload(ebookProduct, "one-more.pdf", GUIDE_PDF).andExpect(status().isConflict());
    }

    @Test
    void invalidFilesAreRejectedAndNothingIsStored() throws Exception {
        upload(ebookProduct, "setup.exe", "MZ binary".getBytes()).andExpect(status().isBadRequest());
        upload(ebookProduct, "invoice.pdf", "MZ\u0090 renamed exe".getBytes(StandardCharsets.ISO_8859_1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Executable files can't be sold. Upload a PDF, EPUB or ZIP file."));
        upload(ebookProduct, "book.epub", GUIDE_PDF).andExpect(status().isBadRequest());
        upload(ebookProduct, "bundle.zip", ProductFileValidatorTest.zip(Map.of("../x.txt", "x"))).andExpect(status().isBadRequest());
        upload(ebookProduct, "bundle.zip", ProductFileValidatorTest.zip(Map.of("run.bat", "x"))).andExpect(status().isBadRequest());
        upload(ebookProduct, "empty.pdf", new byte[0]).andExpect(status().isBadRequest());
        byte[] big = new byte[1024 * 1024 + 1];
        System.arraycopy(GUIDE_PDF, 0, big, 0, GUIDE_PDF.length);
        upload(ebookProduct, "big.pdf", big).andExpect(status().isPayloadTooLarge());

        assertEquals(0, fileRepository.countByProductIdAndRemovedAtIsNull(ebookProduct.getId()));
        assertTrue(objects.isEmpty());
    }

    @Test
    void storageFailureIsReportedAndLeavesNoRecord() throws Exception {
        doThrow(new R2StorageService.StorageException("bucket down"))
                .when(storage).upload(anyString(), any(Path.class), anyLong(), anyString());
        upload(ebookProduct, "Workbook.pdf", GUIDE_PDF).andExpect(status().isServiceUnavailable());
        assertEquals(0, fileRepository.countByProductIdAndRemovedAtIsNull(ebookProduct.getId()));
    }

    @Test
    void onlyTheProductsAuthorCanManageItsFiles() throws Exception {
        upload(ebookProduct, "Workbook.pdf", GUIDE_PDF).andExpect(status().isOk());
        ProductFile file = currentFiles(ebookProduct).get(0);

        // Unauthenticated.
        mvc.perform(multipart("/api/store/products/" + ebookProduct.getId() + "/files").file(part("x.pdf", GUIDE_PDF)))
                .andExpect(result -> assertTrue(result.getResponse().getStatus() == 401 || result.getResponse().getStatus() == 403));
        // Another author: the product isn't in their store.
        mvc.perform(multipart("/api/store/products/" + ebookProduct.getId() + "/files").file(part("x.pdf", GUIDE_PDF))
                .with(as(otherAuthor))).andExpect(status().isNotFound());
        mvc.perform(multipart("/api/store/products/" + ebookProduct.getId() + "/files/" + file.getId() + "/replace")
                .file(part("x.pdf", GUIDE_PDF)).with(as(otherAuthor))).andExpect(status().isNotFound());
        mvc.perform(delete("/api/store/products/" + ebookProduct.getId() + "/files/" + file.getId()).with(as(otherAuthor)))
                .andExpect(status().isNotFound());
        // A buyer without a store.
        mvc.perform(multipart("/api/store/products/" + ebookProduct.getId() + "/files").file(part("x.pdf", GUIDE_PDF))
                .with(as(user("buyer", true)))).andExpect(status().isNotFound());
        // Path tricks never reach storage: ids are UUIDs.
        mvc.perform(delete("/api/store/products/" + ebookProduct.getId() + "/files/..%2F..%2Fsecret").with(as(author)))
                .andExpect(status().is4xxClientError());

        assertEquals(1, currentFiles(ebookProduct).size());
        assertEquals(1, objects.size());
    }

    // ---- Standalone products ------------------------------------------------------

    @Test
    void standaloneProductStaysHiddenUntilItHasAFileAndCanThenBeBought() throws Exception {
        mvc.perform(post("/api/store/products").with(as(author)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"priceCents\":900}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/store/products").with(as(author)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Budget templates\",\"priceCents\":900}"))
                .andExpect(status().isOk());
        StoreProduct product = productRepository.findByStoreIdOrderBySortOrderAscCreatedAtAsc(store.getId()).stream()
                .filter(p -> p.getEbookId() == null).findFirst().orElseThrow();
        assertFalse(product.isActive(), "nothing to deliver yet");
        mvc.perform(patch("/api/store/products/" + product.getId()).with(as(author)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":true}")).andExpect(status().isConflict());
        product.setActive(true);
        productRepository.save(product);
        checkoutRequest(product.getId(), null).andExpect(status().isConflict());
        product.setActive(false);
        productRepository.save(product);

        byte[] zip = ProductFileValidatorTest.zip(Map.of("budget.csv", "a,b"));
        upload(product, "templates.zip", zip).andExpect(status().isOk());
        // Added to a live store: it goes live with the next publish, now that it has a file.
        mvc.perform(get("/api/store").with(as(author)))
                .andExpect(jsonPath("$.changes[?(@.field=='active')].after").value("true"));
        mvc.perform(post("/api/store/publish").with(as(author))).andExpect(status().isOk());
        assertTrue(productRepository.findById(product.getId()).orElseThrow().isActive());

        // Own cover (separate from the files), served publicly.
        byte[] png = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");
        mvc.perform(multipart("/api/store/products/" + product.getId() + "/cover")
                        .file(new MockMultipartFile("file", "cover.png", "image/png", png)).with(as(author)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/public/products/" + product.getId() + "/cover"))
                .andExpect(status().isOk())
                .andExpect(result -> assertEquals("image/png", result.getResponse().getContentType()));
        mvc.perform(get("/api/public/stores/" + store.getSlug())).andExpect(status().isOk())
                .andExpect(jsonPath("$.products[?(@.title=='Budget templates')].coverUrl").isNotEmpty());

        User buyer = user("standalone-buyer", true);
        StoreOrder order = buy(product, buyer, "paid");
        JsonNode purchases = purchases(buyer);
        assertEquals(1, purchases.size());
        assertEquals(order.getId().toString(), purchases.get(0).get("orderId").asText());
        assertEquals("templates.zip", purchases.get(0).get("files").get(0).get("filename").asText());
        byte[] got = downloadAsBuyer(buyer, order, purchases.get(0).get("files").get(0).get("id").asText());
        assertArrayEquals(zip, got);
    }

    // ---- My Purchases ------------------------------------------------------------

    @Test
    void verifiedBuyerSeesThePurchaseAndCanDownloadEveryFileRepeatedly() throws Exception {
        upload(ebookProduct, "Workbook.pdf", GUIDE_PDF).andExpect(status().isOk());
        User buyer = user("buyer", true);

        // Nothing before the webhook confirms payment — the success redirect grants nothing.
        StoreOrder pending = checkout(ebookProduct, null);
        assertEquals(0, purchases(buyer).size());

        webhook(completed(evt(), pending, "paid", buyer.getEmail())).andExpect(status().isOk());
        StoreOrder paid = orderRepository.findById(pending.getId()).orElseThrow();
        assertEquals(buyer.getId(), paid.getBuyerUserId(), "linked to the verified account by email");

        JsonNode list = purchases(buyer);
        assertEquals(1, list.size());
        JsonNode files = list.get(0).get("files");
        assertEquals(2, files.size());
        assertEquals(ProductFileService.EBOOK_PDF_ID, files.get(0).get("id").asText());
        assertEquals("Workbook.pdf", files.get(1).get("filename").asText());
        assertEquals("Sourdough at Home", list.get(0).get("productTitle").asText());

        for (int i = 0; i < 8; i++) { // unlimited — well past the guest limit of 5 per file
            assertArrayEquals(EBOOK_PDF, downloadAsBuyer(buyer, paid, ProductFileService.EBOOK_PDF_ID));
            assertArrayEquals(GUIDE_PDF, downloadAsBuyer(buyer, paid, files.get(1).get("id").asText()));
        }

        // Response headers: attachment with the author's filename, never cached, served type from our format.
        String url = link(buyer, paid, files.get(1).get("id").asText()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        mvc.perform(get(json.readTree(url).get("url").asText()))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    assertEquals("application/pdf", result.getResponse().getContentType());
                    assertTrue(result.getResponse().getHeader("Content-Disposition").startsWith("attachment;"));
                    assertTrue(result.getResponse().getHeader("Content-Disposition").contains("Workbook.pdf"));
                    assertEquals("no-store", result.getResponse().getHeader("Cache-Control"));
                    assertEquals("nosniff", result.getResponse().getHeader("X-Content-Type-Options"));
                });
    }

    @Test
    void unauthorizedDownloadsAreRefused() throws Exception {
        upload(ebookProduct, "Workbook.pdf", GUIDE_PDF).andExpect(status().isOk());
        User buyer = user("buyer", true);
        StoreOrder order = buy(ebookProduct, buyer, "paid");
        String fileId = currentFiles(ebookProduct).get(0).getId().toString();

        // Not signed in.
        mvc.perform(get("/api/purchases"))
                .andExpect(result -> assertTrue(result.getResponse().getStatus() == 401 || result.getResponse().getStatus() == 403));
        mvc.perform(post("/api/purchases/" + order.getId() + "/files/" + fileId + "/download-link"))
                .andExpect(result -> assertTrue(result.getResponse().getStatus() == 401 || result.getResponse().getStatus() == 403));
        // Someone else's order: same 404 as an unknown one.
        User stranger = user("stranger", true);
        link(stranger, order, fileId).andExpect(status().isNotFound());
        link(stranger, orderRepository.findById(order.getId()).orElseThrow(), ProductFileService.EBOOK_PDF_ID)
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/purchases/" + UUID.randomUUID() + "/files/" + fileId + "/download-link").with(as(buyer)))
                .andExpect(status().isNotFound());
        assertEquals(0, purchases(stranger).size());
        // A file of another product, through this order.
        link(buyer, order, UUID.randomUUID().toString()).andExpect(status().isNotFound());
        link(buyer, order, "../../etc/passwd").andExpect(status().is4xxClientError());

        // No, forged, tampered and access-token links.
        mvc.perform(get("/api/public/purchase-files")).andExpect(status().isForbidden());
        mvc.perform(get("/api/public/purchase-files").param("token", "not-a-jwt")).andExpect(status().isForbidden());
        String good = json.readTree(link(buyer, order, fileId).andReturn().getResponse().getContentAsString())
                .get("url").asText();
        String token = good.substring(good.indexOf("token=") + 6);
        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + parts[1] + "." + new StringBuilder(parts[2]).reverse();
        mvc.perform(get("/api/public/purchase-files").param("token", tampered)).andExpect(status().isForbidden());
        String ebookLinkToken = context.getBean(com.ebookwriter.SaaS.service.ebook.PdfDownloadLinkService.class)
                .issue(UUID.randomUUID(), buyer.getId());
        mvc.perform(get("/api/public/purchase-files").param("token", ebookLinkToken)).andExpect(status().isForbidden());

        // A valid link stops working once the order is refunded (re-checked on use).
        mvc.perform(get(good)).andExpect(status().isOk());
        webhook(chargeRefunded(evt(), order)).andExpect(status().isOk());
        mvc.perform(get(good)).andExpect(status().isNotFound());
        assertEquals(0, purchases(buyer).size());
    }

    @Test
    void unverifiedAccountWithTheSameEmailGetsNothing() throws Exception {
        User unverified = user("unverified", false);
        StoreOrder order = checkout(ebookProduct, null);
        webhook(completed(evt(), order, "paid", unverified.getEmail())).andExpect(status().isOk());
        assertNull(orderRepository.findById(order.getId()).orElseThrow().getBuyerUserId());
        assertEquals(0, purchases(unverified).size());
        link(unverified, order, ProductFileService.EBOOK_PDF_ID).andExpect(status().isNotFound());
    }

    @Test
    void guestPurchaseIsClaimedOnceTheBuyerHasAVerifiedAccount() throws Exception {
        String email = "later-" + suffix() + "@example.com";
        StoreOrder order = checkout(ebookProduct, null);
        webhook(completed(evt(), order, "paid", email.toUpperCase())).andExpect(status().isOk());
        assertNull(orderRepository.findById(order.getId()).orElseThrow().getBuyerUserId(), "no account yet");

        // Registers and verifies afterwards (the existing email-verification flow → enabled).
        User buyer = userRepository.save(User.builder().email(email).displayName("Later").password("x").enabled(true).build());
        JsonNode list = purchases(buyer);
        assertEquals(1, list.size());
        assertEquals(buyer.getId(), orderRepository.findById(order.getId()).orElseThrow().getBuyerUserId());
        assertArrayEquals(EBOOK_PDF, downloadAsBuyer(buyer, order, ProductFileService.EBOOK_PDF_ID));
    }

    @Test
    void signedInCheckoutLinksTheOrderAndLocksTheEmail() throws Exception {
        User buyer = user("signed-in", true);
        StoreOrder order = checkout(ebookProduct, buyer);
        assertEquals(buyer.getId(), order.getBuyerUserId());
        ArgumentCaptor<SessionCreateParams> params = ArgumentCaptor.forClass(SessionCreateParams.class);
        verify(stripe).createCheckoutSession(eq(account), params.capture());
        assertEquals(buyer.getEmail(), params.getValue().getCustomerEmail());

        assertEquals(0, purchases(buyer).size(), "linked, but not paid yet");
        webhook(completed(evt(), order, "unpaid", buyer.getEmail())).andExpect(status().isOk());
        assertEquals(0, purchases(buyer).size());
        webhook(completed(evt(), order, "paid", buyer.getEmail())).andExpect(status().isOk());
        assertEquals(1, purchases(buyer).size());
        verify(mailService).sendStorePurchaseEmail(eq(buyer.getEmail()), any(), any(), anyString(), eq(true));
    }

    @Test
    void accessSurvivesTheAuthorHidingTheProductUnpublishingOrLosingPro() throws Exception {
        User buyer = user("loyal", true);
        StoreOrder order = buy(ebookProduct, buyer, "paid");
        ebookProduct.setActive(false);
        productRepository.save(ebookProduct);
        store.setPublished(false);
        storeRepository.save(store);
        UserSubscription sub = subscriptionRepository.findById(author.getId()).orElseThrow();
        sub.setStatus("canceled");
        subscriptionRepository.save(sub);

        JsonNode list = purchases(buyer);
        assertEquals(1, list.size());
        assertFalse(list.get(0).get("onSale").asBoolean());
        assertArrayEquals(EBOOK_PDF, downloadAsBuyer(buyer, order, ProductFileService.EBOOK_PDF_ID));
    }

    // ---- Webhook retries ----------------------------------------------------------

    @Test
    void webhookRetriesNeverCreateInconsistentRecords() throws Exception {
        User buyer = user("retry", true);
        StoreOrder order = checkout(ebookProduct, null);
        String payload = completed(evt(), order, "paid", buyer.getEmail());
        webhook(payload).andExpect(status().isOk());
        webhook(payload).andExpect(status().isOk()); // same event redelivered
        // Same session delivered again under a new event id (e.g. replayed from the Dashboard).
        webhook(completed(evt(), order, "paid", buyer.getEmail())).andExpect(status().isOk());

        StoreOrder paid = orderRepository.findById(order.getId()).orElseThrow();
        assertEquals(StoreOrderStatus.PAID, paid.getStatus());
        assertEquals(buyer.getId(), paid.getBuyerUserId());
        assertEquals(1, grantRepository.findAll().stream().filter(g -> g.getOrderId().equals(order.getId())).count());
        verify(mailService, times(1)).sendStorePurchaseEmail(anyString(), any(), any(), anyString(), anyBoolean());
        assertEquals(1, purchases(buyer).size());
    }

    // ---- Replacement and removal ---------------------------------------------------

    @Test
    void replacingAFileKeepsPurchasesWorkingWithTheNewVersionAndKeepsHistory() throws Exception {
        upload(ebookProduct, "Workbook.pdf", GUIDE_PDF).andExpect(status().isOk());
        ProductFile v1 = currentFiles(ebookProduct).get(0);
        User buyer = user("replace", true);
        StoreOrder order = buy(ebookProduct, buyer, "paid");
        assertArrayEquals(GUIDE_PDF, downloadAsBuyer(buyer, order, v1.getId().toString()));
        String guestToken = sentToken();

        mvc.perform(multipart("/api/store/products/" + ebookProduct.getId() + "/files/" + v1.getId() + "/replace")
                        .file(part("Workbook (2nd edition).pdf", GUIDE_PDF_V2)).with(as(author)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products[0].files.length()").value(1))
                .andExpect(jsonPath("$.products[0].files[0].version").value(2));

        ProductFile v2 = currentFiles(ebookProduct).get(0);
        assertEquals(2, v2.getVersion());
        assertEquals(v1.getId(), v2.getReplacesId());
        assertEquals("Workbook (2nd edition).pdf", v2.getOriginalFilename());
        ProductFile old = fileRepository.findById(v1.getId()).orElseThrow();
        assertNotNull(old.getRemovedAt(), "history kept");
        assertEquals("REPLACED", old.getRemovedReason());
        assertEquals(v1.getSha256(), old.getSha256());
        assertFalse(objects.containsKey(v1.getStorageKey()), "old object deleted after commit");

        // The buyer now gets the current version; the old file id is gone.
        JsonNode files = purchases(buyer).get(0).get("files");
        assertEquals(v2.getId().toString(), files.get(1).get("id").asText());
        assertArrayEquals(GUIDE_PDF_V2, downloadAsBuyer(buyer, order, v2.getId().toString()));
        link(buyer, order, v1.getId().toString()).andExpect(status().isNotFound());
        // So does the guest link from the email.
        byte[] guest = mvc.perform(get("/api/public/downloads/" + guestToken + "/files/" + v2.getId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(GUIDE_PDF_V2, guest);
    }

    @Test
    void theLastFileOfAPurchasedProductCannotBeRemoved() throws Exception {
        mvc.perform(post("/api/store/products").with(as(author)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Templates\",\"priceCents\":900}")).andExpect(status().isOk());
        StoreProduct product = productRepository.findByStoreIdOrderBySortOrderAscCreatedAtAsc(store.getId()).stream()
                .filter(p -> p.getEbookId() == null).findFirst().orElseThrow();
        upload(product, "a.pdf", GUIDE_PDF).andExpect(status().isOk());
        upload(product, "b.pdf", GUIDE_PDF_V2).andExpect(status().isOk());
        product = productRepository.findById(product.getId()).orElseThrow();
        product.setActive(true);
        productRepository.save(product);
        List<ProductFile> files = currentFiles(product);

        buy(product, user("owner", true), "paid");
        mvc.perform(delete("/api/store/products/" + product.getId() + "/files/" + files.get(0).getId()).with(as(author)))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/store/products/" + product.getId() + "/files/" + files.get(1).getId()).with(as(author)))
                .andExpect(status().isConflict());
        assertEquals(1, currentFiles(product).size());
        assertEquals("REMOVED", fileRepository.findById(files.get(0).getId()).orElseThrow().getRemovedReason());
    }

    @Test
    void aFileMissingFromStorageIsReportedNotServed() throws Exception {
        upload(ebookProduct, "Workbook.pdf", GUIDE_PDF).andExpect(status().isOk());
        ProductFile file = currentFiles(ebookProduct).get(0);
        User buyer = user("missing", true);
        StoreOrder order = buy(ebookProduct, buyer, "paid");
        String guestToken = sentToken();
        objects.remove(file.getStorageKey());

        String url = json.readTree(link(buyer, order, file.getId().toString()).andReturn().getResponse()
                .getContentAsString()).get("url").asText();
        mvc.perform(get(url)).andExpect(status().isGone());
        int before = grantRepository.findByOrderId(order.getId()).orElseThrow().getDownloadsUsed();
        mvc.perform(get("/api/public/downloads/" + guestToken + "/files/" + file.getId())).andExpect(status().isGone());
        assertEquals(before, grantRepository.findByOrderId(order.getId()).orElseThrow().getDownloadsUsed(),
                "a failed download doesn't use one up");
    }

    // ---- Guest token downloads -----------------------------------------------------

    @Test
    void guestLinkListsAndServesEveryFileWithALimitPerFile() throws Exception {
        upload(ebookProduct, "Workbook.pdf", GUIDE_PDF).andExpect(status().isOk());
        StoreOrder order = checkout(ebookProduct, null);
        webhook(completed(evt(), order, "paid", "guest-" + suffix() + "@example.com")).andExpect(status().isOk());
        String token = sentToken();
        ProductFile file = currentFiles(ebookProduct).get(0);

        mvc.perform(get("/api/public/downloads/" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files.length()").value(2))
                .andExpect(jsonPath("$.files[0].id").value(ProductFileService.EBOOK_PDF_ID))
                .andExpect(jsonPath("$.files[1].filename").value("Workbook.pdf"))
                .andExpect(jsonPath("$.downloadsRemaining").value(10))
                .andExpect(jsonPath("$.fileSizeBytes").value(EBOOK_PDF.length + GUIDE_PDF.length));

        assertArrayEquals(GUIDE_PDF, mvc.perform(get("/api/public/downloads/" + token + "/files/" + file.getId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        // The original single-file link still serves the first file.
        assertArrayEquals(EBOOK_PDF, mvc.perform(get("/api/public/downloads/" + token + "/file"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        mvc.perform(get("/api/public/downloads/" + token + "/files/" + UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(get("/api/public/downloads/wrong-token/files/" + file.getId())).andExpect(status().isNotFound());
        assertEquals(2, grantRepository.findByOrderId(order.getId()).orElseThrow().getDownloadsUsed());
    }

    @Test
    void accountPerksAreLeftOutOfTheGuestLinkButShownToAccounts() throws Exception {
        upload(ebookProduct, "schedule.zip", ProductFileValidatorTest.zip(Map.of("week.txt", "feed"))).andExpect(status().isOk());
        ProductFile zip = currentFiles(ebookProduct).get(0);
        mvc.perform(patch("/api/store/products/" + ebookProduct.getId() + "/files/" + zip.getId()).with(as(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"Starter schedule\",\"note\":\"A feeding plan\",\"accountOnly\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products[0].files[0].label").value("Starter schedule"))
                .andExpect(jsonPath("$.products[0].files[0].accountOnly").value(true))
                .andExpect(jsonPath("$.changes.length()").value(3));
        assertFalse(fileRepository.findById(zip.getId()).orElseThrow().isAccountOnly(), "a draft until published");
        mvc.perform(post("/api/store/publish").with(as(author))).andExpect(status().isOk());
        assertTrue(fileRepository.findById(zip.getId()).orElseThrow().isAccountOnly());

        mvc.perform(get("/api/public/stores/" + store.getSlug() + "/products/" + ebookProduct.getId()))
                .andExpect(jsonPath("$.included.length()").value(2))
                .andExpect(jsonPath("$.included[1].label").value("Starter schedule"))
                .andExpect(jsonPath("$.included[1].accountOnly").value(true))
                .andExpect(jsonPath("$.included[1].filename").doesNotExist())
                .andExpect(jsonPath("$.downloadLimit").value(5));

        // Guest: the book only; the perk is listed as locked and can't be downloaded.
        StoreOrder order = checkout(ebookProduct, null);
        webhook(completed(evt(), order, "paid", "guest-" + suffix() + "@example.com")).andExpect(status().isOk());
        String token = sentToken();
        mvc.perform(get("/api/public/downloads/" + token))
                .andExpect(jsonPath("$.files.length()").value(1))
                .andExpect(jsonPath("$.perks[0].label").value("Starter schedule"));
        mvc.perform(get("/api/public/downloads/" + token + "/files/" + zip.getId())).andExpect(status().isNotFound());

        // Account: everything, perk included.
        User buyer = user("perk-buyer", true);
        StoreOrder bought = buy(ebookProduct, buyer, "paid");
        JsonNode files = purchases(buyer).get(0).get("files");
        assertEquals(2, files.size());
        assertTrue(files.get(1).get("accountOnly").asBoolean());
        downloadAsBuyer(buyer, bought, zip.getId().toString());
    }

    @Test
    void theSuccessPageListsTheFilesOnlyForTheBuyersCheckoutSession() throws Exception {
        StoreOrder order = checkout(ebookProduct, null);
        String session = order.getCheckoutSessionId();
        mvc.perform(get("/api/public/orders/" + order.getId()).param("session_id", session))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.files").doesNotExist());
        String email = "guest-" + suffix() + "@example.com";
        webhook(completed(evt(), order, "paid", email)).andExpect(status().isOk());

        mvc.perform(get("/api/public/orders/" + order.getId()))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.buyerEmail").doesNotExist())
                .andExpect(jsonPath("$.files").doesNotExist());
        mvc.perform(get("/api/public/orders/" + order.getId()).param("session_id", session))
                .andExpect(jsonPath("$.buyerEmail").value(email))
                .andExpect(jsonPath("$.amount").value(PRICE))
                .andExpect(jsonPath("$.files[0].id").value(ProductFileService.EBOOK_PDF_ID))
                .andExpect(jsonPath("$.files[0].downloadsLeft").value(5));

        String file = "/api/public/orders/" + order.getId() + "/files/" + ProductFileService.EBOOK_PDF_ID;
        mvc.perform(get(file).param("session_id", "cs_wrong")).andExpect(status().isNotFound());
        mvc.perform(get(file)).andExpect(status().isNotFound());
        assertArrayEquals(EBOOK_PDF, mvc.perform(get(file).param("session_id", session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        // Same grant as the emailed link: the count is shared.
        mvc.perform(get("/api/public/downloads/" + sentToken()))
                .andExpect(jsonPath("$.files[0].downloadsLeft").value(4));
    }

    @Test
    void eachFileHasItsOwnDownloadLimit() throws Exception {
        upload(ebookProduct, "Workbook.pdf", GUIDE_PDF).andExpect(status().isOk());
        ProductFile workbook = currentFiles(ebookProduct).get(0);
        StoreOrder order = checkout(ebookProduct, null);
        webhook(completed(evt(), order, "paid", "guest-" + suffix() + "@example.com")).andExpect(status().isOk());
        String token = sentToken();
        for (int i = 0; i < DownloadGrantService.MAX_DOWNLOADS; i++) {
            mvc.perform(get("/api/public/downloads/" + token + "/files/" + workbook.getId())).andExpect(status().isOk());
        }
        mvc.perform(get("/api/public/downloads/" + token + "/files/" + workbook.getId())).andExpect(status().isGone());
        // The other file still has all of its downloads.
        mvc.perform(get("/api/public/downloads/" + token))
                .andExpect(jsonPath("$.files[0].downloadsLeft").value(5))
                .andExpect(jsonPath("$.files[1].downloadsLeft").value(0));
        mvc.perform(get("/api/public/downloads/" + token + "/files/" + ProductFileService.EBOOK_PDF_ID)).andExpect(status().isOk());
    }

    @Test
    void draftChangesWaitForPublishAndCanBeDiscarded() throws Exception {
        mvc.perform(patch("/api/store/products/" + ebookProduct.getId()).with(as(author)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"priceCents\":899}"))
                .andExpect(jsonPath("$.products[0].priceCents").value(899))
                .andExpect(jsonPath("$.products[0].livePriceCents").value(PRICE))
                .andExpect(jsonPath("$.changes[0].field").value("priceCents"))
                .andExpect(jsonPath("$.draftSavedAt").exists());
        // Checkout charges the live price until the author publishes.
        assertEquals(PRICE, checkout(ebookProduct, null).getAmount());

        // Back to the live value: nothing pending any more.
        mvc.perform(patch("/api/store/products/" + ebookProduct.getId()).with(as(author)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"priceCents\":" + PRICE + "}"))
                .andExpect(jsonPath("$.changes.length()").value(0))
                .andExpect(jsonPath("$.draftSavedAt").doesNotExist());

        mvc.perform(patch("/api/store/products/" + ebookProduct.getId()).with(as(author)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Sourdough, Simply\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/store/draft/discard").with(as(author)))
                .andExpect(jsonPath("$.products[0].title").value("Sourdough at Home"))
                .andExpect(jsonPath("$.changes.length()").value(0));

        mvc.perform(patch("/api/store/products/" + ebookProduct.getId()).with(as(author)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"priceCents\":899}")).andExpect(status().isOk());
        mvc.perform(post("/api/store/publish").with(as(author))).andExpect(status().isOk());
        assertEquals(899, checkout(ebookProduct, null).getAmount());
    }

    @Test
    void aRemovedProductLeavesTheStoreButBuyersKeepIt() throws Exception {
        User buyer = user("keeps-it", true);
        StoreOrder order = buy(ebookProduct, buyer, "paid");
        mvc.perform(delete("/api/store/products/" + ebookProduct.getId()).with(as(otherAuthor))).andExpect(status().isNotFound());
        mvc.perform(delete("/api/store/products/" + ebookProduct.getId()).with(as(author)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products.length()").value(0));
        mvc.perform(get("/api/public/stores/" + store.getSlug())).andExpect(jsonPath("$.products.length()").value(0));
        checkoutRequest(ebookProduct.getId(), null).andExpect(status().isNotFound());
        assertArrayEquals(EBOOK_PDF, downloadAsBuyer(buyer, order, ProductFileService.EBOOK_PDF_ID));

        // The ebook can be added back — as the same product.
        mvc.perform(post("/api/store/products").with(as(author)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ebookId\":\"" + ebookProduct.getEbookId() + "\",\"priceCents\":700,\"featured\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products[0].id").value(ebookProduct.getId().toString()))
                .andExpect(jsonPath("$.products[0].active").value(true))
                .andExpect(jsonPath("$.products[0].liveActive").value(false));
    }

    @Test
    void theStoreAddressCanBeCheckedBeforeOpening() throws Exception {
        mvc.perform(get("/api/store/slug-available").param("slug", store.getSlug()).with(as(otherAuthor)))
                .andExpect(jsonPath("$.available").value(false));
        mvc.perform(get("/api/store/slug-available").param("slug", "free-" + suffix()).with(as(otherAuthor)))
                .andExpect(jsonPath("$.available").value(true));
        mvc.perform(get("/api/store/slug-available").param("slug", "-Bad").with(as(otherAuthor)))
                .andExpect(jsonPath("$.available").value(false));
    }

    // ---- Helpers ------------------------------------------------------------------

    private ResultActions upload(StoreProduct product, String name, byte[] bytes) throws Exception {
        return mvc.perform(multipart("/api/store/products/" + product.getId() + "/files")
                .file(part(name, bytes)).with(as(author)));
    }

    private static MockMultipartFile part(String name, byte[] bytes) {
        // The client's content type is deliberately wrong: the server never trusts it.
        return new MockMultipartFile("file", name, "text/html", bytes);
    }

    private List<ProductFile> currentFiles(StoreProduct product) {
        return fileRepository.findByProductIdAndRemovedAtIsNullOrderBySortOrderAscCreatedAtAsc(product.getId());
    }

    private StoreOrder buy(StoreProduct product, User buyer, String paymentStatus) throws Exception {
        StoreOrder order = checkout(product, null);
        webhook(completed(evt(), order, paymentStatus, buyer.getEmail())).andExpect(status().isOk());
        return orderRepository.findById(order.getId()).orElseThrow();
    }

    private StoreOrder checkout(StoreProduct product, User buyer) throws Exception {
        String body = checkoutRequest(product.getId(), buyer).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return orderRepository.findById(UUID.fromString(json.readTree(body).get("orderId").asText())).orElseThrow();
    }

    private ResultActions checkoutRequest(UUID productId, User buyer) throws Exception {
        var request = post("/api/public/checkout").contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":\"" + productId + "\"}");
        if (buyer != null) {
            request.with(as(buyer));
        }
        return mvc.perform(request);
    }

    private JsonNode purchases(User buyer) throws Exception {
        return json.readTree(mvc.perform(get("/api/purchases").with(as(buyer))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private ResultActions link(User buyer, StoreOrder order, String fileId) throws Exception {
        return mvc.perform(post("/api/purchases/" + order.getId() + "/files/" + fileId + "/download-link").with(as(buyer)));
    }

    private byte[] downloadAsBuyer(User buyer, StoreOrder order, String fileId) throws Exception {
        String url = json.readTree(link(buyer, order, fileId).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("url").asText();
        assertTrue(url.startsWith("/api/public/purchase-files?token="), url);
        return mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    }

    private String sentToken() {
        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(mailService, atLeastOnce()).sendStorePurchaseEmail(anyString(), any(), any(), url.capture(), anyBoolean());
        String link = url.getValue();
        return link.substring(link.indexOf('#') + 1);
    }

    private User user(String name, boolean verified) {
        return userRepository.save(User.builder().email(name + "-" + suffix() + "@example.com")
                .displayName(name).password("x").enabled(verified).build());
    }

    private void setPro(User user) {
        subscriptionRepository.save(UserSubscription.builder().userId(user.getId()).status("active")
                .stripeSubscriptionId("sub_test").build());
    }

    private static RequestPostProcessor as(User user) {
        return SecurityMockMvcRequestPostProcessors.user(user.getEmail());
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String evt() {
        return "evt_" + UUID.randomUUID().toString().replace("-", "");
    }

    private ResultActions webhook(String payload) throws Exception {
        return mvc.perform(post("/api/stripe/connect/webhook").contentType(MediaType.APPLICATION_JSON)
                .content(payload).header("Stripe-Signature", sign(payload)));
    }

    private String completed(String eventId, StoreOrder order, String paymentStatus, String email) {
        String id = order.getId().toString();
        String session = """
                {"id":"%s","object":"checkout.session","mode":"payment","payment_status":"%s",
                 "status":"complete","amount_total":%d,"currency":"usd","client_reference_id":"%s",
                 "metadata":{"order_id":"%s"},"payment_intent":"pi_%s",
                 "customer_details":{"email":"%s"},"consent":{"terms_of_service":"accepted"}}
                """.formatted(order.getCheckoutSessionId(), paymentStatus, order.getAmount(), id, id, id.substring(0, 8), email);
        return envelope(eventId, "checkout.session.completed", session);
    }

    private String chargeRefunded(String eventId, StoreOrder order) {
        String charge = """
                {"id":"ch_%s","object":"charge","amount":%d,"amount_refunded":%d,"refunded":true,
                 "currency":"usd","payment_intent":"pi_%s"}
                """.formatted(suffix(), order.getAmount(), order.getAmount(), order.getId().toString().substring(0, 8));
        return envelope(eventId, "charge.refunded", charge);
    }

    private String envelope(String eventId, String type, String object) {
        return """
                {"id":"%s","object":"event","api_version":"2026-06-24.dahlia","account":"%s",
                 "created":%d,"livemode":false,"type":"%s","data":{"object":%s}}
                """.formatted(eventId, account, System.currentTimeMillis() / 1000L, type, object);
    }

    private static String sign(String payload) {
        long timestamp = System.currentTimeMillis() / 1000L;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String v1 = HexFormat.of().formatHex(mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8)));
            return "t=" + timestamp + ",v1=" + v1;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
