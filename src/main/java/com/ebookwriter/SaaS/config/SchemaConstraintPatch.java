package com.ebookwriter.SaaS.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Drops the enum {@code CHECK} constraints Hibernate generated for
 * {@code @Enumerated(STRING)} columns.
 *
 * <p>Why: with {@code ddl-auto=update}, Hibernate creates a check constraint
 * listing an enum's values when the column is first created, but it never
 * updates that constraint when the enum later grows. So adding a value (e.g.
 * {@code EbookStatus.DRAFT}, or the expanded {@code EbookImageRole}) makes every
 * insert of the new value fail with a check-constraint violation against the
 * <em>old</em> value list. The enum values are still enforced in the
 * application, so dropping these DB checks is safe and removes the drift for
 * good — without a manual migration.
 *
 * <p>Idempotent ({@code DROP CONSTRAINT IF EXISTS}) and never fatal: runs after
 * Hibernate's schema update and tolerates a constraint (or table) that isn't
 * there, so it is a no-op on a fresh database.
 */
@Slf4j
@Component
@Order(0)
@RequiredArgsConstructor
public class SchemaConstraintPatch implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    /**
     * (table, constraint) pairs to drop. These are the columns whose enums have
     * grown, plus the other evolving enum columns pre-emptively — dropping a
     * matching or absent constraint is harmless.
     */
    private static final List<String[]> CONSTRAINTS = List.of(
            new String[]{"ebook_images", "ebook_images_role_check"},
            new String[]{"ebook_images", "ebook_images_placement_check"},
            new String[]{"ebook_images", "ebook_images_placed_by_check"},
            new String[]{"ebooks", "ebooks_status_check"},
            new String[]{"ebook_chapters", "ebook_chapters_status_check"},
            new String[]{"ebook_chapters", "ebook_chapters_content_source_check"});

    @Override
    public void run(ApplicationArguments args) {
        for (String[] tc : CONSTRAINTS) {
            String sql = "ALTER TABLE " + tc[0] + " DROP CONSTRAINT IF EXISTS " + tc[1];
            try {
                jdbcTemplate.execute(sql);
            } catch (RuntimeException e) {
                // e.g. the table doesn't exist yet on a brand-new DB — harmless.
                log.debug("[schema] Skipped '{}': {}", sql, e.getMessage());
            }
        }
        log.info("[schema] Enum check-constraint patch applied (drift-safe).");
    }
}
