package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.entity.EbookChapter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Renders real PDFs and reads them back: no section heading may end a page
 * with its content starting on the next (an orphan heading). Covers the shapes
 * that used to strand a heading — a lead-in line before a code block or table,
 * a heading run (h2 straight into h3), a callout or table too big to keep under
 * the heading — at every height on the page, plus whole technical books.
 */
class HeadingPaginationTest {

    private static final String S = "The repository returns the entity and the service decides what it may see. ";

    private final PdfGenerationService service =
            new PdfGenerationService(null, null, null, new EbookHtmlBuilder(), null, null);

    @Test
    void aHeadingTravelsWithTheStartOfItsSectionAtEveryHeightOnThePage() throws Exception {
        StringBuilder code = new StringBuilder("```java\n@Entity\npublic class Offer {\n");
        for (int i = 0; i < 28; i++) {
            code.append("    private String field").append(i).append(";\n");
        }
        code.append("}\n```");
        StringBuilder table = new StringBuilder("| Column | Type |\n|---|---|\n");
        for (int i = 0; i < 14; i++) {
            table.append("| field").append(i).append(" | VARCHAR(255) |\n");
        }
        List<String> shapes = List.of(
                "### Offer: the listing, and the soft delete\n\nHere is the entity:\n\n" + code + "\n\n" + S.repeat(4),
                "## Data model\n\n### User: the account, the credentials and the role\n\n" + code + "\n\n" + S.repeat(4),
                "### Columns\n\nThe columns map one to one:\n\n" + table + "\n" + S.repeat(4),
                "### Rules\n\nThe rules are:\n\n" + ("- " + S.repeat(2) + "\n").repeat(8),
                "### Offer\n\n:::example\n" + S.repeat(12) + "\n:::\n\n" + S.repeat(6));

        List<EbookChapter> chapters = new ArrayList<>();
        int n = 1;
        for (String shape : shapes) {
            // Each chapter starts a page; the filler sets how far down the heading lands.
            for (int lines = 0; lines <= 36; lines += 3) {
                chapters.add(EbookChapter.builder().chapterNumber(n).title("Case " + n++)
                        .content("Filler.\n\n".repeat(lines) + shape).build());
            }
        }
        byte[] pdf = service.render(PaginationFixtureBook.ebook(), chapters);

        assertEquals(List.of(), StrandedHeadingProbe.find(pdf));
    }

    @Test
    void aWholeTechnicalBookHasNoOrphanHeadings() throws Exception {
        byte[] pdf = service.render(PaginationFixtureBook.ebook(), PaginationFixtureBook.chapters(1, 10));

        assertEquals(List.of(), StrandedHeadingProbe.find(pdf));
    }
}
