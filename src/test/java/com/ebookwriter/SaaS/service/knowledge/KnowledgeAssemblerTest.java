package com.ebookwriter.SaaS.service.knowledge;

import com.ebookwriter.SaaS.dto.knowledge.BookKnowledgeData;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Parsing model output, validating source references, and merging batches. */
class KnowledgeAssemblerTest {

    private static final List<String> REFS = List.of(
            "user-notes", "README.md", "src/main/java/com/shop/security/SecurityConfig.java",
            "src/main/java/com/shop/security/JwtService.java", "a.zip!/pom.xml", "b.zip!/pom.xml",
            "my-shop.zip (structure)");

    private final KnowledgeAssembler.SourceResolver resolver = new KnowledgeAssembler.SourceResolver(REFS);

    @Test
    void citationsResolveToRealDocumentsOrAreDropped() {
        assertEquals("README.md", resolver.resolve("README.md"));
        assertEquals("README.md", resolver.resolve("SOURCE: readme.md"));
        assertEquals("user-notes", resolver.resolve("user notes"));
        assertEquals("src/main/java/com/shop/security/SecurityConfig.java", resolver.resolve("SecurityConfig.java"));
        assertEquals("src/main/java/com/shop/security/SecurityConfig.java", resolver.resolve("security/SecurityConfig.java"));
        assertEquals("src/main/java/com/shop/security/JwtService.java",
                resolver.resolve("src/main/java/com/shop/security/JwtService.java (part 2/3)"));
        assertEquals("a.zip!/pom.xml", resolver.resolve("a.zip!/pom.xml"));
        assertNull(resolver.resolve("pom.xml"), "ambiguous bare name is not guessed");
        assertNull(resolver.resolve("src/Invented.java"));
        assertNull(resolver.resolve(""));
        assertEquals("my-shop.zip (structure)", resolver.resolve("my-shop.zip (structure)"));
        assertEquals("my-shop.zip (structure)", resolver.resolve("\"my-shop.zip (structure)\""));
    }

    @Test
    void parsesLenientlyAndDropsBlankItems() throws Exception {
        String raw = """
                {"project": {"name": "my-shop", "technologies": ["Java 21", ""], "sources": ["README.md"]},
                 "summary": "An online shop backend.",
                 "topics": [{"name": "JWT authentication", "importance": "HIGH", "sources": ["SecurityConfig.java", "Nope.java"]},
                            {"name": "  ", "sources": []}],
                 "processes": [{"name": "Create the project", "steps": "Generate with Spring Initializr", "sources": "user-notes"}],
                 "somethingNew": {"ignored": true}}
                """;
        BookKnowledgeData d = KnowledgeAssembler.resolveSources(
                KnowledgeAssembler.parse(new ObjectMapper().readTree(raw)), resolver);

        assertEquals("An online shop backend.", d.overallSummary(), "'summary' maps to overallSummary");
        assertEquals(List.of("Java 21"), d.project().technologies());
        assertEquals(1, d.topics().size());
        assertEquals("high", d.topics().get(0).importance());
        assertEquals(List.of("src/main/java/com/shop/security/SecurityConfig.java"), d.topics().get(0).sources());
        assertEquals(List.of("Generate with Spring Initializr"), d.processes().get(0).steps(), "single value accepted as list");
        assertEquals(List.of("user-notes"), d.processes().get(0).sources());
        assertTrue(d.examples().isEmpty());
    }

    @Test
    void mergeCombinesTheSameItemAcrossBatches() {
        BookKnowledgeData a = new BookKnowledgeData(1, null,
                new BookKnowledgeData.ProjectInfo("my-shop", null, "Shop", null, List.of("Java"), List.of("README.md")),
                "Batch one.", List.of(new BookKnowledgeData.Topic("JWT authentication", "short", "medium", List.of("user-notes"))),
                null, null, null, null,
                List.of(new BookKnowledgeData.UserInsight("Had problems with JWT", "problem", List.of("user-notes"))),
                null, null, null, null, null, null);
        BookKnowledgeData b = new BookKnowledgeData(1, null,
                new BookKnowledgeData.ProjectInfo(null, "web backend", "Online shop backend with JWT", null, List.of("java", "Spring Boot"), List.of()),
                "Batch two.", List.of(new BookKnowledgeData.Topic("JWT Authentication!", "a longer description", "high",
                List.of("src/main/java/com/shop/security/SecurityConfig.java")),
                new BookKnowledgeData.Topic("Products", "catalog", "medium", List.of())),
                null, null, null, null, null, null, null, null, null, null, null);

        BookKnowledgeData m = KnowledgeAssembler.merge(List.of(a, b));

        assertEquals("my-shop", m.project().name());
        assertEquals("web backend", m.project().type());
        assertEquals("Online shop backend with JWT", m.project().description());
        assertEquals(List.of("Java", "Spring Boot"), m.project().technologies());
        assertEquals("Batch one. Batch two.", m.overallSummary());
        assertEquals(2, m.topics().size());
        BookKnowledgeData.Topic jwt = m.topics().get(0);
        assertEquals("a longer description", jwt.description());
        assertEquals("high", jwt.importance());
        assertEquals(List.of("user-notes", "src/main/java/com/shop/security/SecurityConfig.java"), jwt.sources());
        assertEquals(1, m.userInsights().size());
    }

    @Test
    void duplicatesWithinOneBatchAreMergedToo() {
        BookKnowledgeData one = new BookKnowledgeData(1, null, null, "s",
                List.of(new BookKnowledgeData.Topic("JWT authentication", "a", "high", List.of("SecurityConfig.java")),
                        new BookKnowledgeData.Topic("JWT Authentication", "longer text", "medium", List.of("user-notes"))),
                null, null, null, null, null, null, null, null, null, null, null);
        BookKnowledgeData m = KnowledgeAssembler.merge(List.of(one));
        assertEquals(1, m.topics().size());
        assertEquals(List.of("SecurityConfig.java", "user-notes"), m.topics().get(0).sources());
        assertEquals("longer text", m.topics().get(0).description());
    }

    @Test
    void roundTripsThroughJson() {
        BookKnowledgeData d = new BookKnowledgeData(1, new BookKnowledgeData.BookInfo("T", "English", "A", "G", null, "STANDARD"),
                null, "s", List.of(new BookKnowledgeData.Topic("x", "y", "low", List.of("README.md"))),
                null, null, null, null, null, null, null, null, null, null, null);
        BookKnowledgeData back = KnowledgeAssembler.read(KnowledgeAssembler.write(d));
        assertEquals(d, back);
    }
}
