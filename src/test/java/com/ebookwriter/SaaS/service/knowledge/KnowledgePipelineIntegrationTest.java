package com.ebookwriter.SaaS.service.knowledge;

import com.ebookwriter.SaaS.dto.knowledge.BookKnowledgeData;
import com.ebookwriter.SaaS.entity.BookKnowledge;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookStatus;
import com.ebookwriter.SaaS.entity.KnowledgeSourceStatus;
import com.ebookwriter.SaaS.entity.KnowledgeStatus;
import com.ebookwriter.SaaS.entity.User;
import com.ebookwriter.SaaS.repository.BookKnowledgeRepository;
import com.ebookwriter.SaaS.repository.EbookRepository;
import com.ebookwriter.SaaS.repository.KnowledgeSourceRepository;
import com.ebookwriter.SaaS.repository.UserRepository;
import com.ebookwriter.SaaS.service.MailService;
import com.ebookwriter.SaaS.service.ai.AnthropicService;
import com.ebookwriter.SaaS.service.image.OpenAiImageClient;
import com.ebookwriter.SaaS.support.FakeOpenAiServer;
import com.ebookwriter.SaaS.support.FakeOpenAiServer.Reply;
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

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end knowledge ingestion through the real HTTP API, extraction, batching,
 * OpenAI client (over HTTP), merge, consolidation and persistence (H2). Only the
 * OpenAI <em>server</em> is replaced — by {@link FakeOpenAiServer}, which answers
 * like a model would, from the material actually sent to it. Claude is mocked
 * only to prove it is never touched.
 *
 * <p>The main case is the task's reference brief: "Building an Online Shop with
 * Spring Boot" + my-shop.zip + the author's rough notes.
 */
@SpringBootTest
public class KnowledgePipelineIntegrationTest {

