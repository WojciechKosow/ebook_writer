package com.ebookwriter.SaaS.service.store;

import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookPdf;
import com.ebookwriter.SaaS.repository.EbookPdfRepository;
import com.ebookwriter.SaaS.repository.EbookRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Store covers are the real thing: the first page of the ebook's own PDF,
 * rendered to a JPEG. Rendering costs a PDF load, so results are kept in a small
 * in-memory LRU keyed by the ebook and its last update (a re-render of the book
 * produces a new cover automatically).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductCoverService {

    /** Cover width in pixels (≈2x a 300 px card). */
    static final int TARGET_WIDTH = 640;
    private static final int CACHE_SIZE = 64;

    private final EbookRepository ebookRepository;
    private final EbookPdfRepository pdfRepository;

    private final Map<String, byte[]> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    /** Cache-busting version of the ebook's cover (changes when the book is re-rendered). */
    public static String version(Ebook ebook) {
        return ebook.getUpdatedAt() == null ? "0"
                : Long.toString(ebook.getUpdatedAt().toEpochSecond(ZoneOffset.UTC));
    }

    /** JPEG of the PDF's first page, or empty when the ebook has no PDF. */
    @Transactional(readOnly = true)
    public Optional<byte[]> cover(UUID ebookId) {
        Optional<Ebook> ebook = ebookRepository.findById(ebookId);
        if (ebook.isEmpty()) {
            return Optional.empty();
        }
        String key = ebookId + ":" + version(ebook.get());
        synchronized (cache) {
            byte[] hit = cache.get(key);
            if (hit != null) {
                return Optional.of(hit);
            }
        }
        Optional<EbookPdf> pdf = pdfRepository.findById(ebookId);
        if (pdf.isEmpty()) {
            return Optional.empty();
        }
        byte[] jpeg = render(pdf.get().getData());
        if (jpeg == null) {
            return Optional.empty();
        }
        synchronized (cache) {
            cache.put(key, jpeg);
        }
        return Optional.of(jpeg);
    }

    static byte[] render(byte[] pdf) {
        try (PDDocument doc = PDDocument.load(pdf)) {
            if (doc.getNumberOfPages() == 0) {
                return null;
            }
            float widthPt = doc.getPage(0).getMediaBox().getWidth();
            float scale = widthPt > 0 ? TARGET_WIDTH / widthPt : 1f;
            BufferedImage image = new PDFRenderer(doc).renderImage(0, scale, ImageType.RGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "jpg", out);
            return out.toByteArray();
        } catch (IOException | RuntimeException e) {
            log.warn("[StoreCover] Could not render cover: {}", e.getMessage());
            return null;
        }
    }
}
