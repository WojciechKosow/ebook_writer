package com.ebookwriter.SaaS.service.blueprint;

import com.ebookwriter.SaaS.dto.knowledge.BookKnowledgeData;
import com.ebookwriter.SaaS.dto.knowledge.BookKnowledgeData.*;

import java.util.ArrayList;
import java.util.List;

/**
 * BookKnowledge as stage one produces it for the reference case: my-shop.zip
 * (a Spring Boot online-shop backend) plus the author's rough notes.
 * {@link #withoutOrders()} is the same project without an orders module — the
 * blueprint must not invent one.
 */
final class ShopKnowledgeFixture {

    static final String SECURITY = "src/main/java/com/shop/security/SecurityConfig.java";
    static final String JWT_SERVICE = "src/main/java/com/shop/security/JwtService.java";
    static final String USER = "src/main/java/com/shop/user/User.java";
    static final String PRODUCT = "src/main/java/com/shop/product/Product.java";
    static final String ORDER = "src/main/java/com/shop/order/Order.java";
    static final String ORDER_SERVICE = "src/main/java/com/shop/order/OrderService.java";
    static final String PROPS = "src/main/resources/application.properties";
    static final String NOTES = "user-notes";

    private ShopKnowledgeFixture() {
    }

    static BookKnowledgeData full() {
        return build(true);
    }

    static BookKnowledgeData withoutOrders() {
        return build(false);
    }

    private static BookKnowledgeData build(boolean orders) {
        List<Topic> topics = new ArrayList<>(List.of(
                new Topic("Project setup", "Spring Boot project generated, Java 21", "high", List.of("pom.xml", NOTES)),
                new Topic("Dependencies", "web, data-jpa, security, jjwt, postgresql", "high", List.of("pom.xml")),
                new Topic("Database configuration", "PostgreSQL via spring.datasource, ddl-auto=update", "high", List.of(PROPS, "docker-compose.yml")),
                new Topic("Users", "User entity with email, password hash and role", "high", List.of(USER)),
                new Topic("JWT authentication", "Stateless security: JwtAuthFilter before UsernamePasswordAuthenticationFilter",
                        "high", List.of(SECURITY, JWT_SERVICE, NOTES)),
                new Topic("Products", "Product entity with price and stock; public GET, admin POST", "high", List.of(PRODUCT))));
        if (orders) {
            topics.add(new Topic("Orders", "Orders decrement stock transactionally", "high", List.of(ORDER, ORDER_SERVICE)));
        }
        List<String> refs = new ArrayList<>(List.of("my-shop.zip (structure)", NOTES, "README.md", "pom.xml",
                "docker-compose.yml", PROPS, SECURITY, JWT_SERVICE, USER, PRODUCT));
        if (orders) refs.addAll(List.of(ORDER, ORDER_SERVICE));
        List<SourceRef> sources = refs.stream()
                .map(r -> new SourceRef(r, "my-shop.zip", "ZIP", "CODE", null, 500, false, true, null, null)).toList();
        List<SequenceStep> sequence = new ArrayList<>(List.of(
                new SequenceStep("Create the project", List.of(NOTES)),
                new SequenceStep("Add dependencies", List.of(NOTES)),
                new SequenceStep("Set up the database", List.of(NOTES)),
                new SequenceStep("Users", List.of(NOTES))));
        sequence.add(new SequenceStep(orders ? "Products and orders" : "Products", List.of(NOTES)));

        return new BookKnowledgeData(1,
                new BookInfo("Building an Online Shop with Spring Boot", "English", "Beginner Java developers",
                        "Teach beginners how to build the project from scratch.", null, 30),
                new ProjectInfo("Online Shop", "web application backend", "A Spring Boot online-shop backend.", "e-commerce",
                        List.of("Java 21", "Spring Boot", "PostgreSQL", "JWT"), List.of("pom.xml", "README.md")),
                "An online shop backend built step by step.",
                topics,
                List.of(new ProcessInfo("Building the shop from scratch", "The author's order",
                        List.of("Create project", "Dependencies", "Database", "Users", "Products"), List.of(NOTES))),
                List.of(new Example("Security filter chain", "Stateless filter chain", "code", null, List.of(SECURITY))),
                List.of(new ImportantDetail("Explain why JWT is used", "The author asked for it", List.of(NOTES))),
                List.of(new Term("JWT", "Signed token carrying the user's email", List.of(JWT_SERVICE))),
                List.of(new UserInsight("The author had problems with JWT", "problem", List.of(NOTES))),
                List.of(new TechnicalDetail("Security", "Sessions are STATELESS", List.of(SECURITY))),
                List.of(new Fact("Java version is 21", List.of("pom.xml"))),
                sequence,
                List.of(new KnowledgeGap("Why JWT instead of server sessions?", "Author wants to explain it", "JWT authentication", List.of(NOTES))),
                sources,
                new Coverage(2, refs.size(), refs.size(), 0, 1, 7, 2, 8000));
    }
}
