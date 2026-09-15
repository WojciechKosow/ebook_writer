package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.entity.EbookImage;
import com.ebookwriter.SaaS.repository.EbookChapterRepository;
import com.ebookwriter.SaaS.repository.EbookImageRepository;
import com.ebookwriter.SaaS.repository.EbookRepository;
import com.ebookwriter.SaaS.service.storage.R2StorageService;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds a <b>browser-renderable</b> preview of the book, so the editor can show
 * the reader exactly what the download will look like instead of a Word-style
 * approximation. The preview is assembled from the same {@link EbookHtmlBuilder}
 * as the PDF, so its structure (cover, table of contents, chapters, inline image
 * positions) is identical — this is the render pipeline, not a second layout.
 *
 * <p>Two things differ from {@link PdfGenerationService}, both because the output
 * is consumed by a browser rather than openhtmltopdf:
 * <ul>
 *   <li><b>Fonts.</b> The PDF registers the bundled Liberation fonts under the
 *       {@code Ebook Serif/Sans/Mono} family names in code; a browser has no such
 *       families, so the preview CSS maps them to the metric-compatible web fonts
 *       Tinos / Arimo / Cousine (loaded from Google Fonts). Being
 *       metric-compatible with Liberation, line and page breaks match the PDF
 *       closely.</li>
 *   <li><b>Images.</b> The bucket is private and an {@code <img>} loaded by the
 *       browser cannot carry the caller's bearer token, so each
 *       {@code ebook-image:<id>} reference is inlined as a {@code data:} URI. The
 *       result is a single self-contained document the frontend can drop into a
 *       sandboxed iframe — no per-image authenticated request needed.</li>
 * </ul>
 *
 * <p>The CSS keeps the {@code @page} rules (6in × 9in, margins, page numbers).
 * Browsers ignore {@code @page} on screen, so the frontend paginates the returned
 * HTML with a CSS Paged Media polyfill (e.g. Paged.js) to show real book pages.
 *
 * <p><b>Security note for callers:</b> the inlined images are user-uploaded and an
 * SVG can carry script, so the returned HTML must be rendered in a sandboxed
 * iframe (as the download endpoint already sets {@code X-Content-Type-Options:
 * nosniff} on raw bytes).
 */
@Service
@RequiredArgsConstructor
public class EbookPreviewService {

    private static final String CSS_PATH = "pdf/ebook.css";

    /**
     * Web fonts that render the book faithfully in a browser. Tinos, Arimo and
     * Cousine are metric-compatible with the Liberation Serif/Sans/Mono fonts the
     * PDF embeds, so text wraps and paginates almost identically.
     */
    private static final String FONT_IMPORT =
            "@import url('https://fonts.googleapis.com/css2?"
                    + "family=Tinos:ital,wght@0,400;0,700;1,400;1,700"
                    + "&family=Arimo:wght@400;700"
                    + "&family=Cousine&display=swap');\n";

    private final EbookRepository ebookRepository;
    private final EbookChapterRepository chapterRepository;
    private final EbookImageRepository imageRepository;
    private final EbookHtmlBuilder htmlBuilder;
    private final R2StorageService storage;

    private volatile String previewCssCache;

    /**
     * Render the owner's book as a self-contained HTML preview. Ownership-scoped;
     * works whatever the book's status (it renders whatever chapters exist), so
     * the editor can preview a book at any point.
     */
    @Transactional(readOnly = true)
    public String renderPreview(UUID ebookId, UUID userId) {
        Ebook ebook = ebookRepository.findByIdAndUserId(ebookId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Ebook not found"));
        List<EbookChapter> chapters =
                chapterRepository.findByEbookIdOrderByChapterNumberAsc(ebookId);
        List<EbookImage> images =
                imageRepository.findByEbookIdOrderByCreatedAtAsc(ebookId);
        return buildPreviewHtml(ebook, chapters, images);
    }

    /**
     * Assemble the preview HTML from the shared builder and inline the images.
     * Package-visible so it can be exercised without a database.
     */
    String buildPreviewHtml(Ebook ebook, List<EbookChapter> chapters, List<EbookImage> images) {
        String html = htmlBuilder.build(ebook, chapters, previewCss(), images);
        return inlineImages(html, images);
    }

    /**
     * Rewrite each {@code <img src="ebook-image:<id>">} to a {@code data:} URI
     * carrying the image's bytes, and apply the persisted display width. An image
     * whose id no longer resolves, or whose bytes are missing from storage, is
     * dropped so it doesn't render as a broken reference (mirrors the PDF path).
     */
    private String inlineImages(String html, List<EbookImage> images) {
        Map<String, EbookImage> byId = new HashMap<>();
        for (EbookImage image : images) {
            byId.put(image.getId().toString(), image);
        }

        Document doc = Jsoup.parse(html);
        doc.outputSettings().prettyPrint(false); // keep <pre>/code whitespace intact

        for (Element img : doc.select("img")) {
            String src = img.attr("src");
            if (!src.startsWith(EbookImage.REF_SCHEME)) {
                continue;
            }
            String id = src.substring(EbookImage.REF_SCHEME.length());
            EbookImage image = byId.get(id);
            if (image == null) {
                img.remove();
                continue;
            }
            Optional<byte[]> bytes = storage.download(image.getStorageKey());
            if (bytes.isEmpty()) {
                img.remove();
                continue;
            }
            img.attr("src", "data:" + image.getContentType() + ";base64,"
                    + Base64.getEncoder().encodeToString(bytes.get()));

            // Persist the editor's "resize", exactly as the PDF renderer does.
            Integer pct = image.getDisplayWidthPercent();
            if (pct != null) {
                int clamped = Math.max(1, Math.min(100, pct));
                String existing = img.attr("style");
                String width = "width:" + clamped + "%";
                img.attr("style", existing.isBlank() ? width : existing + ";" + width);
            }
        }
        return doc.html();
    }

    /**
     * The book CSS with the code-registered PDF font families mapped to
     * browser-available, metric-compatible web fonts, and the Google Fonts import
     * prepended. Cached: the source stylesheet never changes at runtime.
     */
    private String previewCss() {
        String cached = previewCssCache;
        if (cached != null) {
            return cached;
        }
        String base;
        try (InputStream in = new ClassPathResource(CSS_PATH).getInputStream()) {
            base = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load ebook CSS", e);
        }
        String mapped = base
                .replace("\"Ebook Serif\"", "'Tinos','Liberation Serif',serif")
                .replace("\"Ebook Sans\"", "'Arimo','Liberation Sans',sans-serif")
                .replace("\"Ebook Mono\"", "'Cousine','Liberation Mono',monospace");
        String css = FONT_IMPORT + mapped;
        previewCssCache = css;
        return css;
    }
}
