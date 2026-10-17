package com.ebookwriter.SaaS.service.store;

import com.ebookwriter.SaaS.entity.*;
import com.ebookwriter.SaaS.repository.*;
import com.ebookwriter.SaaS.service.MailService;
import com.ebookwriter.SaaS.service.storage.R2StorageService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.ByteArrayOutputStream;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The store's author profile, ebook pages, ordering, covers and images — the
 * redesign's data, through the real HTTP API and database.
 */
@SpringBootTest
class StoreProfileAndPagesIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired UserSubscriptionRepository subscriptionRepository;
    @Autowired EbookRepository ebookRepository;
    @Autowired EbookChapterRepository chapterRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired StoreProductRepository productRepository;

    @MockitoBean StripeConnectGateway stripe;
    @MockitoBean MailService mailService;
    @MockitoBean EbookPdfRepository pdfRepository;
    @MockitoBean R2StorageService storage;

    MockMvc mvc;
    User author;
    Store store;
    Ebook ebook;
    StoreProduct first;
    StoreProduct second;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        author = userRepository.save(User.builder().email("a-" + UUID.randomUUID() + "@example.com")
                .displayName("Author").password("x").enabled(true).build());
        subscriptionRepository.save(UserSubscription.builder().userId(author.getId())
                .stripeSubscriptionId("sub_t").status("active").build());

        ebook = ebookRepository.save(Ebook.builder().user(author).topic("Bread").title("Sourdough at Home")
                .subtitle("From starter to loaf").language("English").actualPageCount(120)
                .status(EbookStatus.COMPLETED).build());
        Ebook other = ebookRepository.save(Ebook.builder().user(author).topic("Bread").title("Weeknight Bread")
                .status(EbookStatus.COMPLETED).build());
        chapterRepository.save(EbookChapter.builder().ebook(ebook).chapterNumber(2).title("Reading the dough").build());
        chapterRepository.save(EbookChapter.builder().ebook(ebook).chapterNumber(1).title("Making a starter").build());

        store = new Store();
        store.setOwnerId(author.getId());
        store.setSlug("s" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        store.setName("Jane's Kitchen");
        store.setPublished(true);
        store = storeRepository.save(store);

        first = product(ebook, "Sourdough at Home", 0);
        second = product(other, "Weeknight Bread", 1);
    }

    private StoreProduct product(Ebook e, String title, int order) {
        StoreProduct p = new StoreProduct();
        p.setStoreId(store.getId());
        p.setEbookId(e.getId());
        p.setTitle(title);
        p.setPriceCents(999);
        p.setCurrency("usd");
        p.setSortOrder(order);
        return productRepository.save(p);
    }

    private RequestPostProcessor asAuthor() {
        return SecurityMockMvcRequestPostProcessors.user(author.getEmail());
    }

    @Test
    void profileIsSavedAndShownOnThePublicStore() throws Exception {
        mvc.perform(put("/api/store").with(asAuthor()).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"Jane's Kitchen","description":"Home baker for 12 years.",
                         "headline":"Bake bread you're proud of","authorName":"Jane Morrow",
                         "contactEmail":"jane@example.com","instagramUrl":"https://instagram.com/jane",
                         "tiktokUrl":"","websiteUrl":""}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headline").value("Bake bread you're proud of"));

        mvc.perform(get("/api/public/stores/" + store.getSlug()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.store.authorName").value("Jane Morrow"))
                .andExpect(jsonPath("$.store.contactEmail").value("jane@example.com"))
                .andExpect(jsonPath("$.store.instagramUrl").value("https://instagram.com/jane"))
                .andExpect(jsonPath("$.products[0].title").value("Sourdough at Home"))
                .andExpect(jsonPath("$.products[0].pageCount").value(120))
                .andExpect(jsonPath("$.products[0].coverUrl").exists());
    }

    @Test
    void designIsSavedAndShownOnThePublicStore() throws Exception {
        String design = "{\"v\":1,\"active\":\"code\",\"themes\":{\"code\":{\"accent\":\"#8AB4FF\","
                + "\"customCss\":\".hero h1 { letter-spacing: -0.05em; }\"}}}";
        mvc.perform(put("/api/store/theme").with(asAuthor()).contentType(MediaType.APPLICATION_JSON)
                        .content(new com.fasterxml.jackson.databind.ObjectMapper()
                                .writeValueAsString(java.util.Map.of("settings", design))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.themeSettings").value(design))
                .andExpect(jsonPath("$.featuredChapters[0].title").value("Making a starter"));

        mvc.perform(get("/api/public/stores/" + store.getSlug()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.themeSettings").value(design))
                .andExpect(jsonPath("$.featuredChapters[0].title").value("Making a starter"))
                .andExpect(jsonPath("$.featuredChapters[1].title").value("Reading the dough"));

        mvc.perform(put("/api/store/theme").with(asAuthor()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settings\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.themeSettings").doesNotExist());
    }

    @Test
    void designMustBeAKnownStyleWithSafeCss() throws Exception {
        for (String design : java.util.List.of(
                "not json",
                "[1,2]",
                "{\"active\":\"neon\"}",
                "{\"active\":\"classic\"}",
                "{\"active\":\"bold\",\"themes\":{\"bold\":{\"customCss\":\"body{background:url(https://evil.example/x)}\"}}}",
                "{\"active\":\"bold\",\"themes\":{\"bold\":{\"customCss\":\"@import 'https://evil.example/x.css';\"}}}",
                "{\"active\":\"bold\",\"themes\":{\"bold\":{\"customCss\":\"</style><script>alert(1)</script>\"}}}",
                "{\"active\":\"bold\",\"themes\":{\"bold\":{\"customCss\":\"a{b:\\\\75 rl(x)}\"}}}")) {
            mvc.perform(put("/api/store/theme").with(asAuthor()).contentType(MediaType.APPLICATION_JSON)
                            .content(new com.fasterxml.jackson.databind.ObjectMapper()
                                    .writeValueAsString(java.util.Map.of("settings", design))))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void linksMustBeHttps() throws Exception {
        mvc.perform(put("/api/store").with(asAuthor()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"instagramUrl\":\"javascript:alert(1)\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/store").with(asAuthor()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"contactEmail\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ebookPageShowsChaptersHighlightsAndMoreFromTheAuthor() throws Exception {
        mvc.perform(patch("/api/store/products/" + first.getId()).with(asAuthor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"highlights\":\"Keep a starter alive\\n\\nShape a loaf\\n\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/public/stores/" + store.getSlug() + "/products/" + first.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtitle").value("From starter to loaf"))
                .andExpect(jsonPath("$.language").value("English"))
                .andExpect(jsonPath("$.highlights.length()").value(2))
                .andExpect(jsonPath("$.chapters[0].title").value("Making a starter"))
                .andExpect(jsonPath("$.chapters[1].title").value("Reading the dough"))
                .andExpect(jsonPath("$.more.length()").value(1))
                .andExpect(jsonPath("$.more[0].title").value("Weeknight Bread"));
    }

    @Test
    void hiddenProductsAndUnpublishedStoresHaveNoPage() throws Exception {
        second.setActive(false);
        productRepository.save(second);
        mvc.perform(get("/api/public/stores/" + store.getSlug() + "/products/" + second.getId()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/public/stores/" + store.getSlug() + "/products/" + first.getId()))
                .andExpect(jsonPath("$.more.length()").value(0));

        store.setPublished(false);
        storeRepository.save(store);
        mvc.perform(get("/api/public/stores/" + store.getSlug() + "/products/" + first.getId()))
                .andExpect(status().isNotFound());
    }

    @Test
    void productsCanBeReordered() throws Exception {
        mvc.perform(post("/api/store/products/" + second.getId() + "/move").with(asAuthor())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"direction\":\"up\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products[0].title").value("Weeknight Bread"));
        mvc.perform(get("/api/public/stores/" + store.getSlug()))
                .andExpect(jsonPath("$.products[0].title").value("Weeknight Bread"));
    }

    @Test
    void coverIsTheFirstPageOfThePdf() throws Exception {
        when(pdfRepository.findById(ebook.getId())).thenReturn(Optional.of(new EbookPdf(ebook.getId(), blankPdf())));
        byte[] jpeg = mvc.perform(get("/api/public/products/" + first.getId() + "/cover"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andReturn().getResponse().getContentAsByteArray();
        assertEquals((byte) 0xFF, jpeg[0]);
        assertEquals((byte) 0xD8, jpeg[1]);

        mvc.perform(get("/api/public/products/" + UUID.randomUUID() + "/cover")).andExpect(status().isNotFound());
    }

    @Test
    void logoMustBeARasterImage() throws Exception {
        MockMultipartFile svg = new MockMultipartFile("file", "logo.svg", "image/png",
                "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>".getBytes());
        mvc.perform(multipart("/api/store/images/logo").file(svg).with(asAuthor()))
                .andExpect(status().isBadRequest());
        verify(storage, never()).upload(anyString(), any(), anyString());

        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
        mvc.perform(multipart("/api/store/images/logo").file(new MockMultipartFile("file", "logo.png", "image/png", png))
                        .with(asAuthor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logoUrl").exists());
        verify(storage).upload(startsWith("stores/" + store.getId() + "/logo-"), eq(png), eq("image/png"));

        when(storage.download(anyString())).thenReturn(Optional.of(png));
        mvc.perform(get("/api/public/stores/" + store.getSlug() + "/images/logo"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    private static byte[] blankPdf() throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            doc.save(out);
            return out.toByteArray();
        }
    }
}
