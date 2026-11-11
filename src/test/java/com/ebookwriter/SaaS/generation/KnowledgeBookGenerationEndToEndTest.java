package com.ebookwriter.SaaS.generation;

import com.ebookwriter.SaaS.entity.BlueprintQuestion;
import com.ebookwriter.SaaS.entity.BlueprintStatus;
import com.ebookwriter.SaaS.entity.ChapterStatus;
import com.ebookwriter.SaaS.entity.CreditTransactionType;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.entity.EbookStatus;
import com.ebookwriter.SaaS.entity.GenerationMode;
import com.ebookwriter.SaaS.entity.KnowledgeStatus;
import com.ebookwriter.SaaS.entity.User;
import com.ebookwriter.SaaS.dto.blueprint.BlueprintData;
import com.ebookwriter.SaaS.repository.BlueprintQuestionRepository;
import com.ebookwriter.SaaS.repository.BookBlueprintRepository;
import com.ebookwriter.SaaS.repository.BookKnowledgeRepository;
import com.ebookwriter.SaaS.repository.EbookChapterRepository;
import com.ebookwriter.SaaS.repository.EbookPdfRepository;
import com.ebookwriter.SaaS.repository.EbookRepository;
import com.ebookwriter.SaaS.repository.UserRepository;
import com.ebookwriter.SaaS.service.MailService;
import com.ebookwriter.SaaS.service.ai.AnthropicService;
import com.ebookwriter.SaaS.service.ai.ModelChoice;
import com.ebookwriter.SaaS.service.blueprint.BookBlueprintService;
import com.ebookwriter.SaaS.service.blueprint.FakePlanner;
import com.ebookwriter.SaaS.service.credit.CreditService;
import com.ebookwriter.SaaS.service.image.OpenAiImageClient;
import com.ebookwriter.SaaS.service.knowledge.KnowledgePipelineIntegrationTest;
import com.ebookwriter.SaaS.service.knowledge.MyShopFixture;
import com.ebookwriter.SaaS.service.storage.R2StorageService;
import com.ebookwriter.SaaS.support.FakeOpenAiServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The definition of done, end to end, for the reference case
 * ("Building an Online Shop with Spring Boot" + my-shop.zip + the author's notes):
 * <pre>
 *   create ebook → upload ZIP + notes → OpenAI BookKnowledge → OpenAI blueprint →
 *   answer questions → BLUEPRINT_READY → Generate → Claude writes each chapter from
 *   knowledge + blueprint + answers → images → editor → PDF → credits settled
 * </pre>
 * Everything is the real application (REST API, async workers, H2, prompt and
 * context building, editing, image pipeline, PDF renderer, credit ledger). Only
 * the network edges are replaced: OpenAI by {@link FakeOpenAiServer} (over HTTP),
 * Claude by {@link FakeClaude} at the AnthropicService boundary, and the image
 * model / object storage by stubs.
 */
@SpringBootTest
class KnowledgeBookGenerationEndToEndTest {

    static final FakeOpenAiServer OPENAI = new FakeOpenAiServer();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String JWT_ANSWER =
            "I registered JwtAuthFilter after UsernamePasswordAuthenticationFilter, so every request was anonymous. "
                    + "Moving it with addFilterBefore fixed it.";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("openai.api-key", () -> "sk-test");
        r.add("openai.base-url", OPENAI::baseUrl);
        r.add("openai.max-retries", () -> "0");
        r.add("anthropic.max-retries", () -> "1");
        // Own in-memory DB in H2's default mode: the shared PostgreSQL-mode test DB
        // rejects the BLOB column of ebook_pdfs, and this test renders real PDFs.
        r.add("spring.datasource.url", () -> "jdbc:h2:mem:e2e-generation;DB_CLOSE_DELAY=-1");
    }

    @AfterAll
    static void stop() {
        OPENAI.close();
    }

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired EbookRepository ebookRepository;
    @Autowired EbookChapterRepository chapterRepository;
    @Autowired EbookPdfRepository pdfRepository;
    @Autowired BookKnowledgeRepository knowledgeRepository;
    @Autowired BookBlueprintRepository blueprintRepository;
    @Autowired BlueprintQuestionRepository questionRepository;
    @Autowired BookBlueprintService blueprintService;
    @Autowired com.ebookwriter.SaaS.config.properties.AnthropicProperties anthropicProperties;
    @Autowired CreditService creditService;
    @Autowired com.ebookwriter.SaaS.service.credit.CreditPricing creditPricing;
    @Autowired com.ebookwriter.SaaS.repository.EbookImageRepository imageRepository;

    /** Credits per page and the image reserve (6 illustrations × 15 + a 30-credit cover) at the default policy. */
    /** The Better-model page price (the standard price). */
    static final int RATE = 11;
    /** The Basic-model page price — new books are Basic by default. */
    static final int BASIC_RATE = 7;
    static final int IMAGE_RESERVE = 6 * 15 + 30;

    @MockitoBean MailService mailService;
    @MockitoBean AnthropicService anthropic;
    @MockitoBean OpenAiImageClient imageClient;
    @MockitoBean R2StorageService storage;

    MockMvc mvc;
    User user;
    FakeClaude claude;

    /** A deliberately inflated scope guess: Scrivetta must bound it, never write 500+ pages. */
    static final String WILD_SCOPE_GUESS = """
            {"quick": {"pagesLow": 400, "pagesHigh": 500}, "standard": {"pagesLow": 600, "pagesHigh": 700},
             "comprehensive": {"pagesLow": 900, "pagesHigh": 1000}, "contentAmount": "very_large",
             "rationale": "Inflated on purpose."}
            """;

    /** The model of every chapter writing / editing call (they carry an explicit writer). */
    private final List<String> writerModels = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** The model of every call that carries an explicit model but writes no chapter (the chapter briefs). */
    private final List<String> briefModels = new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        OPENAI.reset();
        OPENAI.respond(r -> r.system().contains("book planner of Scrivetta")
                ? FakePlanner.answer(r, FakePlanner.Mode.NORMAL)
                : r.system().contains("Scrivetta's scope analyst")
                ? FakeOpenAiServer.Reply.json(WILD_SCOPE_GUESS)
                : KnowledgePipelineIntegrationTest.modelLikeAnswer(r));

        claude = new FakeClaude();
        writerModels.clear();
        briefModels.clear();
        when(anthropic.completeDetailed(anyString(), anyString(), anyLong()))
                .thenAnswer(i -> new AnthropicService.Completion(claude.respond(i.getArgument(0), i.getArgument(1)), false));
        when(anthropic.completeDetailed(anyString(), anyString(), anyLong(), anyString()))
                .thenAnswer(i -> new AnthropicService.Completion(claude.respond(i.getArgument(0), i.getArgument(1)), false));
        when(anthropic.completeDetailed(anyString(), anyString(), anyLong(), any(ModelChoice.class)))
                .thenAnswer(i -> {
                    writerModels.add(((ModelChoice) i.getArgument(3)).model());
                    return new AnthropicService.Completion(claude.respond(i.getArgument(0), i.getArgument(1)), false);
                });
        when(anthropic.complete(anyString(), anyString(), anyLong(), any(ModelChoice.class)))
                .thenAnswer(i -> {
                    briefModels.add(((ModelChoice) i.getArgument(3)).model());
                    return claude.respond(i.getArgument(0), i.getArgument(1));
                });
        when(anthropic.complete(anyString(), anyString(), anyLong()))
                .thenAnswer(i -> claude.respond(i.getArgument(0), i.getArgument(1)));
        when(anthropic.complete(anyString(), anyString(), anyLong(), anyString()))
                .thenAnswer(i -> claude.respond(i.getArgument(0), i.getArgument(1)));

        byte[] png = png();
        when(imageClient.generate(anyString(), any())).thenReturn(new OpenAiImageClient.GeneratedImage(png, "image/png"));
        when(imageClient.generate(anyString(), any(), any())).thenReturn(new OpenAiImageClient.GeneratedImage(png, "image/png"));
        when(storage.isConfigured()).thenReturn(true);
        when(storage.download(anyString())).thenReturn(Optional.of(png));

        user = userRepository.save(User.builder().email("gen-" + UUID.randomUUID() + "@example.com")
                .displayName("Author").password("x").enabled(true).build());
        creditService.grant(user.getId(), 200 * RATE + IMAGE_RESERVE, CreditTransactionType.CREDIT_PURCHASE,
                null, null, "test credits");
    }

    /** What a finished book should have been charged: its pages, AI illustrations and AI cover. */
    private int expectedCharge(Ebook ebook) {
        List<com.ebookwriter.SaaS.entity.EbookImage> images = imageRepository.findByEbookIdOrderByCreatedAtAsc(ebook.getId());
        int illustrations = (int) images.stream()
                .filter(i -> i.getRole() == com.ebookwriter.SaaS.entity.EbookImageRole.ILLUSTRATION
                        && i.getPlacedBy() == com.ebookwriter.SaaS.entity.ContentSource.AI).count();
        boolean cover = images.stream()
                .anyMatch(i -> i.getPlacement() == com.ebookwriter.SaaS.entity.EbookImagePlacement.COVER
                        && i.getPlacedBy() == com.ebookwriter.SaaS.entity.ContentSource.AI);
        int pages = Math.max(1, Math.min(ebook.getActualPageCount(), ebook.getPageBudget()));
        return creditPricing.generationCharge(pages, ebook.getCreditsPerPage(), illustrations, cover);
    }

    private RequestPostProcessor auth() {
        return SecurityMockMvcRequestPostProcessors.user(user.getEmail());
    }

    // =====================================================================
    // The reference case, end to end
    // =====================================================================

    @Test
    void theBookIsWrittenFromTheAuthorsProjectKnowledgeAndAnswers() throws Exception {
        UUID ebookId = createBook();
        uploadMaterials(ebookId);
        learn(ebookId);
        buildBlueprint(ebookId);

        // Generating before the blueprint is ready is refused — the materials are never silently ignored.
        mvc.perform(post("/api/ebooks/" + ebookId + "/start").with(auth()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Answer or skip the remaining questions in your blueprint first."));

        answerQuestions(ebookId);
        assertEquals(BlueprintStatus.BLUEPRINT_READY, blueprintRepository.findByEbookId(ebookId).orElseThrow().getStatus());
        BlueprintData blueprint = blueprintService.getBlueprint(ebookId).orElseThrow();
        long openAiRequestsBefore = OPENAI.requests().stream().filter(r -> !r.system().contains("scope analyst")).count();
        int balanceBefore = creditService.getBalance(user.getId());

        // ---- Generate ----
        mvc.perform(post("/api/ebooks/" + ebookId + "/start").with(auth())).andExpect(status().isAccepted());
        Ebook ebook = awaitFinished(ebookId);
        assertEquals(EbookStatus.COMPLETED, ebook.getStatus(), "error: " + ebook.getErrorMessage());
        assertEquals(GenerationMode.KNOWLEDGE, ebook.getGenerationMode());

        // OpenAI understood the user; it is not used to write the book.
        assertEquals(openAiRequestsBefore, OPENAI.requests().stream().filter(r -> !r.system().contains("scope analyst")).count(),
                "no OpenAI text calls during writing (only the scope assessment before it)");
        // The blueprint is the plan: no Claude planning call.
        assertTrue(claude.calls.stream().noneMatch(FakeClaude.Call::isLegacyPlanning));
        // Basic writing quality (the default): the lighter model writes and edits every
        // chapter; the planning calls (template, images, cover) stay on the premium model.
        assertEquals(com.ebookwriter.SaaS.entity.WritingQuality.BASIC, ebook.getWritingQuality());
        assertFalse(writerModels.isEmpty());
        assertTrue(writerModels.stream().allMatch("claude-sonnet-5-5"::equals), writerModels.toString());
        // ...and the premium model prepared every chapter for it: one brief request
        // per batch of chapters, each chapter written from its brief.
        List<FakeClaude.Call> briefCalls = claude.calls.stream().filter(FakeClaude.Call::isChapterBriefs).toList();
        assertEquals(1, briefCalls.size(), "a handful of chapters are briefed in one request");
        assertEquals(List.of(anthropicProperties.getModel()), briefModels, "the briefs come from the premium model");
        assertTrue(claude.knowledgeChapterCalls().stream().allMatch(c -> c.user().contains("WRITING BRIEF FROM THE LEAD AUTHOR")
                && c.user().contains("SECTIONS for ")), "every chapter is written from its brief");

        // ---- Structure = the approved blueprint ----
        List<EbookChapter> chapters = chapterRepository.findByEbookIdOrderByChapterNumberAsc(ebookId);
        assertEquals(blueprint.chapters().stream().map(BlueprintData.Chapter::title).toList(),
                chapters.stream().map(EbookChapter::getTitle).toList());
        for (int i = 0; i < chapters.size(); i++) {
            assertEquals(blueprint.chapters().get(i).id(), chapters.get(i).getBlueprintChapterId());
            assertEquals(ChapterStatus.EDITED, chapters.get(i).getStatus());
        }
        assertEquals(blueprint.workingTitle(), ebook.getTitle());

        // ---- What Claude received: book context + chapter-specific knowledge ----
        assertEquals(chapters.size(), claude.knowledgeChapterCalls().size(), "one Claude request per chapter");
        for (FakeClaude.Call call : claude.knowledgeChapterCalls()) {
            assertTrue(call.user().contains("Title: Building an Online Shop with Spring Boot"));
            assertTrue(call.user().contains("Reader: Beginner Java developers"));
            assertTrue(call.user().contains("Name: Online Shop"));
            assertTrue(call.user().contains("Tools, methods and technologies: Java 21, Spring Boot, PostgreSQL, JWT"));
            assertTrue(call.user().contains("BOOK STRUCTURE (the approved blueprint"));
            assertFalse(call.user().contains("super-secret-value"), "no raw secrets");
            assertFalse(call.user().contains("module.exports"), "no dependency folders");
            assertFalse(call.user().contains("=== SOURCE:"), "not the raw analysis batches");
            assertTrue(call.user().length() < 40_000, "bounded request: " + call.user().length());
        }
        assertEquals(1, chapters.stream().filter(c -> c.getTitle().contains("JWT")).count(),
                "one chapter per topic (duplicate knowledge items are merged)");
        FakeClaude.Call jwt = claude.chapterCall("JWT");
        assertTrue(jwt.user().contains("AUTHOR'S OWN EXPERIENCE (problem) — The author had problems with JWT"));
        assertTrue(jwt.user().contains("A (the author's own words): " + JWT_ANSWER), "the answer reaches its chapter");
        assertTrue(jwt.user().contains("--- FILE: src/main/java/com/shop/security/SecurityConfig.java (java) ---"));
        assertTrue(jwt.user().contains("SessionCreationPolicy.STATELESS"), "real project code");
        assertTrue(jwt.user().contains("The reason for choosing JWT over server sessions is not explained."),
                "the skipped gap is flagged as NOT PROVIDED");
        assertFalse(jwt.user().contains("--- FILE: src/main/java/com/shop/product/"), "other chapters' files stay out");

        FakeClaude.Call products = claude.chapterCall("Products");
        assertTrue(products.user().contains("--- FILE: src/main/java/com/shop/product/Product.java"));
        assertFalse(products.user().contains(JWT_ANSWER), "the JWT answer is not sent to the Products chapter");
        assertFalse(products.user().contains("SecurityConfig.java (java) ---"));
        assertTrue(products.user().contains("WHAT EARLIER CHAPTERS ALREADY ESTABLISHED") && products.user().contains("Names introduced:"),
                "continuity: earlier chapters' summaries");

        // ---- Reader texts and the book template were decided once, for the whole book ----
        assertTrue(claude.calls.stream().filter(FakeClaude.Call::isBookTemplate).count() == 1);
        assertTrue(chapters.stream().allMatch(c -> c.getReaderSubtitle() != null
                && c.getReaderSubtitle().startsWith("What ") && !c.getReaderSubtitle().equals(c.getDescription())));

        // ---- The book itself is about THIS project ----
        EbookChapter jwtChapter = chapters.stream().filter(c -> c.getTitle().contains("JWT")).findFirst().orElseThrow();
        assertTrue(jwtChapter.getContent().contains("The first problem I ran into: " + JWT_ANSWER),
                () -> "JWT chapter content:\n" + jwtChapter.getContent());
        assertTrue(jwtChapter.getContent().contains("SecurityConfig.java"));
        assertFalse(String.join("\n", chapters.stream().map(EbookChapter::getContent).toList())
                .contains("Authentication is an important part of modern web applications"));

        // The editorial pass was told to keep the author's specifics.
        assertTrue(claude.calls.stream().filter(FakeClaude.Call::isEditing)
                .allMatch(c -> c.user().contains("THIS BOOK IS BASED ON THE AUTHOR'S OWN KNOWLEDGE AND EXPERIENCE")));

        // ---- Images: the existing pipeline, with project context ----
        FakeClaude.Call imagePlan = claude.calls.stream().filter(FakeClaude.Call::isImagePlanning).findFirst().orElseThrow();
        assertTrue(imagePlan.user().contains("WHAT THIS BOOK TEACHES (from the author's own material)"));
        assertTrue(imagePlan.user().contains("Online Shop"));
        assertTrue(jwtChapter.getId() != null && chapterRepository.findById(jwtChapter.getId()).orElseThrow()
                .getContent().contains("ebook-image:"), "generated image placed in the JWT chapter");

        // ---- PDF + credits ----
        assertTrue(pdfRepository.existsById(ebookId));
        assertTrue(ebook.getActualPageCount() > 0);
        assertEquals(BASIC_RATE, ebook.getCreditsPerPage(), "a Basic-model book is billed at the Basic page price");
        assertEquals(expectedCharge(ebook), ebook.getCreditsCharged(), "billed for its pages and AI images");
        assertTrue(ebook.getCreditsCharged() > ebook.getActualPageCount() * BASIC_RATE, "the AI images are billed too");
        assertEquals(balanceBefore - ebook.getCreditsCharged(), creditService.getBalance(user.getId()));
        mvc.perform(get("/api/ebooks/" + ebookId + "/download").with(auth()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF));

        // ---- Existing editor: load, preview, edit + re-render ----
        String contentJson = mvc.perform(get("/api/ebooks/" + ebookId + "/content").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.editable").value(true))
                .andExpect(jsonPath("$.chapters.length()").value(chapters.size()))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(get("/api/ebooks/" + ebookId + "/preview").with(auth()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(jwtChapter.getTitle())));
        JsonNode loaded = MAPPER.readTree(contentJson);
        ObjectNode save = MAPPER.createObjectNode();
        ArrayNode list = save.putArray("chapters");
        for (JsonNode c : loaded.path("chapters")) {
            String body = c.path("content").asText();
            if (c.path("title").asText().contains("JWT")) body += "\n\nA note I added by hand.";
            list.addObject().put("id", c.path("id").asText()).put("title", c.path("title").asText()).put("content", body);
        }
        mvc.perform(put("/api/ebooks/" + ebookId + "/content").with(auth())
                        .contentType(MediaType.APPLICATION_JSON).content(save.toString()))
                .andExpect(status().isOk());
        EbookChapter edited = chapterRepository.findById(jwtChapter.getId()).orElseThrow();
        assertTrue(edited.getContent().endsWith("A note I added by hand."));
        assertEquals(com.ebookwriter.SaaS.entity.ContentSource.USER, edited.getContentSource());
        assertEquals(chapters.size(), claude.knowledgeChapterCalls().size(), "editing never regenerates the book");
    }

    // =====================================================================
    // Comparison: brief only (A) vs. the author's knowledge (B)
    // =====================================================================

    @Test
    void aKnowledgeBasedBookDiffersClearlyFromABriefOnlyBook() throws Exception {
        // A — the legacy flow: only the brief.
        UUID legacyId = createBook();
        mvc.perform(post("/api/ebooks/" + legacyId + "/start").with(auth())).andExpect(status().isAccepted());
        Ebook legacy = awaitFinished(legacyId);
        assertEquals(EbookStatus.COMPLETED, legacy.getStatus(), legacy.getErrorMessage());
        assertEquals(GenerationMode.LEGACY, legacy.getGenerationMode());
        List<FakeClaude.Call> legacyCalls = List.copyOf(claude.calls);
        assertTrue(legacyCalls.stream().anyMatch(FakeClaude.Call::isLegacyPlanning), "legacy: Claude plans from the brief");
        String legacyPrompts = String.join("\n", legacyCalls.stream().filter(FakeClaude.Call::isLegacyChapter).map(FakeClaude.Call::user).toList());
        claude.calls.clear();

        // B — same brief + my-shop.zip + notes + knowledge + blueprint + answers.
        UUID knowledgeId = createBook();
        uploadMaterials(knowledgeId);
        learn(knowledgeId);
        buildBlueprint(knowledgeId);
        answerQuestions(knowledgeId);
        mvc.perform(post("/api/ebooks/" + knowledgeId + "/start").with(auth())).andExpect(status().isAccepted());
        assertEquals(EbookStatus.COMPLETED, awaitFinished(knowledgeId).getStatus());
        String knowledgePrompts = String.join("\n", claude.knowledgeChapterCalls().stream().map(FakeClaude.Call::user).toList());

        for (String specific : List.of("SecurityConfig.java", "JwtAuthFilter", "The author had problems with JWT",
                JWT_ANSWER, "Name: Online Shop", "Product.java", "1. Create the project")) {
            assertTrue(knowledgePrompts.contains(specific), "B must carry: " + specific);
            assertFalse(legacyPrompts.contains(specific), "A cannot know: " + specific);
        }
        String legacyBook = String.join("\n", chapterRepository.findByEbookIdOrderByChapterNumberAsc(legacyId).stream()
                .map(EbookChapter::getContent).toList());
        String knowledgeBook = String.join("\n", chapterRepository.findByEbookIdOrderByChapterNumberAsc(knowledgeId).stream()
                .map(EbookChapter::getContent).toList());
        assertTrue(legacyBook.contains("Authentication is an important part of modern web applications"));
        assertFalse(legacyBook.contains("SecurityConfig.java"));
        assertTrue(knowledgeBook.contains("SecurityConfig.java"));
        assertTrue(knowledgeBook.contains(JWT_ANSWER));
        assertNotEquals(chapterRepository.findByEbookIdOrderByChapterNumberAsc(legacyId).stream().map(EbookChapter::getTitle).toList(),
                chapterRepository.findByEbookIdOrderByChapterNumberAsc(knowledgeId).stream().map(EbookChapter::getTitle).toList());
    }

    // =====================================================================
    // Failure handling + resume + credits
    // =====================================================================

    @Test
    void aFailedChapterDoesNotDestroyTheBookAndGenerationCanResume() throws Exception {
        UUID ebookId = readyKnowledgeBook();
        int balanceBefore = creditService.getBalance(user.getId());
        claude.failWhen = c -> c.isKnowledgeChapter() && "Products".equals(c.chapterTitle());

        mvc.perform(post("/api/ebooks/" + ebookId + "/start").with(auth())).andExpect(status().isAccepted());
        Ebook failed = awaitFinished(ebookId);

        assertEquals(EbookStatus.FAILED, failed.getStatus());
        assertTrue(failed.getErrorMessage().contains("Chapter") && failed.getErrorMessage().contains("Products")
                && failed.getErrorMessage().contains("resume"), failed.getErrorMessage());
        List<EbookChapter> chapters = chapterRepository.findByEbookIdOrderByChapterNumberAsc(ebookId);
        EbookChapter productsChapter = chapters.stream().filter(c -> c.getTitle().equals("Products")).findFirst().orElseThrow();
        assertEquals(ChapterStatus.FAILED, productsChapter.getStatus());
        assertTrue(productsChapter.getGenerationError().contains("simulated Claude outage"));
        assertTrue(chapters.stream().filter(c -> c != productsChapter).allMatch(c -> c.getStatus() == ChapterStatus.WRITTEN),
                "every other chapter was written and kept");
        long productAttempts = claude.knowledgeChapterCalls().stream().filter(c -> "Products".equals(c.chapterTitle())).count();
        assertEquals(2, productAttempts, "the failed chapter got one retry at the end");
        assertEquals(balanceBefore, creditService.getBalance(user.getId()), "a failed run is fully refunded");
        mvc.perform(get("/api/ebooks/" + ebookId).with(auth()))
                .andExpect(jsonPath("$.resumable").value(true))
                .andExpect(jsonPath("$.generationMode").value("KNOWLEDGE"));

        // Resume: only the missing chapter is written, then the book is finished and billed.
        claude.failWhen = c -> false;
        int writtenBefore = claude.knowledgeChapterCalls().size();
        mvc.perform(post("/api/ebooks/" + ebookId + "/resume").with(auth())).andExpect(status().isAccepted());
        Ebook done = awaitFinished(ebookId);
        assertEquals(EbookStatus.COMPLETED, done.getStatus(), done.getErrorMessage());
        List<FakeClaude.Call> resumed = claude.knowledgeChapterCalls().subList(writtenBefore, claude.knowledgeChapterCalls().size());
        assertEquals(List.of("Products"), resumed.stream().map(FakeClaude.Call::chapterTitle).toList());
        assertTrue(chapterRepository.findByEbookIdOrderByChapterNumberAsc(ebookId).stream()
                .allMatch(c -> c.getStatus() == ChapterStatus.EDITED));
        assertEquals(expectedCharge(done), done.getCreditsCharged());
        assertEquals(balanceBefore - done.getCreditsCharged(), creditService.getBalance(user.getId()));
        mvc.perform(post("/api/ebooks/" + ebookId + "/resume").with(auth())).andExpect(status().isConflict());
    }

    @Test
    void creditsAreRespected() throws Exception {
        UUID ebookId = readyKnowledgeBook();
        // Scrivetta estimates the book from its depth, materials and blueprint; the
        // start needs credits for the estimate's high end.
        mvc.perform(get("/api/ebooks/" + ebookId + "/scope").with(auth()))
                .andExpect(jsonPath("$.aiAssessment").value("NEEDED"));
        String scopeJson = mvc.perform(post("/api/ebooks/" + ebookId + "/scope/assess").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.depth").value("QUICK"))
                .andExpect(jsonPath("$.aiAssessment").value("READY"))
                .andExpect(jsonPath("$.estimate.aiAssessed").value(true))
                .andExpect(jsonPath("$.estimate.basis").value("BLUEPRINT"))
                .andExpect(jsonPath("$.options.length()").value(3))
                .andReturn().getResponse().getContentAsString();
        int required = MAPPER.readTree(scopeJson).path("requiredCredits").asInt();
        assertTrue(required > 0);
        assertTrue(required <= com.ebookwriter.SaaS.service.ebook.ScopeEstimator.depthCap(
                com.ebookwriter.SaaS.entity.BookDepth.QUICK) * RATE + IMAGE_RESERVE, "the wild guess was bounded: " + required);

        // One credit short: refused with an explanation, nothing reserved, still a draft.
        int balance = creditService.getBalance(user.getId());
        creditService.spend(user.getId(), balance - (required - 1), CreditTransactionType.GENERATION, null, "test spend");
        mvc.perform(post("/api/ebooks/" + ebookId + "/start").with(auth()))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.required").value(required))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Quick depth")));
        assertEquals(EbookStatus.DRAFT, ebookRepository.findById(ebookId).orElseThrow().getStatus());
        assertEquals(required - 1, creditService.getBalance(user.getId()));

        // With exactly the required credits the book is finished, and the balance never goes below the overdraft floor.
        creditService.grant(user.getId(), 1, CreditTransactionType.CREDIT_PURCHASE, null, null, "top up");
        mvc.perform(post("/api/ebooks/" + ebookId + "/start").with(auth())).andExpect(status().isAccepted());
        Ebook ebook = awaitFinished(ebookId);
        assertEquals(EbookStatus.COMPLETED, ebook.getStatus(), ebook.getErrorMessage());
        assertEquals(expectedCharge(ebook), ebook.getCreditsCharged());
        assertTrue(creditService.getBalance(user.getId()) >= -10 * RATE);
        assertTrue(ebook.getActualPageCount() <= ebook.getPageBudget(), "never past the reserved ceiling");
        assertTrue(ebook.getPlannedPages() != null && ebook.getPlannedPages() > 0, "the plan's size is recorded");
        assertTrue(ebook.getEstimatedPagesHigh() != null && ebook.getEstimatedPagesHigh() >= ebook.getEstimatedPagesLow());
    }

    /** A brief-only QUICK book whose planner decides it needs far more than estimated. */
    private UUID bookPlannedFarLongerThanEstimated(int creditsToHold) throws Exception {
        UUID ebookId = createBook();
        String scopeJson = mvc.perform(post("/api/ebooks/" + ebookId + "/scope/assess").with(auth()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estimate.basis").value("BRIEF"))
                .andReturn().getResponse().getContentAsString();
        int requiredPages = MAPPER.readTree(scopeJson).path("estimate").path("pagesHigh").asInt();
        if (creditsToHold > 0) {
            int balance = creditService.getBalance(user.getId());
            creditService.spend(user.getId(), balance - creditsToHold, CreditTransactionType.GENERATION, null, "test spend");
        }
        int perChapter = requiredPages + 8;
        claude.legacyPlan = """
                {"title": "Big Book", "subtitle": "s", "targetAudience": "a", "description": "d",
                 "writingGuidelines": "g", "chapters": [
                   {"title": "One", "description": "x", "approxPages": %d},
                   {"title": "Conclusion", "description": "y", "approxPages": 4}]}
                """.formatted(perChapter);
        return ebookId;
    }

    @Test
    void aPlanLongerThanEstimatedIsWrittenWithoutPausingAndBilledForWhatItHas() throws Exception {
        UUID ebookId = bookPlannedFarLongerThanEstimated(0); // plenty of credits
        int balanceBefore = creditService.getBalance(user.getId());

        mvc.perform(post("/api/ebooks/" + ebookId + "/start").with(auth())).andExpect(status().isAccepted());
        Ebook done = awaitFinished(ebookId);

        assertEquals(EbookStatus.COMPLETED, done.getStatus(), done.getErrorMessage());
        assertNull(done.getApprovalStage(), "never paused to ask");
        assertTrue(done.getPlannedPages() > done.getEstimatedPagesHigh(), "the plan really was longer than estimated");
        assertTrue(chapterRepository.findByEbookIdOrderByChapterNumberAsc(ebookId).stream()
                .allMatch(c -> c.getStatus() == ChapterStatus.EDITED), "written in full, nothing cut");
        assertEquals(expectedCharge(done), done.getCreditsCharged(), "billed for the pages it really has");
        assertEquals(balanceBefore - done.getCreditsCharged(), creditService.getBalance(user.getId()),
                "the unused part of the hold was returned");
    }

    @Test
    void aBookThatNeedsMoreThanTheCreditsAllowEndsWithinThemWithoutPausing() throws Exception {
        // The user holds only what starting needs (the estimate's low end): the long
        // plan doesn't fit. Nothing stops — the book is brought to its planned ending
        // within the credits, and the balance never passes the overdraft floor.
        UUID probe = createBook();
        int minimum = MAPPER.readTree(mvc.perform(post("/api/ebooks/" + probe + "/scope/assess").with(auth()))
                .andReturn().getResponse().getContentAsString()).path("requiredCredits").asInt();
        UUID ebookId = bookPlannedFarLongerThanEstimated(minimum);

        mvc.perform(post("/api/ebooks/" + ebookId + "/start").with(auth())).andExpect(status().isAccepted());
        Ebook done = awaitFinished(ebookId);

        assertEquals(EbookStatus.COMPLETED, done.getStatus(), done.getErrorMessage());
        assertNull(done.getApprovalStage(), "never paused to ask");
        assertTrue(done.getActualPageCount() <= done.getPageBudget(), "never past the reserved ceiling");
        assertEquals(expectedCharge(done), done.getCreditsCharged(), "billed for what was produced only");
        assertTrue(creditService.getBalance(user.getId()) >= -10 * done.getCreditsPerPage(), "bounded overdraft");
    }

    @Test
    void writingThatRunsFarPastTheEstimateIsFinishedAndBilledWithoutPausing() throws Exception {
        UUID ebookId = createBook();
        mvc.perform(post("/api/ebooks/" + ebookId + "/scope/assess").with(auth())).andExpect(status().isOk());
        // The plan fits, but the first chapter runs very long.
        claude.legacyExtraWords = 12_000;
        mvc.perform(post("/api/ebooks/" + ebookId + "/start").with(auth())).andExpect(status().isAccepted());
        Ebook done = awaitFinished(ebookId);

        assertEquals(EbookStatus.COMPLETED, done.getStatus(), done.getErrorMessage());
        assertNull(done.getApprovalStage(), "never paused to ask");
        assertTrue(done.getActualPageCount() > done.getEstimatedPagesHigh(), "it really came out longer");
        assertTrue(chapterRepository.findByEbookIdOrderByChapterNumberAsc(ebookId).stream()
                .noneMatch(c -> c.getStatus() == ChapterStatus.DEFERRED), "nothing cut while credits allow");
        assertEquals(expectedCharge(done), done.getCreditsCharged());
    }

    @Test
    void aBetterModelBookIsWrittenByThePremiumModelAtTheStandardPrice() throws Exception {
        UUID ebookId = createBook();
        mvc.perform(put("/api/ebooks/" + ebookId + "/writing-quality").with(auth())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"writingQuality\": \"BETTER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.writingQuality").value("BETTER"));
        mvc.perform(get("/api/ebooks/" + ebookId + "/scope").with(auth()))
                .andExpect(jsonPath("$.estimate.creditsPerPage").value(RATE));

        mvc.perform(post("/api/ebooks/" + ebookId + "/start").with(auth())).andExpect(status().isAccepted());
        Ebook done = awaitFinished(ebookId);

        assertEquals(EbookStatus.COMPLETED, done.getStatus(), done.getErrorMessage());
        assertEquals(RATE, done.getCreditsPerPage());
        assertTrue(writerModels.isEmpty() || writerModels.stream().allMatch(anthropicProperties.getModel()::equals),
                writerModels.toString());
        assertTrue(claude.calls.stream().noneMatch(FakeClaude.Call::isChapterBriefs), "no briefs: it writes itself");
        assertEquals(expectedCharge(done), done.getCreditsCharged());
        mvc.perform(put("/api/ebooks/" + ebookId + "/writing-quality").with(auth())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"writingQuality\": \"BASIC\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void materialsWithoutAFinishedBlueprintCannotFallBackToTheLegacyFlow() throws Exception {
        UUID ebookId = createBook();
        uploadMaterials(ebookId);
        mvc.perform(post("/api/ebooks/" + ebookId + "/start").with(auth()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Let Scrivetta learn from your materials first."));
        learn(ebookId);
        mvc.perform(post("/api/ebooks/" + ebookId + "/start").with(auth()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Build your book blueprint first."));
        assertTrue(claude.calls.isEmpty());
    }

    // =====================================================================
    // helpers — the real user flow through the API
    // =====================================================================

    private UUID readyKnowledgeBook() throws Exception {
        UUID id = createBook();
        uploadMaterials(id);
        learn(id);
        buildBlueprint(id);
        answerQuestions(id);
        return id;
    }

    private UUID createBook() throws Exception {
        String body = MAPPER.writeValueAsString(Map.of("topic", MyShopFixture.TITLE, "language", "English",
                "targetAudience", MyShopFixture.AUDIENCE, "bookGoal", MyShopFixture.GOAL, "depth", "QUICK"));
        String json = mvc.perform(post("/api/ebooks").with(auth()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(MAPPER.readTree(json).path("id").asText());
    }

    private void uploadMaterials(UUID ebookId) throws Exception {
        mvc.perform(multipart("/api/ebooks/" + ebookId + "/knowledge/sources")
                        .file(new MockMultipartFile("file", "my-shop.zip", "application/zip", MyShopFixture.zip()))
                        .with(auth()))
                .andExpect(status().isCreated());
        mvc.perform(put("/api/ebooks/" + ebookId + "/knowledge/notes").with(auth()).contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of("text", MyShopFixture.NOTES))))
                .andExpect(status().isOk());
    }

    private void learn(UUID ebookId) throws Exception {
        mvc.perform(post("/api/ebooks/" + ebookId + "/knowledge/process").with(auth())).andExpect(status().isAccepted());
        await(() -> knowledgeRepository.findByEbookId(ebookId).orElseThrow().getStatus() == KnowledgeStatus.KNOWLEDGE_READY,
                "knowledge ready");
    }

    private void buildBlueprint(UUID ebookId) throws Exception {
        mvc.perform(post("/api/ebooks/" + ebookId + "/knowledge/continue").with(auth())).andExpect(status().isOk());
        mvc.perform(post("/api/ebooks/" + ebookId + "/blueprint/build").with(auth())).andExpect(status().isAccepted());
        await(() -> blueprintRepository.findByEbookId(ebookId).orElseThrow().getStatus() == BlueprintStatus.QUESTIONS_REQUIRED,
                "blueprint built");
    }

    /** Answer the JWT question with real experience, skip the rest. */
    private void answerQuestions(UUID ebookId) throws Exception {
        for (BlueprintQuestion q : questionRepository.findByEbookIdOrderBySortOrderAscCreatedAtAsc(ebookId)) {
            Map<String, Object> body = q.getQuestion().contains("What went wrong")
                    ? Map.of("answer", JWT_ANSWER) : Map.of("skip", true);
            mvc.perform(put("/api/ebooks/" + ebookId + "/blueprint/questions/" + q.getId()).with(auth())
                            .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(body)))
                    .andExpect(status().isOk());
        }
    }

    private Ebook awaitFinished(UUID ebookId) throws InterruptedException {
        await(() -> {
            EbookStatus s = ebookRepository.findById(ebookId).orElseThrow().getStatus();
            return s == EbookStatus.COMPLETED || s == EbookStatus.FAILED;
        }, "generation finished");
        return ebookRepository.findById(ebookId).orElseThrow();
    }

    private static void await(Supplier<Boolean> condition, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.get()) return;
            Thread.sleep(50);
        }
        fail("timed out waiting for: " + what);
    }

    private static byte[] png() throws Exception {
        BufferedImage img = new BufferedImage(60, 40, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
