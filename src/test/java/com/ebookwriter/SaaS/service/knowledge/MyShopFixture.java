package com.ebookwriter.SaaS.service.knowledge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The task's reference case: {@code my-shop.zip}, a small but real Spring Boot
 * online-shop backend wrapped in a {@code my-shop/} folder — plus the noise a
 * real archive carries (build output, .git, node_modules, an IDE folder, a .env
 * with secrets, a screenshot, a duplicated README) that ingestion must skip.
 */
final class MyShopFixture {

    static final String NOTES = """
            First create the project.
            Then dependencies.
            Then database.
            Then users.
            I had problems with JWT.
            Then products and orders.
            Need to explain why we use JWT.
            """;

    static final String TITLE = "Building an Online Shop with Spring Boot";
    static final String AUDIENCE = "Beginner Java developers";
    static final String GOAL = "Teach beginners how to build the project from scratch.";

    private MyShopFixture() {
    }

    static Map<String, String> textFiles() {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("my-shop/README.md", """
                # my-shop

                A simple online shop backend: users register and log in (JWT), browse products
                and place orders. Built with Java 21, Spring Boot 3 and PostgreSQL.

                ## Running
                1. Start PostgreSQL (`docker compose up -d`).
                2. `./mvnw spring-boot:run`
                """);
        f.put("my-shop/pom.xml", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.shop</groupId>
                  <artifactId>my-shop</artifactId>
                  <properties><java.version>21</java.version></properties>
                  <dependencies>
                    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
                    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-jpa</artifactId></dependency>
                    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-security</artifactId></dependency>
                    <dependency><groupId>io.jsonwebtoken</groupId><artifactId>jjwt-api</artifactId><version>0.12.5</version></dependency>
                    <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId></dependency>
                  </dependencies>
                </project>
                """);
        f.put("my-shop/docker-compose.yml", """
                services:
                  db:
                    image: postgres:16
                    environment:
                      POSTGRES_DB: shop
                """);
        f.put("my-shop/src/main/resources/application.properties", """
                spring.datasource.url=jdbc:postgresql://localhost:5432/shop
                spring.jpa.hibernate.ddl-auto=update
                jwt.expiration=3600000
                """);
        f.put("my-shop/src/main/java/com/shop/ShopApplication.java", """
                package com.shop;

                @SpringBootApplication
                public class ShopApplication {
                    public static void main(String[] args) { SpringApplication.run(ShopApplication.class, args); }
                }
                """);
        f.put("my-shop/src/main/java/com/shop/security/SecurityConfig.java", """
                package com.shop.security;

                @Configuration
                public class SecurityConfig {
                    @Bean
                    SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtFilter) throws Exception {
                        return http.csrf(c -> c.disable())
                                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                                .authorizeHttpRequests(a -> a.requestMatchers("/api/auth/**").permitAll()
                                        .requestMatchers(HttpMethod.GET, "/api/products/**").permitAll()
                                        .anyRequest().authenticated())
                                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                                .build();
                    }
                }
                """);
        f.put("my-shop/src/main/java/com/shop/security/JwtService.java", """
                package com.shop.security;

                @Service
                public class JwtService {
                    @Value("${jwt.secret}") private String secret;
                    @Value("${jwt.expiration}") private long expiration;

                    public String generateToken(User user) {
                        return Jwts.builder().subject(user.getEmail())
                                .expiration(new Date(System.currentTimeMillis() + expiration))
                                .signWith(key()).compact();
                    }

                    public String extractEmail(String token) {
                        return Jwts.parser().verifyWith(key()).build().parseSignedClaims(token).getPayload().getSubject();
                    }
                }
                """);
        f.put("my-shop/src/main/java/com/shop/security/JwtAuthFilter.java", """
                package com.shop.security;

                public class JwtAuthFilter extends OncePerRequestFilter {
                    // Reads "Authorization: Bearer <token>", validates it and sets the SecurityContext.
                    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) {
                        String header = req.getHeader("Authorization");
                        if (header != null && header.startsWith("Bearer ")) {
                            String email = jwtService.extractEmail(header.substring(7));
                            // ... load user, set authentication
                        }
                        chain.doFilter(req, res);
                    }
                }
                """);
        f.put("my-shop/src/main/java/com/shop/user/User.java", """
                package com.shop.user;

                @Entity @Table(name = "users")
                public class User {
                    @Id @GeneratedValue private Long id;
                    @Column(unique = true) private String email;
                    private String passwordHash;
                    @Enumerated(EnumType.STRING) private Role role;
                }
                """);
        f.put("my-shop/src/main/java/com/shop/user/AuthController.java", """
                package com.shop.user;

