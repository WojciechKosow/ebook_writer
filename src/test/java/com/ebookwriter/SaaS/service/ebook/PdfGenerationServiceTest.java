package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline check of the PDF path (HTML assembly, embedded fonts, openhtmltopdf).
 * Does not touch the database or the Anthropic API.
 */
class PdfGenerationServiceTest {

    @Test
    void rendersAPdfFromAManuscript() {
        PdfGenerationService service =
                new PdfGenerationService(null, null, null, new EbookHtmlBuilder());

        Ebook ebook = Ebook.builder()
                .topic("Building SaaS Applications with Spring Boot")
                .title("The Pragmatic Spring Boot SaaS")
                .subtitle("From idea to production")
                .language("English")
                .build();

        EbookChapter c1 = EbookChapter.builder()
                .chapterNumber(1)
                .title("Getting Started")
                .content("""
                        This chapter introduces the project. Here is a list:

                        - First point with some **bold** text
                        - Second point with `inline code`

                        ## A section

                        Some prose. Now a code block, including non-ASCII: café, żółć.

                        ```java
                        public record User(UUID id, String email) {}
                        ```
                        """)
                .build();

        EbookChapter c2 = EbookChapter.builder()
                .chapterNumber(2)
                .title("Persistence")
                .content("A short second chapter.\n\n### Subsection\n\nMore text here.")
                .build();

        byte[] pdf = service.render(ebook, List.of(c1, c2));

        assertTrue(pdf.length > 1000, "PDF should be non-trivial in size");
        assertTrue(pdf[0] == '%' && pdf[1] == 'P' && pdf[2] == 'D' && pdf[3] == 'F',
                "Output should start with the %PDF magic header");
    }
}
