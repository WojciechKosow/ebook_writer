package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.config.properties.OpenAiProperties;
import com.ebookwriter.SaaS.dto.image.ImagePlan;
import com.ebookwriter.SaaS.entity.ContentSource;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.entity.EbookImage;
import com.ebookwriter.SaaS.entity.EbookImagePlacement;
import com.ebookwriter.SaaS.repository.EbookChapterRepository;
import com.ebookwriter.SaaS.repository.EbookImageRepository;
import com.ebookwriter.SaaS.repository.EbookRepository;
import com.ebookwriter.SaaS.service.image.OpenAiImageClient;
import com.ebookwriter.SaaS.service.storage.R2StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Image Generator. Takes the validated {@code List<ImagePlan>} from the
 * planner and, for each plan, generates the image via {@link OpenAiImageClient},
 * stores the bytes in the same private R2 bucket as uploaded assets, records an
 * {@link EbookImage} (the "generated image" — distinct from the plan), and asks
 * {@link ImagePlacementService} to drop its inline token into the target
 * chapter's Markdown.
 *
 * <p>Failures are isolated per image: if one image fails to generate or store,
 * it is logged and skipped and the remaining images (and the book) still
 * complete. Nothing here fails the whole generation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageGenerationService {

    private final OpenAiImageClient imageClient;
    private final OpenAiProperties openAiProperties;
    private final R2StorageService storage;
    private final EbookRepository ebookRepository;
    private final EbookChapterRepository chapterRepository;
    private final EbookImageRepository imageRepository;
    private final ImagePlacementService placementService;

    /**
     * Generate, store and place every image in {@code plans}. Returns how many
     * images were successfully produced. A no-op (returns 0) when the plan is
     * empty or the pipeline/storage isn't configured.
     */
    @Transactional
    public int generate(UUID ebookId, List<ImagePlan> plans) {
        if (plans == null || plans.isEmpty()) {
            return 0;
        }
        if (!openAiProperties.isConfigured() || !storage.isConfigured()) {
            log.info("Skipping {} planned image(s) for ebook {}: {} not configured",
                    plans.size(), ebookId,
                    !openAiProperties.isConfigured() ? "OpenAI" : "image storage (R2)");
            return 0;
        }

        Ebook ebook = ebookRepository.findById(ebookId)
                .orElseThrow(() -> new IllegalArgumentException("Ebook not found: " + ebookId));
        Map<Integer, EbookChapter> byNumber = new HashMap<>();
        for (EbookChapter c : chapterRepository.findByEbookIdOrderByChapterNumberAsc(ebookId)) {
            byNumber.put(c.getChapterNumber(), c);
        }

        int succeeded = 0;
        for (ImagePlan plan : plans) {
            EbookChapter chapter = byNumber.get(plan.chapterNumber());
            if (chapter == null) {
                continue; // chapter went away between planning and generating
            }
            try {
                generateOne(ebook, chapter, plan);
                succeeded++;
            } catch (RuntimeException e) {
                // Isolated: one image failing must not lose the others or the book.
                log.warn("Generated image {} for ebook {} failed; skipping it: {}",
                        plan.id(), ebookId, e.getMessage());
            }
        }
        log.info("Generated {}/{} planned image(s) for ebook {}", succeeded, plans.size(), ebookId);
        return succeeded;
    }

    private void generateOne(Ebook ebook, EbookChapter chapter, ImagePlan plan) {
        OpenAiImageClient.GeneratedImage generated =
                imageClient.generate(plan.generationPrompt(), plan.aspectRatio());
        byte[] bytes = generated.bytes();

        String key = "ebooks/" + ebook.getId() + "/images/" + UUID.randomUUID() + ".png";
        storage.upload(key, bytes, generated.contentType());

        int[] dims = readDimensions(bytes);
        EbookImage image = EbookImage.builder()
                .ebook(ebook)
                // Placement is set explicitly here and stays in sync with the token
                // that ImagePlacementService writes into the chapter Markdown.
                .placement(EbookImagePlacement.CHAPTER)
                .chapter(chapter)
                .role(plan.type().toRole())
                .placedBy(ContentSource.AI)
                .storageKey(key)
                .contentType(generated.contentType())
                .originalFilename(plan.id() + ".png")
                .aiDescription(plan.description() != null ? plan.description() : plan.purpose())
                .tags("ai-generated," + plan.type().name().toLowerCase())
                .sizeBytes(bytes.length)
                .width(dims[0])
                .height(dims[1])
                .build();
        image = imageRepository.save(image); // id is DB-generated on insert

        // Layout: place the inline token at the planner's semantic anchor. The
        // Markdown is the source of truth, so this drives both editor and PDF.
        placementService.place(chapter, image.markdownRef(), plan.anchorHeading(), plan.altText());
        chapterRepository.save(chapter);

        log.info("Generated image {} ({} KB) into chapter {} of ebook {}",
                image.getId(), bytes.length / 1024, chapter.getChapterNumber(), ebook.getId());
    }

    /** Best-effort pixel dimensions; {@code [0, 0]} if they can't be read. */
    private static int[] readDimensions(byte[] bytes) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img != null) {
                return new int[]{img.getWidth(), img.getHeight()};
            }
        } catch (Exception e) {
            // dimensions are optional metadata
        }
        return new int[]{0, 0};
    }
}
