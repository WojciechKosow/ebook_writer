package com.ebookwriter.SaaS.service.store;

import com.ebookwriter.SaaS.entity.*;
import com.ebookwriter.SaaS.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The product page design: saving it (validated), the store's style and the
 * page design reaching the public product page, and the free preview — only the
 * chapters the author picked are readable, as sanitised HTML without images.
 */
@SpringBootTest
class ProductPageIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired UserSubscriptionRepository subscriptionRepository;
    @Autowired EbookRepository ebookRepository;
    @Autowired EbookChapterRepository chapterRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired StoreProductRepository productRepository;

    @MockitoBean StripeConnectGateway stripe;
    @MockitoBean EbookPdfRepository pdfRepository;

    MockMvc mvc;
    User author;
    Store store;
    StoreProduct product;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        author = userRepository.save(User.builder().email("page-" + UUID.randomUUID() + "@example.com")
                .displayName("Author").password("x").enabled(true).build());
        subscriptionRepository.save(UserSubscription.builder().userId(author.getId()).status("active")
                .stripeSubscriptionId("sub_test").build());
        Ebook ebook = ebookRepository.save(Ebook.builder().user(author).topic("Bread").title("Bread")
                .status(EbookStatus.COMPLETED).build());
        chapterRepository.save(EbookChapter.builder().ebook(ebook).chapterNumber(1).title("Starter")
                .content("# Starter\n\nFeed it **daily**.\n\n![secret](ebook-image:123)\n\n<script>alert(1)</script>\n\n<img src=x onerror=alert(1)>")
                .build());
        chapterRepository.save(EbookChapter.builder().ebook(ebook).chapterNumber(2).title("Shaping").content("Shape it.").build());
        chapterRepository.save(EbookChapter.builder().ebook(ebook).chapterNumber(3).title("Baking").build());
        when(pdfRepository.existsById(ebook.getId())).thenReturn(true);

        store = new Store();
        store.setOwnerId(author.getId());
        store.setSlug("p" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        store.setName("Bakery");
        store.setPublished(true);
        store.setThemeSettings("{\"v\":1,\"active\":\"modern\",\"themes\":{}}");
        store = storeRepository.save(store);

        product = new StoreProduct();
        product.setStoreId(store.getId());
        product.setEbookId(ebook.getId());
        product.setTitle("Bread");
        product.setPriceCents(900);
        product.setCurrency("usd");
        product = productRepository.save(product);
    }

    @Test
    void savedDesignAndStoreStyleReachThePublicPageAndOnlyPickedChaptersAreReadable() throws Exception {
        String doc = "{\"v\":1,\"sections\":{\"hero\":{\"buyLabel\":\"Get it\"}},\"preview\":{\"chapters\":[1]}}";
        save(doc).andExpect(status().isOk())
                .andExpect(jsonPath("$.products[0].pageSettings").value(doc))
                .andExpect(jsonPath("$.products[0].chapters.length()").value(3))
                .andExpect(jsonPath("$.products[0].chapters[2].hasText").value(false));

        String page = "/api/public/stores/" + store.getSlug() + "/products/" + product.getId();
        mvc.perform(get(page)).andExpect(status().isOk())
                .andExpect(jsonPath("$.pageSettings").value(doc))
                .andExpect(jsonPath("$.themeSettings").value(store.getThemeSettings()))
                .andExpect(jsonPath("$.sampleChapters.length()").value(1))
                .andExpect(jsonPath("$.sampleChapters[0].title").value("Starter"));

        String html = mvc.perform(get(page + "/preview/1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Starter"))
                .andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("<strong>daily</strong>"), html);
        assertFalse(html.contains("<script"), "no scripts");
        assertFalse(html.contains("<img") || html.contains("ebook-image") || html.contains("onerror"), "no images");

        // Not in the free preview — public 404, but the author can look at it in the editor.
        mvc.perform(get(page + "/preview/2")).andExpect(status().isNotFound());
        mvc.perform(get("/api/store/products/" + product.getId() + "/preview/2")
                        .with(SecurityMockMvcRequestPostProcessors.user(author.getEmail())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Shaping"));

        // Hidden product: nothing public.
        product = productRepository.findById(product.getId()).orElseThrow();
        product.setActive(false);
        productRepository.save(product);
        mvc.perform(get(page + "/preview/1")).andExpect(status().isNotFound());
    }

    @Test
    void invalidDesignsAreRefused() throws Exception {
        save("not json").andExpect(status().isBadRequest());
        save("[1,2]").andExpect(status().isBadRequest());
        save("{\"preview\":{\"chapters\":[1,2,3,4]}}").andExpect(status().isBadRequest());
        save("{\"preview\":{\"chapters\":[3]}}").andExpect(status().isBadRequest()); // no text
        save("{\"preview\":{\"chapters\":[99]}}").andExpect(status().isBadRequest());
        save("{\"preview\":{\"chapters\":[\"1\"]}}").andExpect(status().isBadRequest());
        save("{\"x\":\"" + "a".repeat(30_001) + "\"}").andExpect(status().isBadRequest());
        assertNull(productRepository.findById(product.getId()).orElseThrow().getPageSettings());

        // Blank resets.
        save("{\"preview\":{\"chapters\":[2]}}").andExpect(status().isOk());
        save("").andExpect(status().isOk());
        assertNull(productRepository.findById(product.getId()).orElseThrow().getPageSettings());
    }

    @Test
    void anotherAuthorCannotChangeOrPeekIntoTheProduct() throws Exception {
        User other = userRepository.save(User.builder().email("other-" + UUID.randomUUID() + "@example.com")
                .displayName("Other").password("x").enabled(true).build());
        Store otherStore = new Store();
        otherStore.setOwnerId(other.getId());
        otherStore.setSlug("q" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        otherStore.setName("Other");
        storeRepository.save(otherStore);
        var as = SecurityMockMvcRequestPostProcessors.user(other.getEmail());
        mvc.perform(patch("/api/store/products/" + product.getId()).with(as).contentType(MediaType.APPLICATION_JSON)
                .content("{\"pageSettings\":\"{}\"}")).andExpect(status().isNotFound());
        mvc.perform(get("/api/store/products/" + product.getId() + "/preview/1").with(as)).andExpect(status().isNotFound());
    }

    private ResultActions save(String doc) throws Exception {
        String body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                java.util.Map.of("pageSettings", doc));
        return mvc.perform(patch("/api/store/products/" + product.getId())
                .with(SecurityMockMvcRequestPostProcessors.user(author.getEmail()))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
