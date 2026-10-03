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
    @Autowired CreditService creditService;

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
        when(anthropic.completeDetailed(anyString(), anyString(), anyLong()))
                .thenAnswer(i -> new AnthropicService.Completion(claude.respond(i.getArgument(0), i.getArgument(1)), false));
        when(anthropic.completeDetailed(anyString(), anyString(), anyLong(), anyString()))
                .thenAnswer(i -> new AnthropicService.Completion(claude.respond(i.getArgument(0), i.getArgument(1)), false));
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
        creditService.grant(user.getId(), 200, CreditTransactionType.CREDIT_PURCHASE, null, null, "test credits");
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
            assertTrue(call.user().contains("Technologies: Java 21, Spring Boot, PostgreSQL, JWT"));
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

        // ---- The book itself is about THIS project ----
        EbookChapter jwtChapter = chapters.stream().filter(c -> c.getTitle().contains("JWT")).findFirst().orElseThrow();
        assertTrue(jwtChapter.getContent().contains("The first problem I ran into: " + JWT_ANSWER),
                () -> "JWT chapter content:\n" + jwtChapter.getContent());
        assertTrue(jwtChapter.getContent().contains("SecurityConfig.java"));
        assertFalse(String.join("\n", chapters.stream().map(EbookChapter::getContent).toList())
                .contains("Authentication is an important part of modern web applications"));

        // The editorial pass was told to keep the author's specifics.
        assertTrue(claude.calls.stream().filter(FakeClaude.Call::isEditing)
                .allMatch(c -> c.user().contains("THIS BOOK IS BASED ON THE AUTHOR'S OWN PROJECT AND EXPERIENCE")));

        // ---- Images: the existing pipeline, with project context ----
        FakeClaude.Call imagePlan = claude.calls.stream().filter(FakeClaude.Call::isImagePlanning).findFirst().orElseThrow();
        assertTrue(imagePlan.user().contains("THE AUTHOR'S PROJECT (this book teaches it)"));
        assertTrue(imagePlan.user().contains("Online Shop"));
        assertTrue(jwtChapter.getId() != null && chapterRepository.findById(jwtChapter.getId()).orElseThrow()
                .getContent().contains("ebook-image:"), "generated image placed in the JWT chapter");

        // ---- PDF + credits ----
        assertTrue(pdfRepository.existsById(ebookId));
        assertTrue(ebook.getActualPageCount() > 0);
        assertEquals(ebook.getActualPageCount(), ebook.getCreditsCharged(), "1 credit = 1 final page");
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
        assertEquals(done.getActualPageCount(), done.getCreditsCharged());
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
                com.ebookwriter.SaaS.entity.BookDepth.QUICK), "the wild guess was bounded: " + required);

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
        assertEquals(ebook.getActualPageCount(), ebook.getCreditsCharged());
        assertTrue(creditService.getBalance(user.getId()) >= -10);
        assertTrue(ebook.getCreditsCharged() <= ebook.getPageBudget(), "never past the reserved ceiling");
        assertTrue(ebook.getPlannedPages() != null && ebook.getPlannedPages() > 0, "the plan's size is recorded");
        assertTrue(ebook.getEstimatedPagesHigh() != null && ebook.getEstimatedPagesHigh() >= ebook.getEstimatedPagesLow());
    }

    /**
     * A brief-only QUICK book whose plan comes out clearly longer than the agreed
     * estimate, for a user holding exactly what the estimate needs. Returns the id,
     * paused in AWAITING_APPROVAL after planning.
     */
    private UUID pausedAfterPlanning() throws Exception {
        UUID ebookId = createBook();
        String scopeJson = mvc.perform(post("/api/ebooks/" + ebookId + "/scope/assess").with(auth()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estimate.basis").value("BRIEF"))
                .andReturn().getResponse().getContentAsString();
        int required = MAPPER.readTree(scopeJson).path("requiredCredits").asInt();
        int balance = creditService.getBalance(user.getId());
        creditService.spend(user.getId(), balance - required, CreditTransactionType.GENERATION, null, "test spend");

        // The planner decides the subject needs far more than estimated (not a runaway).
        int perChapter = required + 8;
        claude.legacyPlan = """
                {"title": "Big Book", "subtitle": "s", "targetAudience": "a", "description": "d",
                 "writingGuidelines": "g", "chapters": [
                   {"title": "One", "description": "x", "approxPages": %d},
                   {"title": "Conclusion", "description": "y", "approxPages": 4}]}
                """.formatted(perChapter);
        mvc.perform(post("/api/ebooks/" + ebookId + "/start").with(auth())).andExpect(status().isAccepted());
        await(() -> ebookRepository.findById(ebookId).orElseThrow().getStatus() == EbookStatus.AWAITING_APPROVAL,
                "paused for approval");
        return ebookId;
    }

    @Test
    void aPlanLongerThanAgreedPausesAndAsksInsteadOfCuttingOrGrowingSilently() throws Exception {
        UUID ebookId = pausedAfterPlanning();
        Ebook paused = ebookRepository.findById(ebookId).orElseThrow();
        int balanceWhilePaused = creditService.getBalance(user.getId());

        // Nothing was written; the status tells the UI what to ask.
        assertTrue(claude.calls.stream().noneMatch(FakeClaude.Call::isLegacyChapter), "no chapter before the user decides");
        mvc.perform(get("/api/ebooks/" + ebookId).with(auth()))
                .andExpect(jsonPath("$.status").value("AWAITING_APPROVAL"))
                .andExpect(jsonPath("$.approvalStage").value("PLAN"))
                .andExpect(jsonPath("$.proposedPages").value(paused.getPlannedPages()))
                .andExpect(jsonPath("$.approvedPages").value(paused.getApprovedPages()));
        assertTrue(paused.getPlannedPages() > paused.getApprovedPages());

        // The planning prompt asked for depth, with the estimate only as orientation.
        String planning = claude.calls.stream().filter(FakeClaude.Call::isLegacyPlanning).findFirst().orElseThrow().user();
        assertTrue(planning.contains("QUICK — a short, focused book"));
        assertTrue(planning.contains("not a target, not a limit"));
        assertFalse(planning.contains("must not exceed"));

        // "Yes, continue" needs more credits reserved than the user has: refused, still paused.
        mvc.perform(post("/api/ebooks/" + ebookId + "/scope-decision").with(auth())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\": \"CONTINUE\"}"))
                .andExpect(status().isPaymentRequired());
        assertEquals(EbookStatus.AWAITING_APPROVAL, ebookRepository.findById(ebookId).orElseThrow().getStatus());
        assertEquals(balanceWhilePaused, creditService.getBalance(user.getId()));

        // "No, keep it within the agreed length": the plan is scaled, the book is finished.
        mvc.perform(post("/api/ebooks/" + ebookId + "/scope-decision").with(auth())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\": \"FIT\"}"))
                .andExpect(status().isOk());
        Ebook done = awaitFinished(ebookId);
        assertEquals(EbookStatus.COMPLETED, done.getStatus(), done.getErrorMessage());
        assertTrue(done.isFitToBudget());
        assertTrue(done.getPlannedPages() <= done.getApprovedPages(), "planned within the agreed length");
        assertEquals(2, chapterRepository.findByEbookIdOrderByChapterNumberAsc(ebookId).size(), "no chapter dropped");
        assertTrue(creditService.getBalance(user.getId()) >= -10);
    }

    @Test
    void continuingALongerBookWithEnoughCreditsWritesItInFull() throws Exception {
        UUID ebookId = pausedAfterPlanning();
        creditService.grant(user.getId(), 200, CreditTransactionType.CREDIT_PURCHASE, null, null, "top up");
        Ebook paused = ebookRepository.findById(ebookId).orElseThrow();

        mvc.perform(post("/api/ebooks/" + ebookId + "/scope-decision").with(auth())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\": \"CONTINUE\"}"))
                .andExpect(status().isOk());
        Ebook done = awaitFinished(ebookId);
        assertEquals(EbookStatus.COMPLETED, done.getStatus(), done.getErrorMessage());
        assertEquals(paused.getPlannedPages(), done.getApprovedPages(), "the user agreed to the longer book");
        assertTrue(done.getPageBudget() > paused.getPageBudget(), "the hold was extended");
        assertEquals(done.getActualPageCount(), done.getCreditsCharged(), "billed for the real pages only");
    }

    @Test
    void writingThatRunsFarPastTheAgreedLengthPausesMidBookAndContinuesWhenApproved() throws Exception {
        UUID ebookId = createBook();
        mvc.perform(post("/api/ebooks/" + ebookId + "/scope/assess").with(auth())).andExpect(status().isOk());
        // The plan fits (the default 4-chapter plan), but the first chapter runs very long.
        claude.legacyExtraWords = 12_000;
        mvc.perform(post("/api/ebooks/" + ebookId + "/start").with(auth())).andExpect(status().isAccepted());
        await(() -> ebookRepository.findById(ebookId).orElseThrow().getStatus() == EbookStatus.AWAITING_APPROVAL,
                "paused while writing");

        Ebook paused = ebookRepository.findById(ebookId).orElseThrow();
        assertEquals("WRITING", paused.getApprovalStage());
        assertTrue(paused.getProposedPages() > paused.getApprovedPages());
        long written = chapterRepository.findByEbookIdOrderByChapterNumberAsc(ebookId).stream()
                .filter(c -> c.getStatus() == ChapterStatus.WRITTEN).count();
        assertEquals(1, written, "paused right after the chapter that ran long, nothing thrown away");
        mvc.perform(post("/api/ebooks/" + ebookId + "/scope-decision").with(auth())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\": \"CANCEL\"}"))
                .andExpect(status().isConflict());

        claude.legacyExtraWords = 0;
        mvc.perform(post("/api/ebooks/" + ebookId + "/scope-decision").with(auth())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\": \"CONTINUE\"}"))
                .andExpect(status().isOk());
        Ebook done = awaitFinished(ebookId);
        assertEquals(EbookStatus.COMPLETED, done.getStatus(), done.getErrorMessage());
        assertEquals(paused.getProposedPages(), done.getApprovedPages());
        assertTrue(chapterRepository.findByEbookIdOrderByChapterNumberAsc(ebookId).stream()
                .noneMatch(c -> c.getStatus() == ChapterStatus.DEFERRED), "nothing cut");
    }

    @Test
    void cancellingAfterPlanningRefundsEverythingAndReturnsToDraft() throws Exception {
        UUID ebookId = pausedAfterPlanning();
        Ebook paused = ebookRepository.findById(ebookId).orElseThrow();
        int balanceBeforeStart = creditService.getBalance(user.getId()) + paused.getPageBudget();

        mvc.perform(post("/api/ebooks/" + ebookId + "/scope-decision").with(auth())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\": \"CANCEL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));
        assertEquals(balanceBeforeStart, creditService.getBalance(user.getId()), "the whole hold was refunded");
        assertTrue(chapterRepository.findByEbookIdOrderByChapterNumberAsc(ebookId).isEmpty());
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
