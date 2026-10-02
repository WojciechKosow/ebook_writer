package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.config.properties.KnowledgeWritingProperties;
import com.ebookwriter.SaaS.dto.blueprint.BlueprintData;
import com.ebookwriter.SaaS.dto.blueprint.BlueprintData.Chapter;
import com.ebookwriter.SaaS.dto.blueprint.BlueprintData.KnowledgeRef;
import com.ebookwriter.SaaS.dto.blueprint.BookGenerationInput;
import com.ebookwriter.SaaS.dto.knowledge.BookKnowledgeData;
import com.ebookwriter.SaaS.dto.knowledge.NormalizedDocument;
import com.ebookwriter.SaaS.dto.knowledge.NormalizedDocument.Kind;
import com.ebookwriter.SaaS.service.blueprint.ShopKnowledgeFixture;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.ebookwriter.SaaS.service.blueprint.ShopKnowledgeFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * What Claude receives for one chapter: the book-level context, only the
 * knowledge / answers / gaps / source files relevant to THAT chapter, all
 * size-capped. Built from the reference my-shop knowledge.
 */
class KnowledgeChapterContextTest {

    private final KnowledgeWritingProperties limits = new KnowledgeWritingProperties();
    private final BookKnowledgeData knowledge = ShopKnowledgeFixture.full();

    private final Chapter intro = chapter("c-intro", "What We're Building", List.of(), List.of("README.md", "my-shop.zip (structure)"));
    private final Chapter jwt = chapter("c-jwt", "JWT Security",
            List.of(new KnowledgeRef("topic", "JWT authentication"), new KnowledgeRef("userInsight", "The author had problems with JWT")),
            List.of(SECURITY, JWT_SERVICE, NOTES));
    private final Chapter products = chapter("c-products", "Products",
            List.of(new KnowledgeRef("topic", "Products")), List.of(PRODUCT));

    private final BlueprintData blueprint = new BlueprintData(1, "A hands-on guide to the author's shop.",
            "Building an Online Shop with Spring Boot", null, "Beginner Java developers",
            "Build a working online shop backend from scratch", "A running, secured shop API.", "Author's order",
            List.of(intro, jwt, products),
            List.of(new BlueprintData.Gap("g-why", "Why JWT over server sessions is not explained.", null, "important",
                            List.of("c-jwt"), "SKIPPED", "q2"),
                    new BlueprintData.Gap("g-book", "Target deployment is unknown.", null, "minor", List.of(), "NOT_ASKED", null)),
            List.of());

    private final BookGenerationInput.Answer jwtAnswer = new BookGenerationInput.Answer(UUID.randomUUID(), "c-jwt", "g1",
            "What went wrong with JWT?", "I registered JwtAuthFilter after UsernamePasswordAuthenticationFilter, so every request was anonymous.");
    private final BookGenerationInput.Answer bookAnswer = new BookGenerationInput.Answer(UUID.randomUUID(), null, null,
            "Who is this for?", "People who know Java basics but never built an API.");
    private final BookGenerationInput input = new BookGenerationInput(UUID.randomUUID(), knowledge, blueprint,
            List.of(jwtAnswer, bookAnswer), blueprint.knowledgeGaps());

    private final Map<String, NormalizedDocument> documents = documents();

    private static Chapter chapter(String id, String title, List<KnowledgeRef> refs, List<String> sources) {
        return new Chapter(id, 1, title, "Purpose of " + title, List.of(title), List.of(), refs, sources, List.of(), "AI", false);
    }

    private static Map<String, NormalizedDocument> documents() {
        Map<String, NormalizedDocument> m = new LinkedHashMap<>();
        m.put(NOTES, doc(NOTES, Kind.NOTES, "I had problems with JWT."));
        m.put("README.md", doc("README.md", Kind.DOCUMENT, "# my-shop\nOnline shop backend."));
        m.put(SECURITY, doc(SECURITY, Kind.CODE, "@Configuration\npublic class SecurityConfig {\n  // SessionCreationPolicy.STATELESS\n}"));
        m.put(JWT_SERVICE, doc(JWT_SERVICE, Kind.CODE, "public class JwtService {\n  public String generateToken(User user) {}\n}" + "\n//pad".repeat(2_000)));
        m.put(PRODUCT, doc(PRODUCT, Kind.CODE, "@Entity\npublic class Product { private BigDecimal price; }"));
        return m;
    }

    private static NormalizedDocument doc(String path, Kind kind, String content) {
        return new NormalizedDocument(path, kind, "java", kind == Kind.CODE ? "java" : null, content.length(), false, "h" + path, content);
    }

