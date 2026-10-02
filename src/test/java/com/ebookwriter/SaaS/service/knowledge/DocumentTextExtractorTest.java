package com.ebookwriter.SaaS.service.knowledge;

import com.ebookwriter.SaaS.config.properties.KnowledgeProperties;
import com.ebookwriter.SaaS.dto.knowledge.NormalizedDocument;
import com.ebookwriter.SaaS.dto.knowledge.NormalizedDocument.Kind;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** PDF / DOCX / TXT / MD extraction and the normalisation applied to all of them. */
class DocumentTextExtractorTest {

    private final DocumentTextExtractor extractor = new DocumentTextExtractor(new KnowledgeProperties());

    @Test
    void pdfTextIsExtracted() throws Exception {
        NormalizedDocument doc = extractor.extract("guide.pdf", MyShopFixture.pdf("Chapter one", "JWT keeps the API stateless."), Kind.DOCUMENT);
        assertTrue(doc.content().contains("JWT keeps the API stateless."));
        assertEquals("pdf", doc.extension());
    }

    @Test
    void docxParagraphsAndHeadingsAreExtracted() throws Exception {
        NormalizedDocument doc = extractor.extract("plan.docx",
                MyShopFixture.docx("Overview", "First create the project.", "Then the database."), Kind.DOCUMENT);
        assertEquals("# Overview\nFirst create the project.\nThen the database.", doc.content());
    }

    @Test
    void docxWithExternalEntityIsRejectedSafely() {
        String xml = "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<w:document xmlns:w=\"w\"><w:body><w:p><w:r><w:t>&x;</w:t></w:r></w:p></w:body></w:document>";
        assertThrows(DocumentTextExtractor.DocumentExtractionException.class,
                () -> DocumentTextExtractor.docxXmlToText(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void markdownAndTextAreNormalised() throws Exception {
        byte[] bytes = "\uFEFF# Notes\r\n\r\n\r\n\r\n\r\nline   \r\nnext\u0007".getBytes(StandardCharsets.UTF_8);
        NormalizedDocument doc = extractor.extract("notes.md", bytes, Kind.DOCUMENT);
        assertEquals("# Notes\n\n\nline\nnext", doc.content());
        assertEquals(doc.content().length(), doc.chars());
        assertNotNull(doc.contentHash());
    }

    @Test
    void latin1TextIsDecoded() throws Exception {
        NormalizedDocument doc = extractor.extract("old.txt", "caf\u00e9".getBytes(StandardCharsets.ISO_8859_1), Kind.DOCUMENT);
        assertEquals("caf\u00e9", doc.content());
    }

    @Test
    void corruptAndEmptyFilesFailWithAReadableReason() {
        var bad = assertThrows(DocumentTextExtractor.DocumentExtractionException.class,
                () -> extractor.extract("x.pdf", "garbage".getBytes(StandardCharsets.UTF_8), Kind.DOCUMENT));
        assertTrue(bad.getMessage().contains("PDF"));
        assertThrows(DocumentTextExtractor.DocumentExtractionException.class,
                () -> extractor.extract("x.docx", "garbage".getBytes(StandardCharsets.UTF_8), Kind.DOCUMENT));
        assertThrows(DocumentTextExtractor.DocumentExtractionException.class,
                () -> extractor.extract("x.txt", "   \n\n ".getBytes(StandardCharsets.UTF_8), Kind.DOCUMENT));
        assertThrows(DocumentTextExtractor.DocumentExtractionException.class,
                () -> extractor.extract("x.txt", new byte[]{1, 0, 2, 0}, Kind.DOCUMENT));
    }

    @Test
    void sameContentWithDifferentWhitespaceHashesTheSame() {
        assertEquals(TextNormalizer.contentHash("Hello   World\n"), TextNormalizer.contentHash("hello world"));
        assertNotEquals(TextNormalizer.contentHash("hello world"), TextNormalizer.contentHash("hello there"));
    }
}