                @RestController @RequestMapping("/api/auth")
                public class AuthController {
                    @PostMapping("/register") public TokenResponse register(@RequestBody RegisterRequest r) { return authService.register(r); }
                    @PostMapping("/login") public TokenResponse login(@RequestBody LoginRequest r) { return authService.login(r); }
                }
                """);
        f.put("my-shop/src/main/java/com/shop/product/Product.java", """
                package com.shop.product;

                @Entity
                public class Product {
                    @Id @GeneratedValue private Long id;
                    private String name;
                    private BigDecimal price;
                    private int stock;
                }
                """);
        f.put("my-shop/src/main/java/com/shop/product/ProductController.java", """
                package com.shop.product;

                @RestController @RequestMapping("/api/products")
                public class ProductController {
                    @GetMapping public List<Product> all() { return repository.findAll(); }
                    @PostMapping @PreAuthorize("hasRole('ADMIN')") public Product create(@RequestBody Product p) { return repository.save(p); }
                }
                """);
        f.put("my-shop/src/main/java/com/shop/order/Order.java", """
                package com.shop.order;

                @Entity @Table(name = "orders")
                public class Order {
                    @Id @GeneratedValue private Long id;
                    @ManyToOne private User customer;
                    @OneToMany(cascade = CascadeType.ALL) private List<OrderItem> items;
                    @Enumerated(EnumType.STRING) private OrderStatus status;
                }
                """);
        f.put("my-shop/src/main/java/com/shop/order/OrderService.java", """
                package com.shop.order;

                @Service
                public class OrderService {
                    @Transactional
                    public Order placeOrder(User customer, List<OrderItemRequest> items) {
                        // checks stock for every product, decrements it, saves the order as NEW
                    }
                }
                """);
        f.put("my-shop/src/test/java/com/shop/OrderServiceTest.java", """
                package com.shop;

                class OrderServiceTest {
                    @Test void placingAnOrderDecrementsStock() { }
                }
                """);
        f.put("my-shop/notes/todo.md", """
                - add pagination to products
                - JWT refresh tokens?
                """);
        // A copy of the README (duplicate — must be analysed once).
        f.put("my-shop/docs/README-copy.md", f.get("my-shop/README.md"));
        // Noise that must never be read:
        f.put("my-shop/.env", "JWT_SECRET=super-secret-value\nDB_PASSWORD=hunter2\n");
        f.put("my-shop/target/classes/application.properties", "spring.datasource.url=build-output\n");
        f.put("my-shop/node_modules/left-pad/index.js", "module.exports = leftPad;\n");
        f.put("my-shop/.git/config", "[core]\n\trepositoryformatversion = 0\n");
        f.put("my-shop/.idea/workspace.xml", "<project/>\n");
        f.put("my-shop/package-lock.json", "{\"lockfileVersion\": 3}\n");
        return f;
    }

    static byte[] zip() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry("my-shop/"));
            zos.closeEntry();
            for (Map.Entry<String, String> e : textFiles().entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
            // A binary screenshot (PNG signature + junk) — listed, never read.
            zos.putNextEntry(new ZipEntry("my-shop/screenshots/home.png"));
            zos.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 1, 2, 3});
            zos.closeEntry();
        }
        return bos.toByteArray();
    }

    /** Build an arbitrary ZIP from entries. */
    static byte[] zipOf(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    /** A minimal valid DOCX with a heading and two paragraphs. */
    static byte[] docx(String heading, String... paragraphs) throws IOException {
        StringBuilder body = new StringBuilder();
        body.append("<w:p><w:pPr><w:pStyle w:val=\"Heading1\"/></w:pPr><w:r><w:t>").append(heading).append("</w:t></w:r></w:p>");
        for (String p : paragraphs) {
            body.append("<w:p><w:r><w:t xml:space=\"preserve\">").append(p).append("</w:t></w:r></w:p>");
        }
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
                + body + "</w:body></w:document>";
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("[Content_Types].xml", "<Types/>".getBytes(StandardCharsets.UTF_8));
        entries.put("word/document.xml", xml.getBytes(StandardCharsets.UTF_8));
        return zipOf(entries);
    }

    /** A one-page PDF with the given lines of text (via PDFBox). */
    static byte[] pdf(String... lines) throws IOException {
        try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            org.apache.pdfbox.pdmodel.PDPage page = new org.apache.pdfbox.pdmodel.PDPage();
            doc.addPage(page);
            try (org.apache.pdfbox.pdmodel.PDPageContentStream cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(org.apache.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 12);
                cs.setLeading(16);
                cs.newLineAtOffset(50, 700);
                for (String line : lines) {
                    cs.showText(line);
                    cs.newLine();
                }
                cs.endText();
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.save(bos);
            return bos.toByteArray();
        }
    }
}
