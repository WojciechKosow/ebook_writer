package com.ebookwriter.SaaS.service.blueprint;

import com.ebookwriter.SaaS.dto.blueprint.BlueprintData;
import com.ebookwriter.SaaS.dto.blueprint.BlueprintData.Chapter;
import com.ebookwriter.SaaS.dto.knowledge.BookKnowledgeData;
import com.ebookwriter.SaaS.service.knowledge.KnowledgeAssembler;
import com.ebookwriter.SaaS.support.FakeOpenAiServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Knowledge → chapter mapping, gap detection and question selection, without I/O. */
class BlueprintAssemblerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode plannerOutput(BookKnowledgeData k, FakePlanner.Mode mode) throws Exception {
        String prompt = "BOOK KNOWLEDGE (JSON)\n" + KnowledgeAssembler.write(k);
        var reply = FakePlanner.answer(new FakeOpenAiServer.Request("", "m", "s", prompt), mode);
        return MAPPER.readTree(reply.content());
    }

    private static Chapter chapter(BlueprintData d, String title) {
        return d.chapters().stream().filter(c -> c.title().equals(title)).findFirst().orElseThrow();
    }

    @Test
    void chaptersAreMappedToRealKnowledgeAndSources() throws Exception {
        BookKnowledgeData k = ShopKnowledgeFixture.full();
        var result = BlueprintAssembler.assemble(plannerOutput(k, FakePlanner.Mode.NORMAL), k, null, Set.of(), 8, 30);
        BlueprintData d = result.blueprint();

        assertEquals(8, d.chapters().size());
        assertEquals("What We're Building", d.chapters().get(0).title());
        for (int i = 0; i < d.chapters().size(); i++) assertEquals(i + 1, d.chapters().get(i).order());

        Chapter jwt = chapter(d, "JWT authentication");
        assertTrue(jwt.knowledgeReferences().contains(new BlueprintData.KnowledgeRef("topic", "JWT authentication")),
                "lower-cased reference matched to the real item");
        assertTrue(jwt.knowledgeReferences().contains(
                new BlueprintData.KnowledgeRef("userInsight", "The author had problems with JWT")), "the author's experience is mapped");
        assertTrue(jwt.knowledgeReferences().stream().noneMatch(r -> r.name().contains("Kubernetes")), "invented knowledge dropped");
        // Sources: cited ones resolved + inherited from the knowledge items; invented ones dropped.
        assertTrue(jwt.sourceReferences().containsAll(List.of(ShopKnowledgeFixture.SECURITY,
                ShopKnowledgeFixture.JWT_SERVICE, ShopKnowledgeFixture.NOTES)));
        assertTrue(jwt.sourceReferences().stream().noneMatch(s -> s.contains("Invented")));
        assertEquals(List.of(ShopKnowledgeFixture.NOTES), jwt.keyPoints().get(0).sources());
        assertTrue(chapter(d, "Products").sourceReferences().contains(ShopKnowledgeFixture.PRODUCT));
        assertEquals(List.of("README.md", "my-shop.zip (structure)"), d.chapters().get(0).sourceReferences());
        assertTrue(result.warnings().stream().noneMatch(w -> w.contains("not linked")), "every chapter is grounded");
    }

    @Test
    void gapsAreLinkedToChaptersAndInferableOnesAreDropped() throws Exception {
        BookKnowledgeData k = ShopKnowledgeFixture.full();
        var result = BlueprintAssembler.assemble(plannerOutput(k, FakePlanner.Mode.NORMAL), k, null, Set.of(), 8, 30);
        BlueprintData d = result.blueprint();
        Chapter jwt = chapter(d, "JWT authentication");

        assertEquals(2, d.knowledgeGaps().size(), "the Java-version gap is inferable from pom.xml");
        BlueprintData.Gap problem = d.knowledgeGaps().get(0);
        assertTrue(problem.description().contains("what went wrong"));
        assertEquals("critical", problem.severity());
        assertEquals(List.of(jwt.id()), problem.chapterIds());
        assertTrue(jwt.gapIds().containsAll(d.knowledgeGaps().stream().map(BlueprintData.Gap::id).toList()));

        assertEquals(2, result.questions().size());
        assertEquals(1, result.questions().get(0).priority());
        assertTrue(result.questions().get(0).question().contains("What went wrong"));
        assertEquals(jwt.id(), result.questions().get(0).chapterId());
        assertEquals(problem.id(), result.questions().get(0).gapId());
        assertTrue(result.questions().stream().noneMatch(q -> q.question().contains("Java version")));
    }

    @Test
    void manyGapsBecomeAFewPrioritisedQuestions() throws Exception {
        BookKnowledgeData k = ShopKnowledgeFixture.full();
        var result = BlueprintAssembler.assemble(plannerOutput(k, FakePlanner.Mode.MANY_GAPS), k, null, Set.of(), 8, 30);
        assertEquals(15, result.blueprint().knowledgeGaps().size());
        assertEquals(8, result.questions().size(), "capped — never a form");
        List<Integer> priorities = result.questions().stream().map(BlueprintAssembler.ProposedQuestion::priority).toList();
        assertEquals(priorities.stream().sorted().toList(), priorities, "most important first");
        assertEquals(1, priorities.get(0));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("7 lower-priority")));
    }

    @Test
    void completeMaterialsNeedNoQuestions() throws Exception {
        BookKnowledgeData k = ShopKnowledgeFixture.full();
        var result = BlueprintAssembler.assemble(plannerOutput(k, FakePlanner.Mode.NO_GAPS), k, null, Set.of(), 8, 30);
        assertTrue(result.blueprint().knowledgeGaps().isEmpty());
        assertTrue(result.questions().isEmpty());
    }

    @Test
    void blueprintFollowsTheActualProject() throws Exception {
        BookKnowledgeData k = ShopKnowledgeFixture.withoutOrders();
        var d = BlueprintAssembler.assemble(plannerOutput(k, FakePlanner.Mode.NORMAL), k, null, Set.of(), 8, 30).blueprint();
        assertTrue(d.chapters().stream().noneMatch(c -> c.title().contains("Order")));
        assertTrue(d.chapters().stream().allMatch(c -> c.sourceReferences().stream().noneMatch(s -> s.contains("order"))));
    }

    @Test
    void noChaptersIsAnError() {
        assertThrows(IllegalArgumentException.class, () -> BlueprintAssembler.assemble(
                MAPPER.createObjectNode(), ShopKnowledgeFixture.full(), null, Set.of(), 8, 30));
    }

    @Test
    void chapterCapIsEnforced() throws Exception {
        BookKnowledgeData k = ShopKnowledgeFixture.full();
        var result = BlueprintAssembler.assemble(plannerOutput(k, FakePlanner.Mode.NORMAL), k, null, Set.of(), 8, 3);
        assertEquals(3, result.blueprint().chapters().size());
    }

    @Test
    void authorEditsSurviveRegeneration() {
        Chapter aiProducts = new Chapter("new-1", 1, "Products", "AI purpose", List.of("catalog"), List.of(),
                List.of(new BlueprintData.KnowledgeRef("topic", "Products")), List.of("p.java"), List.of(), "AI", false);
        Chapter aiOrders = new Chapter("new-2", 2, "Orders", "AI orders", List.of(), List.of(), List.of(), List.of(), List.of(), "AI", false);
        BlueprintData fresh = new BlueprintData(1, "c", "AI Title", null, "aud", "goal", "promise", null,
                List.of(aiProducts, aiOrders), List.of(), List.of());

        Chapter mineEdited = new Chapter("old-1", 1, "Products", "My purpose", List.of(), List.of(), List.of(), List.of(), List.of(), "AI", true);
        Chapter mineAdded = new Chapter("old-2", 2, "Deploying to Railway", "How I deploy", List.of(), List.of(), List.of(), List.of(), List.of(), "AUTHOR", true);
        BlueprintData previous = new BlueprintData(1, "c", "My Title", null, "aud", "goal", "promise", null,
                List.of(mineEdited, mineAdded), List.of(), List.of("workingTitle"));

        BlueprintData merged = BlueprintAssembler.preserveAuthorEdits(fresh, previous);

        assertEquals("My Title", merged.workingTitle());
        assertEquals(List.of("Products", "Deploying to Railway", "Orders"), merged.chapters().stream().map(Chapter::title).toList());
        Chapter products = merged.chapters().get(0);
        assertEquals("My purpose", products.purpose(), "author's purpose kept");
        assertEquals(List.of("p.java"), products.sourceReferences(), "new knowledge mapping applied");
        assertTrue(products.edited());
        assertEquals("AUTHOR", merged.chapters().get(1).origin());
        assertEquals(List.of(1, 2, 3), merged.chapters().stream().map(Chapter::order).toList());
    }
}
