package com.ebookwriter.SaaS.service.blueprint;

import com.ebookwriter.SaaS.dto.blueprint.BlueprintData;
import com.ebookwriter.SaaS.dto.blueprint.BookGenerationInput;
import com.ebookwriter.SaaS.dto.knowledge.BookKnowledgeData;
import com.ebookwriter.SaaS.entity.BlueprintQuestion;
import com.ebookwriter.SaaS.entity.BlueprintStatus;
import com.ebookwriter.SaaS.entity.BookBlueprint;
import com.ebookwriter.SaaS.entity.BookKnowledge;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookStatus;
import com.ebookwriter.SaaS.entity.KnowledgeStatus;
import com.ebookwriter.SaaS.entity.QuestionStatus;
import com.ebookwriter.SaaS.entity.User;
import com.ebookwriter.SaaS.repository.BlueprintQuestionRepository;
import com.ebookwriter.SaaS.repository.BookBlueprintRepository;
import com.ebookwriter.SaaS.repository.BookKnowledgeRepository;
import com.ebookwriter.SaaS.repository.EbookRepository;
import com.ebookwriter.SaaS.repository.UserRepository;
import com.ebookwriter.SaaS.service.MailService;
import com.ebookwriter.SaaS.service.ai.AnthropicService;
import com.ebookwriter.SaaS.service.image.OpenAiImageClient;
import com.ebookwriter.SaaS.service.knowledge.KnowledgeAssembler;
import com.ebookwriter.SaaS.support.FakeOpenAiServer;
import com.ebookwriter.SaaS.support.FakeOpenAiServer.Reply;
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
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BookKnowledge → Book Blueprint → gaps → questions → answers → BLUEPRINT_READY,
 * end to end: real REST API, async build, prompt building, OpenAI client over
 * HTTP, assembly, H2 persistence. Only the OpenAI server is replaced
 * ({@link FakePlanner} answers from the knowledge actually sent). Claude is
 * mocked only to prove it is never touched.
 */
@SpringBootTest
class BlueprintPipelineIntegrationTest {

