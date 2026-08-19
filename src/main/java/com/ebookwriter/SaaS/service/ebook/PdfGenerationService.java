package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.entity.EbookPdf;
import com.ebookwriter.SaaS.repository.EbookChapterRepository;
import com.ebookwriter.SaaS.repository.EbookPdfRepository;
import com.ebookwriter.SaaS.repository.EbookRepository;
import com.openhtmltopdf.extend.FSSupplier;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.jsoup.nodes.Document;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Step 4 — turn the finished manuscript into a styled PDF and store the bytes.
 * Markdown/HTML assembly lives in {@link EbookHtmlBuilder}; this class owns the
 * HTML -> PDF rendering (jsoup normalisation + openhtmltopdf) and font embedding.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PdfGenerationService {

    private static final String CSS_PATH = "pdf/ebook.css";
    private static final String FONT_DIR = "/fonts/";

    private final EbookRepository ebookRepository;
    private final EbookChapterRepository chapterRepository;
    private final EbookPdfRepository pdfRepository;
    private final EbookHtmlBuilder htmlBuilder;

    private volatile String cssCache;

    @Transactional
    public void renderAndStore(UUID ebookId) {
        Ebook ebook = ebookRepository.findById(ebookId)
                .orElseThrow(() -> new IllegalArgumentException("Ebook not found: " + ebookId));
        List<EbookChapter> chapters = chapterRepository.findByEbookIdOrderByChapterNumberAsc(ebookId);

        byte[] pdf = render(ebook, chapters);

        pdfRepository.save(new EbookPdf(ebookId, pdf));
        log.info("Rendered PDF for ebook {} ({} KB)", ebookId, pdf.length / 1024);
    }

    /** Build the book HTML and render it to PDF bytes (no persistence). */
    public byte[] render(Ebook ebook, List<EbookChapter> chapters) {
        String html = htmlBuilder.build(ebook, chapters, css());
        return renderPdf(html);
    }

    private byte[] renderPdf(String html) {
        try {
            Document jsoupDoc = Jsoup.parse(html);
            jsoupDoc.outputSettings().syntax(Document.OutputSettings.Syntax.xml);
            org.w3c.dom.Document dom = new W3CDom().fromJsoup(jsoupDoc);

            ByteArrayOutputStream os = new ByteArrayOutputStream(256 * 1024);
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            registerFonts(builder);
            builder.withW3cDocument(dom, "/");
            builder.toStream(os);
            builder.run();
            return os.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("PDF rendering failed: " + e.getMessage(), e);
        }
    }

    private void registerFonts(PdfRendererBuilder builder) {
        builder.useFont(font("LiberationSerif-Regular.ttf"), "Ebook Serif", 400, FontStyle.NORMAL, true);
        builder.useFont(font("LiberationSerif-Bold.ttf"), "Ebook Serif", 700, FontStyle.NORMAL, true);
        builder.useFont(font("LiberationSerif-Italic.ttf"), "Ebook Serif", 400, FontStyle.ITALIC, true);
        builder.useFont(font("LiberationSerif-BoldItalic.ttf"), "Ebook Serif", 700, FontStyle.ITALIC, true);
        builder.useFont(font("LiberationSans-Regular.ttf"), "Ebook Sans", 400, FontStyle.NORMAL, true);
        builder.useFont(font("LiberationSans-Bold.ttf"), "Ebook Sans", 700, FontStyle.NORMAL, true);
        builder.useFont(font("LiberationMono-Regular.ttf"), "Ebook Mono", 400, FontStyle.NORMAL, true);
    }

    /** Supplies a fresh classpath stream each time openhtmltopdf asks for the font. */
    private FSSupplier<InputStream> font(String fileName) {
        return () -> getClass().getResourceAsStream(FONT_DIR + fileName);
    }

    private String css() {
        String cached = cssCache;
        if (cached != null) {
            return cached;
        }
        try (InputStream in = new ClassPathResource(CSS_PATH).getInputStream()) {
            cached = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            cssCache = cached;
            return cached;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load ebook CSS", e);
        }
    }
}