    @Test
    void everyChapterGetsTheBookLevelContext() {
        for (Chapter c : List.of(intro, jwt, products)) {
            var ctx = KnowledgeChapterContext.build(input, c.id(), documents, limits);
            assertTrue(ctx.book().contains("Title: Building an Online Shop with Spring Boot"));
            assertTrue(ctx.book().contains("Reader: Beginner Java developers"));
            assertTrue(ctx.book().contains("Promise: A running, secured shop API."));
            assertTrue(ctx.book().contains("Name: Online Shop"));
            assertTrue(ctx.book().contains("Technologies: Java 21, Spring Boot, PostgreSQL, JWT"));
            assertTrue(ctx.book().contains("- JWT: Signed token carrying the user's email"), "terminology for continuity");
            assertTrue(ctx.book().contains("1. Create the project"), "the author's own order of work");
        }
    }

    @Test
    void theJwtChapterGetsItsKnowledgeAnswersGapsAndSourceFiles() {
        var ctx = KnowledgeChapterContext.build(input, "c-jwt", documents, limits);

        assertTrue(ctx.knowledge().contains("Topic — JWT authentication"));
        assertTrue(ctx.knowledge().contains("AUTHOR'S OWN EXPERIENCE (problem) — The author had problems with JWT"));
        assertTrue(ctx.knowledge().contains("Technical detail — Security: Sessions are STATELESS"), "shares SecurityConfig.java");
        assertTrue(ctx.knowledge().contains("[from: " + SECURITY), "knowledge keeps its sources");
        assertFalse(ctx.knowledge().contains("Topic — Products"), "another chapter's knowledge is not sent");

        assertTrue(ctx.answers().contains("I registered JwtAuthFilter after UsernamePasswordAuthenticationFilter"));
        assertTrue(ctx.answers().contains("People who know Java basics"), "book-wide answers go to every chapter");

        assertTrue(ctx.gaps().contains("Why JWT over server sessions is not explained."));
        assertTrue(ctx.gaps().contains("Target deployment is unknown."), "book-level gap");

        assertEquals(List.of(SECURITY, JWT_SERVICE), ctx.excerptRefs(), "code files of this chapter, notes excluded");
        assertTrue(ctx.excerpts().contains("--- FILE: " + SECURITY + " (java) ---"));
        assertTrue(ctx.excerpts().contains("SessionCreationPolicy.STATELESS"));
        assertTrue(ctx.excerpts().contains("[… rest of the file omitted …]"), "long files are cut");
        assertFalse(ctx.excerpts().contains("class Product"));
    }

    @Test
    void theProductsChapterGetsOnlyProductsMaterial() {
        var ctx = KnowledgeChapterContext.build(input, "c-products", documents, limits);
        assertTrue(ctx.knowledge().contains("Topic — Products"));
        assertFalse(ctx.knowledge().contains("JWT authentication"));
        assertFalse(ctx.knowledge().contains("problems with JWT"));
        assertFalse(ctx.answers().contains("JwtAuthFilter"), "the JWT answer stays with its chapter");
        assertFalse(ctx.gaps().contains("Why JWT"));
        assertEquals(List.of(PRODUCT), ctx.excerptRefs());
    }

    @Test
    void contextStaysWithinItsLimits() {
        limits.setMaxExcerpts(1);
        limits.setMaxExcerptChars(100);
        limits.setMaxChapterKnowledgeChars(200);
        var ctx = KnowledgeChapterContext.build(input, "c-jwt", documents, limits);
        assertEquals(1, ctx.excerptRefs().size());
        assertTrue(ctx.excerpts().length() < 400);
        assertTrue(ctx.itemCount() >= 1, "explicitly mapped knowledge is always included");
        assertTrue(ctx.totalChars() < limits.getMaxBookContextChars() + 2_000);
    }

    @Test
    void aChapterWithoutABlueprintLinkStillGetsTheBook() {
        var ctx = KnowledgeChapterContext.build(input, null, documents, limits);
        assertTrue(ctx.book().contains("Online Shop"));
        assertEquals("", ctx.knowledge());
        assertTrue(ctx.excerptRefs().isEmpty());
    }

    @Test
    void blueprintChaptersAreSizedByTheirKnowledge() {
        var planned = KnowledgeBookPlanner.distribute(List.of(intro, jwt, products), 26);
        assertEquals(List.of("What We're Building", "JWT Security", "Products"), planned.stream().map(p -> p.title()).toList());
        assertTrue(planned.get(1).approxPages() > planned.get(0).approxPages(), "the knowledge-rich chapter gets more room");
        assertTrue(planned.stream().allMatch(p -> p.approxPages() >= 1));
        assertTrue(planned.get(1).description().contains("Purpose of JWT Security"));
        assertTrue(planned.get(1).description().contains("Covers: JWT Security."));
        // The hard credit ceiling still applies (legacy rule, reused): the final chapter survives.
        var clamped = BookPlanningService.clampToBudget(planned, 2);
        assertEquals(List.of("What We're Building", "Products"), clamped.stream().map(p -> p.title()).toList());
    }
}