    static final FakeOpenAiServer OPENAI = new FakeOpenAiServer();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("openai.api-key", () -> "sk-test");
        r.add("openai.base-url", OPENAI::baseUrl);
        r.add("openai.max-retries", () -> "0");
    }

    @AfterAll
    static void stop() {
        OPENAI.close();
    }

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired EbookRepository ebookRepository;
    @Autowired BookKnowledgeRepository knowledgeRepository;
    @Autowired BookBlueprintRepository blueprintRepository;
    @Autowired BlueprintQuestionRepository questionRepository;
    @Autowired BookBlueprintService blueprintService;

    @MockitoBean MailService mailService;
    @MockitoBean AnthropicService anthropicService;
    @MockitoBean OpenAiImageClient imageClient;

    MockMvc mvc;
    User user;
    final AtomicReference<FakePlanner.Mode> mode = new AtomicReference<>(FakePlanner.Mode.NORMAL);

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        OPENAI.reset();
        mode.set(FakePlanner.Mode.NORMAL);
        OPENAI.respond(r -> FakePlanner.answer(r, mode.get()));
        user = userRepository.save(User.builder().email("bp-" + UUID.randomUUID() + "@example.com")
                .displayName("Author").password("x").enabled(true).build());
    }

    private RequestPostProcessor auth() {
        return SecurityMockMvcRequestPostProcessors.user(user.getEmail());
    }

    // ---- 1–8: the reference case --------------------------------------------------

    @Test
    void onlineShopKnowledgeBecomesABlueprintWithQuestionsAndAnswers() throws Exception {
        UUID ebookId = bookWithKnowledge(ShopKnowledgeFixture.full());

        build(ebookId).andExpect(status().isAccepted());
        BookBlueprint row = awaitBuilt(ebookId);

        // 1. BookKnowledge → blueprint, sent to OpenAI as the planner's grounding.
        assertEquals(BlueprintStatus.QUESTIONS_REQUIRED, row.getStatus(), "error: " + row.getErrorMessage());
        assertEquals(1, OPENAI.requests().size());
        FakeOpenAiServer.Request req = OPENAI.requests().get(0);
        JsonNode body = MAPPER.readTree(req.body());
        assertEquals("gpt-5-mini", body.path("model").asText(), "blueprint model falls back to the knowledge model");
        assertEquals("medium", body.path("reasoning_effort").asText());
        assertTrue(req.user().contains("Book goal / purpose: Teach beginners how to build the project from scratch."));
        assertTrue(req.user().contains("Target audience: Beginner Java developers"));
        assertTrue(req.user().contains("\"The author had problems with JWT\""), "the author's experience reaches the planner");
        assertTrue(req.user().contains("- " + ShopKnowledgeFixture.SECURITY), "valid source refs listed");
        assertTrue(req.system().contains("THE BOOK MUST REFLECT THE AUTHOR'S ACTUAL KNOWLEDGE"));
        assertTrue(req.system().contains("Ask at most 8 questions"));
        assertFalse(req.user().contains("\"coverage\""), "bookkeeping is not sent");

        // 2. persisted and readable.
        BlueprintData bp = blueprintService.getBlueprint(ebookId).orElseThrow();
        assertEquals("Building an Online Shop with Spring Boot", bp.workingTitle());
        assertEquals("Beginner Java developers", bp.audience());
        assertNotNull(bp.promise());

        // 3. chapters from the knowledge; 4. knowledge → chapter mapping.
        assertEquals(List.of("What We're Building", "Project setup", "Dependencies", "Database configuration", "Users",
                "JWT authentication", "Products", "Orders"), bp.chapters().stream().map(BlueprintData.Chapter::title).toList());
        BlueprintData.Chapter jwt = bp.chapters().get(5);
        assertTrue(jwt.knowledgeReferences().contains(new BlueprintData.KnowledgeRef("userInsight", "The author had problems with JWT")));
        assertTrue(jwt.sourceReferences().containsAll(List.of(ShopKnowledgeFixture.SECURITY, ShopKnowledgeFixture.JWT_SERVICE, "user-notes")));
        assertTrue(bp.chapters().stream().allMatch(c -> !c.sourceReferences().isEmpty()), "every chapter is grounded");
        assertTrue(bp.chapters().stream().flatMap(c -> c.sourceReferences().stream()).noneMatch(s -> s.contains("Invented")));

        // 5. gaps; 6. questions (inferable gap dropped, ordered by priority, linked to the chapter).
        assertEquals(2, bp.knowledgeGaps().size());
        assertTrue(bp.knowledgeGaps().stream().allMatch(g -> "OPEN".equals(g.status()) && g.questionId() != null));
        List<BlueprintQuestion> questions = questionRepository.findByEbookIdOrderBySortOrderAscCreatedAtAsc(ebookId);
        assertEquals(2, questions.size());
        assertTrue(questions.get(0).getQuestion().contains("What went wrong"));
        assertEquals(jwt.id(), questions.get(0).getChapterId());
        assertEquals(1, questions.get(0).getPriority());

        // "Here's what Scrivetta understands" over the API.
        mvc.perform(get("/api/ebooks/" + ebookId + "/blueprint").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUESTIONS_REQUIRED"))
                .andExpect(jsonPath("$.blueprint.chapters.length()").value(8))
                .andExpect(jsonPath("$.questions[0].chapterTitle").value("JWT authentication"))
                .andExpect(jsonPath("$.summary.groundedChapters").value(8))
                .andExpect(jsonPath("$.summary.open").value(2))
                .andExpect(jsonPath("$.usage.model").value("gpt-5-mini-2026-test"));
        assertTrue(blueprintService.getGenerationInput(ebookId).isEmpty(), "not ready while questions are open");

        // 7. saving answers — in the DB, not frontend state.
        String answer = "I forgot to add the JWT filter before UsernamePasswordAuthenticationFilter, so every request was anonymous.";
        answerQuestion(ebookId, questions.get(0).getId(), Map.of("answer", answer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUESTIONS_REQUIRED"));
        BlueprintQuestion saved = questionRepository.findById(questions.get(0).getId()).orElseThrow();
        assertEquals(QuestionStatus.ANSWERED, saved.getStatus());
        assertEquals(answer, saved.getAnswer());
        assertNotNull(saved.getAnsweredAt());
        answerQuestion(ebookId, questions.get(1).getId(), Map.of("answer", "")).andExpect(status().isBadRequest());

        // 8. blueprint updated after answers; the last one completes it.
        answerQuestion(ebookId, questions.get(1).getId(), Map.of("skip", true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BLUEPRINT_READY"));
        BlueprintData after = blueprintService.getBlueprint(ebookId).orElseThrow();
        assertEquals("ANSWERED", gapFor(after, questions.get(0)).status());
        assertEquals("SKIPPED", gapFor(after, questions.get(1)).status());

        // The hand-off: BookKnowledge + BookBlueprint + UserAnswers.
        BookGenerationInput input = blueprintService.getGenerationInput(ebookId).orElseThrow();
        assertEquals("Online Shop", input.knowledge().project().name());
        assertEquals(8, input.blueprint().chapters().size());
        assertEquals(1, input.answers().size());
        assertEquals(answer, input.answersFor(jwt.id()).get(0).answer());
        assertEquals(1, input.unresolvedGaps().size(), "the skipped gap stays unresolved — the writer must not invent it");

        Ebook ebook = ebookRepository.findById(ebookId).orElseThrow();
        assertEquals(EbookStatus.DRAFT, ebook.getStatus());
        assertEquals(0, ebook.getCreditsCharged());
        assertEquals(KnowledgeStatus.READY_FOR_BLUEPRINT, knowledgeRepository.findByEbookId(ebookId).orElseThrow().getStatus());
        verifyNoInteractions(anthropicService);
        verifyNoInteractions(imageClient);
    }

    @Test
    void blueprintReflectsTheProjectThatWasProvided() throws Exception {
        UUID ebookId = bookWithKnowledge(ShopKnowledgeFixture.withoutOrders());
        build(ebookId).andExpect(status().isAccepted());
        awaitBuilt(ebookId);
        BlueprintData bp = blueprintService.getBlueprint(ebookId).orElseThrow();
        assertTrue(bp.chapters().stream().noneMatch(c -> c.title().contains("Order")), "no orders module, no Orders chapter");
        assertFalse(OPENAI.requests().get(0).user().contains("OrderService"));
    }

    // ---- 9. no gaps ---------------------------------------------------------------

    @Test
    void completeMaterialsGoToReviewAndAreApproved() throws Exception {
        mode.set(FakePlanner.Mode.NO_GAPS);
        UUID ebookId = bookWithKnowledge(ShopKnowledgeFixture.full());
        build(ebookId).andExpect(status().isAccepted());
        BookBlueprint row = awaitBuilt(ebookId);

        assertEquals(BlueprintStatus.BLUEPRINT_REVIEW, row.getStatus());
        assertTrue(questionRepository.findByEbookIdOrderBySortOrderAscCreatedAtAsc(ebookId).isEmpty());
        assertTrue(blueprintService.getGenerationInput(ebookId).isEmpty(), "the author still approves the structure");

        mvc.perform(post("/api/ebooks/" + ebookId + "/blueprint/approve").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BLUEPRINT_READY"));
        BookGenerationInput input = blueprintService.getGenerationInput(ebookId).orElseThrow();
        assertTrue(input.answers().isEmpty());
        assertTrue(input.unresolvedGaps().isEmpty());
    }

    // ---- 10. many gaps ------------------------------------------------------------

    @Test
    void manyGapsLeadToAFewPrioritisedQuestions() throws Exception {
        mode.set(FakePlanner.Mode.MANY_GAPS);
        UUID ebookId = bookWithKnowledge(ShopKnowledgeFixture.full());
        build(ebookId).andExpect(status().isAccepted());
        assertEquals(BlueprintStatus.QUESTIONS_REQUIRED, awaitBuilt(ebookId).getStatus());

        List<BlueprintQuestion> qs = questionRepository.findByEbookIdOrderBySortOrderAscCreatedAtAsc(ebookId);
        assertEquals(8, qs.size());
        List<Integer> priorities = qs.stream().map(BlueprintQuestion::getPriority).toList();
        assertEquals(priorities.stream().sorted().toList(), priorities);
        BlueprintData bp = blueprintService.getBlueprint(ebookId).orElseThrow();
        assertEquals(15, bp.knowledgeGaps().size());
        assertEquals(7, bp.knowledgeGaps().stream().filter(g -> "NOT_ASKED".equals(g.status())).count());

        // Approving with open questions is refused; skipping them all completes it.
        mvc.perform(post("/api/ebooks/" + ebookId + "/blueprint/approve").with(auth())).andExpect(status().isConflict());
        for (BlueprintQuestion q : qs) answerQuestion(ebookId, q.getId(), Map.of("skip", true)).andExpect(status().isOk());
        assertEquals(BlueprintStatus.BLUEPRINT_READY, blueprintRepository.findByEbookId(ebookId).orElseThrow().getStatus());
        assertEquals(15, blueprintService.getGenerationInput(ebookId).orElseThrow().unresolvedGaps().size());
    }

    // ---- editing + regeneration ---------------------------------------------------

    @Test
    void authorEditsArePersistedAndSurviveRegeneration() throws Exception {
        UUID ebookId = bookWithKnowledge(ShopKnowledgeFixture.full());
        build(ebookId).andExpect(status().isAccepted());
        awaitBuilt(ebookId);
        BlueprintData bp = blueprintService.getBlueprint(ebookId).orElseThrow();
        List<BlueprintQuestion> qs = questionRepository.findByEbookIdOrderBySortOrderAscCreatedAtAsc(ebookId);
        answerQuestion(ebookId, qs.get(0).getId(), Map.of("answer", "The filter order was wrong.")).andExpect(status().isOk());

        // Rename the book and a chapter, move JWT first, delete "Dependencies", add a chapter.
        BlueprintData.Chapter jwt = bp.chapters().get(5);
        ArrayNode chapters = MAPPER.createArrayNode();
        chapters.addObject().put("id", jwt.id()).put("title", "Securing the API with JWT").put("purpose", "My own purpose");
        for (BlueprintData.Chapter c : bp.chapters()) {
            if (c.id().equals(jwt.id()) || c.title().equals("Dependencies")) continue;
            chapters.addObject().put("id", c.id()).put("title", c.title()).put("purpose", c.purpose());
        }
        chapters.addObject().put("title", "Deploying to Railway").put("purpose", "How I deploy it");
        ObjectNode edit = MAPPER.createObjectNode().put("workingTitle", "My Spring Shop");
        edit.set("chapters", chapters);
        mvc.perform(put("/api/ebooks/" + ebookId + "/blueprint").with(auth())
                        .contentType(MediaType.APPLICATION_JSON).content(edit.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blueprint.workingTitle").value("My Spring Shop"))
                .andExpect(jsonPath("$.blueprint.chapters.length()").value(8))
                .andExpect(jsonPath("$.blueprint.chapters[0].title").value("Securing the API with JWT"))
                .andExpect(jsonPath("$.userEdited").value(true));

        BlueprintData edited = blueprintService.getBlueprint(ebookId).orElseThrow();
        BlueprintData.Chapter first = edited.chapters().get(0);
        assertEquals(jwt.id(), first.id());
        assertEquals(1, first.order());
        assertTrue(first.edited());
        assertEquals(jwt.sourceReferences(), first.sourceReferences(), "renaming keeps the knowledge mapping");
        BlueprintData.Chapter added = edited.chapters().get(7);
        assertEquals("AUTHOR", added.origin());
        assertTrue(edited.chapters().stream().noneMatch(c -> c.title().equals("Dependencies")));
        assertEquals(List.of("workingTitle"), edited.userEditedFields());

        // Invalid edits are rejected.
        mvc.perform(put("/api/ebooks/" + ebookId + "/blueprint").with(auth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"chapters\":[{\"title\":\"  \"}]}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/ebooks/" + ebookId + "/blueprint").with(auth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"chapters\":[]}")).andExpect(status().isBadRequest());

        // Rebuilding an edited blueprint needs confirmation …
        build(ebookId).andExpect(status().isConflict());
        // … and then keeps the author's work.
        OPENAI.reset();
        OPENAI.respond(r -> FakePlanner.answer(r, FakePlanner.Mode.NORMAL));
        mvc.perform(post("/api/ebooks/" + ebookId + "/blueprint/build?force=true").with(auth())).andExpect(status().isAccepted());
        BookBlueprint rebuilt = awaitBuilt(ebookId);
        assertEquals(2, rebuilt.getGeneration());

        String prompt = OPENAI.requests().get(0).user();
        assertTrue(prompt.contains("[id: " + jwt.id() + "] Securing the API with JWT — My own purpose [edited by author]"));
        assertTrue(prompt.contains("] Deploying to Railway — How I deploy it [added by author]"));
        assertTrue(prompt.contains("A: The filter order was wrong."), "answers feed the rebuild");
        assertTrue(prompt.contains("workingTitle: My Spring Shop"));

        BlueprintData again = blueprintService.getBlueprint(ebookId).orElseThrow();
        assertEquals("My Spring Shop", again.workingTitle());
        assertTrue(again.chapters().stream().anyMatch(c -> c.title().equals("Deploying to Railway") && "AUTHOR".equals(c.origin())));
        List<String> titles = FakePlanner.titles(MAPPER.valueToTree(again.chapters()));
        BlueprintData.Chapter jwtAgain = again.chapters().stream().filter(c -> c.id().equals(jwt.id())).findFirst()
                .orElseThrow(() -> new AssertionError("edited chapter lost its id: " + titles));
        assertEquals("Securing the API with JWT", jwtAgain.title(), "author's title kept");
        assertEquals("My own purpose", jwtAgain.purpose());
        assertFalse(titles.contains("JWT authentication"), "the renamed chapter is not proposed twice: " + titles);
        assertTrue(jwtAgain.sourceReferences().contains(ShopKnowledgeFixture.SECURITY), "knowledge re-mapped");

        List<BlueprintQuestion> afterRebuild = questionRepository.findByEbookIdOrderBySortOrderAscCreatedAtAsc(ebookId);
        BlueprintQuestion kept = afterRebuild.stream().filter(q -> q.getStatus() == QuestionStatus.ANSWERED).findFirst().orElseThrow();
        assertEquals("The filter order was wrong.", kept.getAnswer(), "answers survive regeneration");
        assertEquals(jwt.id(), kept.getChapterId(), "answer still linked to the (stable) JWT chapter");
        assertEquals(2, afterRebuild.size(), "answered JWT gap is not asked again; the other open question is replaced");
    }

    // ---- guards -------------------------------------------------------------------

    @Test
    void blueprintNeedsReadyKnowledgeAndHandlesFailures() throws Exception {
        UUID ebookId = createBook();
        build(ebookId).andExpect(status().isConflict());
        assertTrue(OPENAI.requests().isEmpty());

        seedKnowledge(ebookId, ShopKnowledgeFixture.full());
        OPENAI.respond(r -> Reply.error(503));
        build(ebookId).andExpect(status().isAccepted());
        BookBlueprint failed = awaitBuilt(ebookId);
        assertEquals(BlueprintStatus.FAILED, failed.getStatus());
        assertTrue(failed.getErrorMessage().contains("try again"));
        assertTrue(blueprintService.getBlueprint(ebookId).isEmpty());

        OPENAI.respond(r -> FakePlanner.answer(r, FakePlanner.Mode.NORMAL));
        build(ebookId).andExpect(status().isAccepted());
        assertEquals(BlueprintStatus.QUESTIONS_REQUIRED, awaitBuilt(ebookId).getStatus());

        // Knowledge re-processed since → the blueprint is outdated and not handed off.
        BookKnowledge k = knowledgeRepository.findByEbookId(ebookId).orElseThrow();
        k.setProcessingRuns(k.getProcessingRuns() + 1);
        knowledgeRepository.save(k);
        mvc.perform(get("/api/ebooks/" + ebookId + "/blueprint").with(auth()))
                .andExpect(jsonPath("$.knowledgeOutdated").value(true));

        // Another user cannot read it.
        User other = userRepository.save(User.builder().email("o-" + UUID.randomUUID() + "@example.com")
                .displayName("O").password("x").enabled(true).build());
        mvc.perform(get("/api/ebooks/" + ebookId + "/blueprint").with(SecurityMockMvcRequestPostProcessors.user(other.getEmail())))
                .andExpect(status().isNotFound());
    }

    // ---- helpers ------------------------------------------------------------------

    private UUID createBook() throws Exception {
        String body = MAPPER.writeValueAsString(Map.of("topic", "Building an Online Shop with Spring Boot",
                "language", "English", "targetAudience", "Beginner Java developers",
                "bookGoal", "Teach beginners how to build the project from scratch."));
        String json = mvc.perform(post("/api/ebooks").with(auth()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(MAPPER.readTree(json).path("id").asText());
    }

    private UUID bookWithKnowledge(BookKnowledgeData knowledge) throws Exception {
        UUID id = createBook();
        seedKnowledge(id, knowledge);
        return id;
    }

    /** Store BookKnowledge exactly as stage one leaves it (KNOWLEDGE_READY). */
    private void seedKnowledge(UUID ebookId, BookKnowledgeData knowledge) {
        Ebook ebook = ebookRepository.findById(ebookId).orElseThrow();
        knowledgeRepository.save(BookKnowledge.builder().ebook(ebook).status(KnowledgeStatus.KNOWLEDGE_READY)
                .knowledgeJson(KnowledgeAssembler.write(knowledge)).schemaVersion(1).processingRuns(1).build());
    }

    private ResultActions build(UUID ebookId) throws Exception {
        return mvc.perform(post("/api/ebooks/" + ebookId + "/blueprint/build").with(auth()));
    }

    private ResultActions answerQuestion(UUID ebookId, UUID questionId, Map<String, Object> body) throws Exception {
        return mvc.perform(put("/api/ebooks/" + ebookId + "/blueprint/questions/" + questionId).with(auth())
                .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(body)));
    }

    private BookBlueprint awaitBuilt(UUID ebookId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            BookBlueprint b = blueprintRepository.findByEbookId(ebookId).orElseThrow();
            if (b.getStatus() != BlueprintStatus.BUILDING_BLUEPRINT) return b;
            Thread.sleep(50);
        }
        fail("blueprint build did not finish");
        return null;
    }

    private static BlueprintData.Gap gapFor(BlueprintData bp, BlueprintQuestion q) {
        return bp.knowledgeGaps().stream().filter(g -> g.id().equals(q.getGapId())).findFirst().orElseThrow();
    }
}
