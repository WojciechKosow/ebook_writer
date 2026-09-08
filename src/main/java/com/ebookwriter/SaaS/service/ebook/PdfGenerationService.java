package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.entity.EbookImage;
import com.ebookwriter.SaaS.entity.EbookPdf;
import com.ebookwriter.SaaS.repository.EbookChapterRepository;
import com.ebookwriter.SaaS.repository.EbookImageRepository;
import com.ebookwriter.SaaS.repository.EbookPdfRepository;
import com.ebookwriter.SaaS.repository.EbookRepository;
import com.ebookwriter.SaaS.service.storage.R2StorageService;
import com.openhtmltopdf.extend.FSStream;
import com.openhtmltopdf.extend.FSStreamFactory;
import com.openhtmltopdf.extend.FSSupplier;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /** URL scheme used inside the render HTML for images streamed from R2. */
    private static final String R2_PROTOCOL = "r2";
    private static final String R2_SCHEME = R2_PROTOCOL + ":";

    private final EbookRepository ebookRepository;
    private final EbookChapterRepository chapterRepository;
    private final EbookPdfRepository pdfRepository;
    private final EbookHtmlBuilder htmlBuilder;
    private final EbookImageRepository imageRepository;
    private final R2StorageService storage;

    private volatile String cssCache;

    /** Extra render passes allowed while trimming an over-budget book down to size. */
    private static final int MAX_TRIM_PASSES = 12;

    /**
     * Render the book, store the PDF, and return the <b>real</b> number of pages
     * in it. The page count is what the user is billed for, so it is read back
     * from the rendered bytes rather than estimated from word targets.
     *
     * <p>{@code maxPages} is a hard ceiling: if the rendered book comes out
     * longer (the model overshot its word targets), trailing content is trimmed
     * and the book re-rendered — a cheap, API-free loop — until it fits. Any
     * chapter whose content was trimmed is persisted so the stored manuscript
     * matches the delivered PDF. Pass a non-positive {@code maxPages} to render
     * as-is with no ceiling.
     */
    @Transactional
    public int renderAndStore(UUID ebookId, int maxPages) {
        Ebook ebook = ebookRepository.findById(ebookId)
                .orElseThrow(() -> new IllegalArgumentException("Ebook not found: " + ebookId));
        List<EbookChapter> chapters = chapterRepository.findByEbookIdOrderByChapterNumberAsc(ebookId);
        List<EbookImage> images = imageRepository.findByEbookIdOrderByCreatedAtAsc(ebookId);

        byte[] pdf = render(ebook, chapters, images);
        int pageCount = countPages(pdf);

        int passes = 0;
        while (maxPages > 0 && pageCount > maxPages && passes < MAX_TRIM_PASSES) {
            int overflowWords = (pageCount - maxPages) * ChapterGenerationService.WORDS_PER_PAGE;
            if (!trimTrailingWords(chapters, overflowWords)) {
                break; // nothing left to trim
            }
            pdf = render(ebook, chapters, images);
            pageCount = countPages(pdf);
            passes++;
        }

        if (passes > 0) {
            chapterRepository.saveAll(chapters); // persist the trimmed manuscript
            log.info("Trimmed ebook {} to {} pages in {} pass(es) to fit budget {}",
                    ebookId, pageCount, passes, maxPages);
        }

        pdfRepository.save(new EbookPdf(ebookId, pdf));
        log.info("Rendered PDF for ebook {} ({} KB, {} pages)", ebookId, pdf.length / 1024, pageCount);
        return pageCount;
    }

    /**
     * Remove roughly {@code words} words from the end of the manuscript by
     * dropping trailing paragraphs of the last non-empty chapter (spilling into
     * earlier chapters if one chapter isn't enough). Paragraph granularity keeps
     * code blocks and headings intact rather than cutting mid-block. Mutates the
     * chapters' content in place.
     *
     * @return true if anything was removed, false if there was nothing left.
     */
    static boolean trimTrailingWords(List<EbookChapter> chapters, int words) {
        int remaining = Math.max(1, words);
        boolean removedAnything = false;

        for (int i = chapters.size() - 1; i >= 0 && remaining > 0; i--) {
            EbookChapter chapter = chapters.get(i);
            String content = chapter.getContent();
            if (content == null || content.isBlank()) {
                continue;
            }
            // Split into paragraphs (blank-line separated), drop from the tail.
            List<String> paragraphs = new ArrayList<>(List.of(content.strip().split("\\n\\s*\\n")));
            while (remaining > 0 && !paragraphs.isEmpty()) {
                String last = paragraphs.remove(paragraphs.size() - 1);
                remaining -= wordCount(last);
                removedAnything = true;
            }
            chapter.setContent(String.join("\n\n", paragraphs).strip());
        }
        return removedAnything;
    }

    private static int wordCount(String s) {
        String t = s.strip();
        return t.isEmpty() ? 0 : t.split("\\s+").length;
    }

    /** Read the true page count from rendered PDF bytes. */
    private int countPages(byte[] pdf) {
        try (PDDocument document = PDDocument.load(pdf)) {
            return document.getNumberOfPages();
        } catch (IOException e) {
            throw new RuntimeException("Could not read rendered PDF page count: " + e.getMessage(), e);
        }
    }

    /** Build the book HTML and render it to PDF bytes (no persistence, no images). */
    public byte[] render(Ebook ebook, List<EbookChapter> chapters) {
        return render(ebook, chapters, List.of());
    }

    /**
     * Build the book HTML — including the cover and inline images — and render it
     * to PDF bytes (no persistence). Image references ({@code ebook-image:<id>})
     * are rewritten to a streamable {@code r2:} URL and the bytes are pulled from
     * R2 on demand during rendering, so the images are embedded directly in the
     * PDF (the bucket stays private).
     */
    public byte[] render(Ebook ebook, List<EbookChapter> chapters, List<EbookImage> images) {
        String html = htmlBuilder.build(ebook, chapters, css(), images);
        Map<String, String> keyByImageId = new LinkedHashMap<>();
        for (EbookImage image : images) {
            keyByImageId.put(image.getId().toString(), image.getStorageKey());
        }
        return renderPdf(html, keyByImageId);
    }

    private byte[] renderPdf(String html, Map<String, String> keyByImageId) {
        try {
            Document jsoupDoc = Jsoup.parse(html);
            rewriteImageReferences(jsoupDoc, keyByImageId);
            jsoupDoc.outputSettings().syntax(Document.OutputSettings.Syntax.xml);
            org.w3c.dom.Document dom = new W3CDom().fromJsoup(jsoupDoc);

            ByteArrayOutputStream os = new ByteArrayOutputStream(256 * 1024);
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            registerFonts(builder);
            builder.useProtocolsStreamImplementation(r2StreamFactory(), R2_PROTOCOL);
            builder.withW3cDocument(dom, "/");
            builder.toStream(os);
            builder.run();
            return os.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("PDF rendering failed: " + e.getMessage(), e);
        }
    }

    /**
     * Rewrite each {@code <img src="ebook-image:<id>">} to the streamable
     * {@code r2:<storageKey>} URL. An image whose id no longer resolves (e.g. it
     * was deleted) is dropped so it doesn't render as a broken reference.
     */
    private static void rewriteImageReferences(Document doc, Map<String, String> keyByImageId) {
        for (Element img : doc.select("img")) {
            String src = img.attr("src");
            if (!src.startsWith(EbookImage.REF_SCHEME)) {
                continue;
            }
            String id = src.substring(EbookImage.REF_SCHEME.length());
            String key = keyByImageId.get(id);
            if (key != null) {
                img.attr("src", R2_SCHEME + key);
            } else {
                img.remove();
            }
        }
    }

    /**
     * Supplies image bytes for {@code r2:} URLs by streaming them from the R2
     * bucket. openhtmltopdf calls this only for images that survived the rewrite
     * above, so it is never invoked when a book has no images.
     */
    private FSStreamFactory r2StreamFactory() {
        return url -> {
            String key = url.startsWith(R2_SCHEME) ? url.substring(R2_SCHEME.length()) : url;
            byte[] bytes = storage.download(key)
                    .orElseThrow(() -> new RuntimeException("Image not found in storage: " + key));
            return new FSStream() {
                @Override
                public InputStream getStream() {
                    return new ByteArrayInputStream(bytes);
                }

                @Override
                public Reader getReader() {
                    return new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8);
                }
            };
        };
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