    static final FakeOpenAiServer OPENAI = new FakeOpenAiServer();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern SOURCE_LABEL = Pattern.compile("=== SOURCE: (.+?) \\| type: (\\w+)");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("openai.api-key", () -> "sk-test");
        r.add("openai.base-url", OPENAI::baseUrl);
        r.add("openai.max-retries", () -> "1");
        // Small batches so my-shop.zip needs several requests + one consolidation.
        r.add("knowledge.max-chars-per-chunk", () -> "6000");
    }

    @AfterAll
    static void stop() {
        OPENAI.close();
    }

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired EbookRepository ebookRepository;
    @Autowired KnowledgeSourceRepository sourceRepository;
    @Autowired BookKnowledgeRepository knowledgeRepository;
    @Autowired BookKnowledgeService knowledgeService;

    @MockitoBean MailService mailService;
    @MockitoBean AnthropicService anthropicService;
    @MockitoBean OpenAiImageClient imageClient;

    MockMvc mvc;
    User user;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        OPENAI.reset();
        String email = "author-" + UUID.randomUUID() + "@example.com";
        user = userRepository.save(User.builder().email(email).displayName("Author").password("x").enabled(true).build());
    }

    private RequestPostProcessor auth() {
        return SecurityMockMvcRequestPostProcessors.user(user.getEmail());
    }

    // ---- The reference case ---------------------------------------------------------

    @Test
    void onlineShopMaterialsBecomeStructuredBookKnowledge() throws Exception {
        OPENAI.respond(KnowledgePipelineIntegrationTest::modelLikeAnswer);

        UUID ebookId = createBook();
        uploadZip(ebookId, "my-shop.zip", MyShopFixture.zip())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceType").value("ZIP"))
                .andExpect(jsonPath("$.status").value("PARTIAL"));
        mvc.perform(put("/api/ebooks/" + ebookId + "/knowledge/notes").with(auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of("text", MyShopFixture.NOTES))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceType").value("NOTES"));
        assertEquals(KnowledgeStatus.MATERIALS_UPLOADING, knowledgeService.getStatus(ebookId));
        assertFalse(knowledgeService.isKnowledgeReady(ebookId));

        mvc.perform(post("/api/ebooks/" + ebookId + "/knowledge/process").with(auth()))
                .andExpect(status().isAccepted());
        BookKnowledge stored = awaitDone(ebookId);

        // ---- status + persistence ----
        assertEquals(KnowledgeStatus.KNOWLEDGE_READY, stored.getStatus(), "error: " + stored.getErrorMessage());
        assertTrue(knowledgeService.isKnowledgeReady(ebookId));
        BookKnowledgeData k = knowledgeService.getBookKnowledge(ebookId).orElseThrow();

        // ---- what was sent to OpenAI ----
        List<FakeOpenAiServer.Request> requests = OPENAI.requests();
        List<FakeOpenAiServer.Request> extraction = requests.stream().filter(r -> !isConsolidation(r)).toList();
        assertTrue(extraction.size() >= 2, "the archive is analysed in several bounded batches");
        assertEquals(1, requests.stream().filter(KnowledgePipelineIntegrationTest::isConsolidation).count());
        assertTrue(requests.stream().allMatch(r -> r.model().equals("gpt-5-mini")));
        assertTrue(extraction.stream().allMatch(r -> r.user().length() < 6000 + 6000), "requests stay bounded");
        String allSent = String.join("\n", extraction.stream().map(FakeOpenAiServer.Request::user).toList());
        assertTrue(allSent.contains("Book goal / purpose: " + MyShopFixture.GOAL), "brief reaches the analysis");
        assertTrue(allSent.contains("Target audience: " + MyShopFixture.AUDIENCE));
        assertTrue(extraction.get(0).user().contains("SOURCE: user-notes"), "notes are analysed first");
        assertTrue(allSent.contains("I had problems with JWT."));
        assertTrue(allSent.contains("SOURCE: src/main/java/com/shop/security/SecurityConfig.java | type: code | language: java"));
        assertTrue(allSent.contains("SessionCreationPolicy.STATELESS"), "real code content is analysed");
        assertTrue(allSent.contains("SOURCE: my-shop.zip (structure)"));
        assertFalse(allSent.contains("super-secret-value"), ".env secrets never leave the server");
        assertFalse(allSent.contains("module.exports = leftPad"), "dependencies are not analysed");
        assertFalse(allSent.contains("SOURCE: docs/README-copy.md"), "duplicate analysed once");
        // Later batches get the notes as context so code is read in light of them.
        assertTrue(extraction.get(1).user().contains("AUTHOR'S NOTES (context only"));

        // ---- the structured knowledge ----
        assertEquals(MyShopFixture.TITLE, k.book().workingTitle());
        assertEquals(MyShopFixture.GOAL, k.book().goal());
        assertEquals(MyShopFixture.AUDIENCE, k.book().targetAudience());
        assertEquals("English", k.book().language());
        assertEquals("Online Shop", k.project().name());
        assertTrue(k.project().technologies().containsAll(List.of("Java 21", "Spring Boot", "PostgreSQL", "JWT")));

        BookKnowledgeData.Topic jwt = topic(k, "JWT authentication");
        assertTrue(jwt.sources().contains("src/main/java/com/shop/security/SecurityConfig.java"),
                "bare filename citation resolved to the real path: " + jwt.sources());
        assertTrue(jwt.sources().contains("user-notes"), "topic linked to the notes too: " + jwt.sources());
        assertFalse(jwt.sources().contains("src/main/java/com/shop/Invented.java"), "invented citations are dropped");
        assertNotNull(topic(k, "Products"));
        assertNotNull(topic(k, "Orders"));
        assertNotNull(topic(k, "Database configuration"));

        assertTrue(k.userInsights().stream().anyMatch(i -> i.insight().contains("problems with JWT")
                && "problem".equals(i.kind()) && i.sources().equals(List.of("user-notes"))));
        assertEquals(List.of("Create the project", "Add dependencies", "Set up the database", "Users",
                        "Products and orders"),
                k.intendedSequence().stream().map(BookKnowledgeData.SequenceStep::step).toList());
        assertTrue(k.knowledgeGaps().stream().anyMatch(g -> g.question().contains("Why JWT")));
        assertTrue(k.importantDetails().stream().anyMatch(d -> d.detail().contains("why JWT")));
        assertFalse(k.processes().isEmpty());
        assertEquals("Consolidated: an online shop backend built step by step.", k.overallSummary());

        // Source tracking & coverage.
        Set<String> refs = new LinkedHashSet<>(k.sources().stream().map(BookKnowledgeData.SourceRef::ref).toList());
        assertTrue(refs.contains("user-notes"));
        assertTrue(refs.contains("src/main/java/com/shop/order/OrderService.java"));
        BookKnowledgeData.SourceRef copy = k.sources().stream()
                .filter(s -> s.ref().equals("docs/README-copy.md")).findFirst().orElseThrow();
        assertEquals("README.md", copy.duplicateOf());
        assertEquals(2, k.coverage().sourcesTotal());
        assertEquals(1, k.coverage().duplicates());
        assertTrue(k.coverage().filesSkippedAtExtraction() > 0, "skipped files (.env, node_modules…) stay skipped");
        assertEquals(extraction.size(), k.coverage().chunks());
        // Every batch succeeded: everything sent is analysed, nothing failed.
        assertEquals(0, k.coverage().documentsFailed());
        assertEquals(k.sources().stream().filter(BookKnowledgeData.SourceRef::analyzed).count(), k.coverage().documentsAnalyzed());
        assertEquals(k.coverage().documentsTotal() - k.coverage().duplicates() - k.coverage().documentsNotAnalyzed(),
                k.coverage().documentsAnalyzed());
        assertEquals(stored.getDocumentsAnalyzed(), k.coverage().documentsAnalyzed());

        // Usage / cost accounting.
        assertEquals(requests.size(), stored.getOpenAiCalls());
        assertTrue(stored.getInputTokens() > 0 && stored.getOutputTokens() > 0);
        assertTrue(stored.getEstimatedCostUsd() > 0);
        assertEquals(stored.getEstimatedCostUsd(), stored.getTotalEstimatedCostUsd(), 1e-12);
        assertEquals("gpt-5-mini-2026-test", stored.getModel());

        // ---- the author-facing overview ----
        mvc.perform(get("/api/ebooks/" + ebookId + "/knowledge").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("KNOWLEDGE_READY"))
                .andExpect(jsonPath("$.summary.projectName").value("Online Shop"))
                .andExpect(jsonPath("$.summary.topicsFound").value(k.topics().size()))
                .andExpect(jsonPath("$.summary.processesFound").value(k.processes().size()))
                .andExpect(jsonPath("$.summary.knowledgeGaps").value(k.knowledgeGaps().size()))
                .andExpect(jsonPath("$.summary.sourcesAnalyzed").value(2))
                .andExpect(jsonPath("$.notes").value(MyShopFixture.NOTES.strip()))
                .andExpect(jsonPath("$.sources.length()").value(2));
        mvc.perform(get("/api/ebooks/" + ebookId + "/knowledge/full").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.topics[0].sources").isArray());

        // ---- Continue → READY_FOR_BLUEPRINT; still a DRAFT, nothing generated ----
        mvc.perform(post("/api/ebooks/" + ebookId + "/knowledge/continue").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY_FOR_BLUEPRINT"))
                .andExpect(jsonPath("$.readyForBlueprint").value(true));
        assertTrue(knowledgeService.getBookKnowledge(ebookId).isPresent(), "next stage can read it");
        Ebook ebook = ebookRepository.findById(ebookId).orElseThrow();
        assertEquals(EbookStatus.DRAFT, ebook.getStatus());
        assertEquals(0, ebook.getCreditsCharged());

        // Claude is the book-writing engine and is never used for ingestion.
        verifyNoInteractions(anthropicService);
        verifyNoInteractions(imageClient);
    }

    @Test
    void rarProjectGoesThroughTheSamePipelineAsZip() throws Exception {
        OPENAI.respond(KnowledgePipelineIntegrationTest::modelLikeAnswer);
        UUID ebookId = createBook();

        // The same project as my-shop.zip, packed as a solid RAR5.
        uploadZip(ebookId, "my-shop.rar", RarKnowledgeExtractionTest.fixture("my-shop.rar"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceType").value("RAR"))
                .andExpect(jsonPath("$.status").value("PARTIAL"))
                .andExpect(jsonPath("$.documentCount").value(18));
        // Damaged / password-protected RARs are stored as FAILED with a clear reason.
        uploadZip(ebookId, "locked.rar", RarKnowledgeExtractionTest.fixture("password.rar"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorMessage").value(org.hamcrest.Matchers.containsString("password-protected")));
        uploadZip(ebookId, "evil.rar", RarKnowledgeExtractionTest.fixture("traversal.rar"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorMessage").value(org.hamcrest.Matchers.containsString("unsafe path")));
        uploadZip(ebookId, "project.7z", new byte[]{'7', 'z', 1, 2}).andExpect(status().isUnsupportedMediaType());

        mvc.perform(post("/api/ebooks/" + ebookId + "/knowledge/process").with(auth())).andExpect(status().isAccepted());
        BookKnowledge done = awaitDone(ebookId);
        assertEquals(KnowledgeStatus.KNOWLEDGE_READY, done.getStatus(), "error: " + done.getErrorMessage());

        String allSent = String.join("\n", OPENAI.requests().stream()
                .filter(r -> !isConsolidation(r)).map(FakeOpenAiServer.Request::user).toList());
        assertTrue(allSent.contains("SOURCE: my-shop.rar (structure)"));
        assertTrue(allSent.contains("SOURCE: src/main/java/com/shop/security/SecurityConfig.java | type: code | language: java | from: my-shop.rar"),
                "RAR files are labelled like ZIP files");
        assertTrue(allSent.contains("SessionCreationPolicy.STATELESS"), "real code from the RAR is analysed");
        assertFalse(allSent.contains("super-secret-value"), ".env secrets never leave the server");
        assertFalse(allSent.contains("evil"), "nothing from the rejected archive is analysed");

        BookKnowledgeData k = knowledgeService.getBookKnowledge(ebookId).orElseThrow();
        assertEquals("Online Shop", k.project().name());
        assertTrue(topic(k, "JWT authentication").sources().contains("src/main/java/com/shop/security/SecurityConfig.java"));
    }

    // ---- persistence & state rules -------------------------------------------------

    @Test
    void knowledgeIsPersistedPerBookAndOwnerScoped() throws Exception {
        OPENAI.respond(KnowledgePipelineIntegrationTest::modelLikeAnswer);
        UUID ebookId = createBook();
        setNotes(ebookId, MyShopFixture.NOTES);
        mvc.perform(post("/api/ebooks/" + ebookId + "/knowledge/process").with(auth())).andExpect(status().isAccepted());
        assertEquals(KnowledgeStatus.KNOWLEDGE_READY, awaitDone(ebookId).getStatus());
        assertEquals(1, OPENAI.requests().size(), "one batch → no consolidation call");

        // Stored as JSON in book_knowledge, readable after a fresh load.
        BookKnowledge row = knowledgeRepository.findByEbookId(ebookId).orElseThrow();
        assertNotNull(row.getKnowledgeJson());
        assertEquals(1, row.getSchemaVersion());
        assertEquals(KnowledgeAssembler.read(row.getKnowledgeJson()), knowledgeService.getBookKnowledge(ebookId).orElseThrow());

        // Another user cannot see it.
        User other = userRepository.save(User.builder().email("other-" + UUID.randomUUID() + "@example.com")
                .displayName("Other").password("x").enabled(true).build());
        mvc.perform(get("/api/ebooks/" + ebookId + "/knowledge")
                        .with(SecurityMockMvcRequestPostProcessors.user(other.getEmail())))
                .andExpect(status().isNotFound());

        // Changing the materials makes the knowledge stale until re-processed.
        uploadZip(ebookId, "extra.md", "# Extra\nPayment providers.".getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isCreated());
        assertEquals(KnowledgeStatus.MATERIALS_UPLOADING, knowledgeService.getStatus(ebookId));
        assertTrue(knowledgeService.getBookKnowledge(ebookId).isEmpty());
        mvc.perform(post("/api/ebooks/" + ebookId + "/knowledge/continue").with(auth())).andExpect(status().isConflict());
    }

    @Test
    void badFilesAreReportedWithoutBreakingTheRest() throws Exception {
        OPENAI.respond(KnowledgePipelineIntegrationTest::modelLikeAnswer);
        UUID ebookId = createBook();

        uploadZip(ebookId, "broken.pdf", "not a pdf".getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorMessage").exists());
        uploadZip(ebookId, "slides.pptx", new byte[]{1, 2, 3}).andExpect(status().isUnsupportedMediaType());
        byte[] guide = MyShopFixture.docx("Guide", "Orders reduce stock.");
        uploadZip(ebookId, "guide.docx", guide).andExpect(status().isCreated());
        uploadZip(ebookId, "guide-again.docx", guide).andExpect(status().isConflict());

        mvc.perform(post("/api/ebooks/" + ebookId + "/knowledge/process").with(auth())).andExpect(status().isAccepted());
        BookKnowledge done = awaitDone(ebookId);
        assertEquals(KnowledgeStatus.KNOWLEDGE_READY, done.getStatus());
        assertTrue(done.getWarningsJson().contains("broken.pdf could not be read"));
        assertTrue(OPENAI.requests().get(0).user().contains("SOURCE: guide.docx"));
        assertEquals(KnowledgeSourceStatus.FAILED, sourceRepository.findByEbookIdOrderByCreatedAtAsc(ebookId).get(0).getStatus());
    }

    @Test
    void oneFailedBatchStillProducesKnowledge() throws Exception {
        OPENAI.respond(r -> r.user().contains("batch 1 of") ? Reply.error(500) : modelLikeAnswer(r));
        UUID ebookId = createBook();
        uploadZip(ebookId, "my-shop.zip", MyShopFixture.zip()).andExpect(status().isCreated());

        mvc.perform(post("/api/ebooks/" + ebookId + "/knowledge/process").with(auth())).andExpect(status().isAccepted());
        BookKnowledge done = awaitDone(ebookId);
        assertEquals(KnowledgeStatus.KNOWLEDGE_READY, done.getStatus());
        assertTrue(done.getWarningsJson().contains("Batch 1 of"));
        BookKnowledgeData k = knowledgeService.getBookKnowledge(ebookId).orElseThrow();
        assertFalse(k.topics().isEmpty());

        // The documents of the failed batch are not reported as analysed.
        Set<String> inFailedBatch = new LinkedHashSet<>();
        Matcher m = SOURCE_LABEL.matcher(OPENAI.requests().stream()
                .filter(r -> r.user().contains("batch 1 of")).findFirst().orElseThrow().user());
        while (m.find()) inFailedBatch.add(m.group(1));
        assertFalse(inFailedBatch.isEmpty());
        for (BookKnowledgeData.SourceRef s : k.sources()) {
            if (inFailedBatch.contains(s.ref())) {
                assertFalse(s.analyzed(), s.ref() + " was in the failed batch");
                assertEquals(KnowledgeChunker.ANALYSIS_FAILED_REASON, s.notAnalyzedReason());
            } else if (s.duplicateOf() == null) {
                assertTrue(s.analyzed(), s.ref() + " was in a successful batch");
            }
        }
        assertEquals(inFailedBatch.size(), k.coverage().documentsFailed());
        assertEquals(k.sources().stream().filter(BookKnowledgeData.SourceRef::analyzed).count(), k.coverage().documentsAnalyzed());
        assertEquals(k.coverage().documentsAnalyzed(), done.getDocumentsAnalyzed());
        assertTrue(done.getWarningsJson().contains("incomplete"));

        // Incomplete knowledge does not continue to the blueprint; the reason names the materials.
        String firstFailed = inFailedBatch.iterator().next();
        mvc.perform(post("/api/ebooks/" + ebookId + "/knowledge/continue").with(auth()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("could not analyse")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(firstFailed)));
        assertEquals(KnowledgeStatus.KNOWLEDGE_READY, knowledgeService.getStatus(ebookId));
        mvc.perform(post("/api/ebooks/" + ebookId + "/blueprint/build").with(auth()))
                .andExpect(status().isConflict());

        // A later run that analyses everything clears the block.
        OPENAI.respond(KnowledgePipelineIntegrationTest::modelLikeAnswer);
        mvc.perform(post("/api/ebooks/" + ebookId + "/knowledge/process").with(auth())).andExpect(status().isAccepted());
        assertEquals(KnowledgeStatus.KNOWLEDGE_READY, awaitDone(ebookId).getStatus());
        assertEquals(0, knowledgeService.getBookKnowledge(ebookId).orElseThrow().coverage().documentsFailed());
        mvc.perform(post("/api/ebooks/" + ebookId + "/knowledge/continue").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY_FOR_BLUEPRINT"));
    }

    @Test
    void openAiOutageFailsGracefullyAndCanBeRetried() throws Exception {
        OPENAI.respond(r -> Reply.error(503));
        UUID ebookId = createBook();
        setNotes(ebookId, MyShopFixture.NOTES);

        mvc.perform(post("/api/ebooks/" + ebookId + "/knowledge/process").with(auth())).andExpect(status().isAccepted());
        BookKnowledge failed = awaitDone(ebookId);
        assertEquals(KnowledgeStatus.FAILED, failed.getStatus());
        assertTrue(failed.getErrorMessage().contains("try again"));
        assertTrue(knowledgeService.getBookKnowledge(ebookId).isEmpty());

        OPENAI.respond(KnowledgePipelineIntegrationTest::modelLikeAnswer);
        mvc.perform(post("/api/ebooks/" + ebookId + "/knowledge/process").with(auth())).andExpect(status().isAccepted());
        BookKnowledge retried = awaitDone(ebookId);
        assertEquals(KnowledgeStatus.KNOWLEDGE_READY, retried.getStatus());
        assertEquals(2, retried.getProcessingRuns());
    }

    @Test
    void processingNeedsMaterialAndADraft() throws Exception {
        UUID ebookId = createBook();
        mvc.perform(post("/api/ebooks/" + ebookId + "/knowledge/process").with(auth())).andExpect(status().isConflict());
        mvc.perform(get("/api/ebooks/" + ebookId + "/knowledge").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.limits.acceptedFormats.length()").value(6));

        Ebook ebook = ebookRepository.findById(ebookId).orElseThrow();
        ebook.setStatus(EbookStatus.COMPLETED);
        ebookRepository.save(ebook);
        mvc.perform(put("/api/ebooks/" + ebookId + "/knowledge/notes").with(auth())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"hi\"}"))
                .andExpect(status().isConflict());
        assertTrue(OPENAI.requests().isEmpty());
    }

    // ---- helpers ---------------------------------------------------------------------

    private UUID createBook() throws Exception {
        String body = MAPPER.writeValueAsString(Map.of(
                "topic", MyShopFixture.TITLE,
                "language", "English",
                "targetAudience", MyShopFixture.AUDIENCE,
                "bookGoal", MyShopFixture.GOAL));
        String json = mvc.perform(post("/api/ebooks").with(auth()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(MAPPER.readTree(json).path("id").asText());
        assertEquals(MyShopFixture.GOAL, ebookRepository.findById(id).orElseThrow().getBookGoal());
        return id;
    }

    private org.springframework.test.web.servlet.ResultActions uploadZip(UUID ebookId, String name, byte[] bytes) throws Exception {
        return mvc.perform(multipart("/api/ebooks/" + ebookId + "/knowledge/sources")
                .file(new MockMultipartFile("file", name, "application/octet-stream", bytes))
                .with(auth()));
    }

    private void setNotes(UUID ebookId, String text) throws Exception {
        mvc.perform(put("/api/ebooks/" + ebookId + "/knowledge/notes").with(auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of("text", text))))
                .andExpect(status().isOk());
    }

    private BookKnowledge awaitDone(UUID ebookId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            BookKnowledge k = knowledgeRepository.findByEbookId(ebookId).orElseThrow();
            if (k.getStatus() == KnowledgeStatus.KNOWLEDGE_READY || k.getStatus() == KnowledgeStatus.FAILED) return k;
            Thread.sleep(50);
        }
        fail("knowledge processing did not finish");
        return null;
    }

    private static BookKnowledgeData.Topic topic(BookKnowledgeData k, String name) {
        return k.topics().stream().filter(t -> t.name().equalsIgnoreCase(name)).findFirst()
                .orElseThrow(() -> new AssertionError("missing topic " + name + " in "
                        + k.topics().stream().map(BookKnowledgeData.Topic::name).toList()));
    }

    private static boolean isConsolidation(FakeOpenAiServer.Request r) {
        return r.system().contains("CONSOLIDATE");
    }

    /**
     * Answers like the knowledge model would: only from the SOURCE blocks actually
     * present in the request, citing them (sometimes by bare filename, once with an
     * invented file — both of which the pipeline must handle).
     */
    public static Reply modelLikeAnswer(FakeOpenAiServer.Request r) {
        if (isConsolidation(r)) {
            String marker = "RAW MERGED KNOWLEDGE FROM ALL BATCHES (JSON)\n";
            try {
                ObjectNode merged = (ObjectNode) MAPPER.readTree(r.user().substring(r.user().indexOf(marker) + marker.length()));
                merged.put("summary", "Consolidated: an online shop backend built step by step.");
                merged.remove("overallSummary");
                return Reply.json(merged.toString());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        Set<String> labels = new LinkedHashSet<>();
        Matcher m = SOURCE_LABEL.matcher(r.user());
        while (m.find()) labels.add(m.group(1));

        ObjectNode out = MAPPER.createObjectNode();
        ArrayNode topics = out.putArray("topics");
        ArrayNode processes = out.putArray("processes");
        ArrayNode insights = out.putArray("userInsights");
        ArrayNode sequence = out.putArray("intendedSequence");
        ArrayNode details = out.putArray("importantDetails");
        ArrayNode gaps = out.putArray("knowledgeGaps");
        ArrayNode examples = out.putArray("examples");
        out.put("summary", "Batch covering " + labels.size() + " sources.");

        if (labels.contains("pom.xml")) {
            ObjectNode project = out.putObject("project");
            project.put("name", "Online Shop");
            project.put("type", "web application backend");
            project.putArray("technologies").add("Java 21").add("Spring Boot").add("PostgreSQL").add("JWT");
            project.putArray("sources").add("pom.xml").add("README.md");
        }
        for (String label : labels) {
            if (label.endsWith("SecurityConfig.java")) {
                topic(topics, "JWT authentication", "Stateless security: JwtAuthFilter before the username/password filter.",
                        "SecurityConfig.java", "src/main/java/com/shop/Invented.java");
                example(examples, "Security filter chain", label);
            }
            if (label.endsWith("JwtService.java")) topic(topics, "JWT authentication", "Tokens signed with a secret, expiry from config.", label);
            if (label.endsWith("Product.java")) topic(topics, "Products", "Product entity with price and stock.", "Product.java");
            if (label.endsWith("Order.java") || label.endsWith("OrderService.java")) topic(topics, "Orders", "Orders decrement stock.", label);
            if (label.endsWith("application.properties")) topic(topics, "Database configuration", "PostgreSQL via spring.datasource.", label);
            if (label.endsWith("User.java")) topic(topics, "Users", "User entity with roles.", label);
        }
        if (labels.contains("user-notes")) {
            topic(topics, "JWT authentication", "The author struggled with it.", "user notes");
            for (String step : List.of("Create the project", "Add dependencies", "Set up the database", "Users", "Products and orders")) {
                sequence.addObject().put("step", step).putArray("sources").add("user-notes");
            }
            insights.addObject().put("insight", "The author had problems with JWT").put("kind", "problem")
                    .putArray("sources").add("user-notes");
            details.addObject().put("detail", "Explain why JWT is used").put("whyItMatters", "The author asked for it")
                    .putArray("sources").add("user-notes");
            gaps.addObject().put("question", "Why JWT instead of server sessions?").put("relatedTopic", "JWT authentication")
                    .putArray("sources").add("user-notes");
            ObjectNode process = processes.addObject();
            process.put("name", "Building the shop from scratch");
            process.putArray("steps").add("Create project").add("Dependencies").add("Database").add("Users").add("Products and orders");
            process.putArray("sources").add("user-notes");
        }
        if (labels.contains("guide.docx")) topic(topics, "Orders", "Orders reduce stock.", "guide.docx");
        return Reply.json(out.toString());
    }

    private static void topic(ArrayNode topics, String name, String description, String... sources) {
        ObjectNode t = topics.addObject();
        t.put("name", name).put("description", description).put("importance", "high");
        ArrayNode s = t.putArray("sources");
        for (String src : sources) s.add(src);
    }

    private static void example(ArrayNode examples, String title, String source) {
        examples.addObject().put("title", title).put("description", "Shows the stateless filter chain").put("kind", "code")
                .putArray("sources").add(source);
    }
}
