package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.config.properties.OpenAiProperties;
import com.ebookwriter.SaaS.dto.EbookImageDTO;
import com.ebookwriter.SaaS.dto.cover.CoverLayout;
import com.ebookwriter.SaaS.dto.cover.CoverPlan;
import com.ebookwriter.SaaS.entity.ContentSource;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.entity.EbookImage;
import com.ebookwriter.SaaS.entity.EbookImagePlacement;
import com.ebookwriter.SaaS.entity.EbookImageRole;
import com.ebookwriter.SaaS.entity.EbookStatus;
import com.ebookwriter.SaaS.prompt.CoverPrompts;
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
import java.util.List;
import java.util.UUID;

/**
 * Generates a book's <b>cover visual</b> and stores it as an independent,
 * editable {@link EbookImage} asset (placement {@code COVER}) — never a flattened
 * cover. The AI produces only the text-free image; Scrivetta renders the title,
 * subtitle, author and imprint on top, and picks the {@link CoverLayout}. So the
 * cover stays a composition of separate elements the user can edit, replace,
 * regenerate or reposition.
 *
 * <p>Best-effort, mirroring the inline-image pipeline: with no OpenAI key, images
 * disabled, or a generation failure, the book keeps its safe typographic cover
 * rather than a broken one. The generation-time entry point never throws; the
 * editor-triggered {@link #regenerate} surfaces a clear error if generation is
 * genuinely unavailable, since the user explicitly asked for a new visual.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoverGenerationService {

    private final OpenAiProperties openAiProperties;
    private final OpenAiImageClient imageClient;
    private final R2StorageService storage;
    private final EbookRepository ebookRepository;
    private final EbookChapterRepository chapterRepository;
    private final EbookImageRepository imageRepository;
    private final CoverPlanningService coverPlanningService;
    private final PdfGenerationService pdfGenerationService;

    /**
     * Generation-time cover step. No-op (leaves the typographic cover) when the
     * pipeline can't generate images or the user already chose a cover. Sets
     * {@link Ebook#getCoverLayout()} and, when the plan calls for a visual, creates
     * the COVER asset. Never throws — a cover failure must not fail the book.
     */
    @Transactional
    public void generateForBook(UUID ebookId) {
        try {
            Ebook ebook = ebookRepository.findById(ebookId).orElse(null);
            if (ebook == null) {
                return;
            }
            // Respect a cover the user already set — never overwrite their choice.
            EbookImage existingCover = currentCover(ebookId);
            if (existingCover != null && existingCover.getPlacedBy() == ContentSource.USER) {
                log.info("Ebook {} already has a user-set cover; skipping AI cover", ebookId);
                return;
            }
            if (!canGenerate()) {
                // Leave the layout null -> the renderer uses the typographic cover.
                log.info("Cover generation unavailable for ebook {} (OpenAI/storage not configured); "
                        + "using typographic cover", ebookId);
                return;
            }

            CoverPlan plan = coverPlanningService.plan(ebook, chapters(ebookId));
            ebook.setCoverLayout(plan.layout());
            ebookRepository.save(ebook);

            if (plan.hasVisual()) {
                generateVisual(ebook, plan);
            } else {
                log.info("Cover plan for ebook {} is typographic; no visual generated", ebookId);
            }
        } catch (RuntimeException e) {
            // Best-effort: fall back to a safe typographic cover.
            log.warn("Cover generation failed for ebook {}; keeping typographic cover: {}",
                    ebookId, e.getMessage());
            ebookRepository.findById(ebookId).ifPresent(ebook -> {
                if (currentCover(ebookId) == null) {
                    ebook.setCoverLayout(CoverLayout.TYPOGRAPHIC);
                    ebookRepository.save(ebook);
                }
            });
        }
    }

    /**
     * Regenerate the cover visual on demand from the editor, keeping the title,
     * subtitle and (where it still calls for a visual) the current layout. Unlike
     * the generation-time step this surfaces an error when generation is
     * unavailable, because the user explicitly asked for a new visual. Ownership-
     * scoped; re-renders a COMPLETED book so the download matches.
     */
    @Transactional
    public EbookImageDTO regenerate(UUID ebookId, UUID userId) {
        Ebook ebook = ebookRepository.findByIdAndUserId(ebookId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Ebook not found"));
        if (!canGenerate()) {
            throw new IllegalStateException(
                    "Cover generation is not available (image generation is not configured).");
        }

        CoverPlan plan = coverPlanningService.plan(ebook, chapters(ebookId));
        // Keep the existing layout when it still uses a visual; otherwise adopt the
        // plan's (or a strong default) so the regenerated image has a home.
        CoverLayout current = ebook.getCoverLayout();
        CoverLayout layout = (current != null && current.requiresVisual())
                ? current
                : (plan.layout().requiresVisual() ? plan.layout() : CoverLayout.EDITORIAL);
        ebook.setCoverLayout(layout);
        ebookRepository.save(ebook);

        EbookImage image = generateVisual(ebook, plan);

        if (ebook.getStatus() == EbookStatus.COMPLETED) {
            pdfGenerationService.renderAndStore(ebookId, 0);
        }
        return EbookImageDTO.from(image);
    }

    // ---- internals ----------------------------------------------------------

    /** Generate the visual, store it in R2, and record it as the COVER asset. */
    private EbookImage generateVisual(Ebook ebook, CoverPlan plan) {
        // The hard no-text/no-logo constraints are always appended in code, so the
        // asset can never carry baked-in typography even if the plan forgot.
        String prompt = plan.imagePrompt().strip() + CoverPrompts.IMAGE_CONSTRAINTS;
        OpenAiImageClient.GeneratedImage generated = imageClient.generate(prompt, plan.aspectRatio());
        byte[] bytes = generated.bytes();

        String key = "ebooks/" + ebook.getId() + "/images/" + UUID.randomUUID() + ".png";
        storage.upload(key, bytes, generated.contentType());

        clearPreviousCover(ebook.getId());

        int[] dims = readDimensions(bytes);
        EbookImage image = EbookImage.builder()
                .ebook(ebook)
                .placement(EbookImagePlacement.COVER)
                .role(EbookImageRole.COVER)
                .placedBy(ContentSource.AI)
                .storageKey(key)
                .contentType(generated.contentType())
                .originalFilename("cover.png")
                .aiDescription(plan.visualConcept())
                .tags("ai-generated,cover")
                .sizeBytes(bytes.length)
                .width(dims[0])
                .height(dims[1])
                .build();
        image = imageRepository.save(image);
        log.info("Generated cover visual {} ({} KB, layout {}) for ebook {}",
                image.getId(), bytes.length / 1024, plan.layout(), ebook.getId());
        return image;
    }

    /**
     * Make room for a new cover: delete a previous <em>AI</em> cover asset
     * outright (its only purpose was to be the cover), and demote a user-uploaded
     * cover to UNUSED so the asset stays in the library but is no longer the cover.
     */
    private void clearPreviousCover(UUID ebookId) {
        for (EbookImage cover : imageRepository.findByEbookIdAndPlacement(ebookId, EbookImagePlacement.COVER)) {
            if (cover.getPlacedBy() == ContentSource.AI) {
                storage.delete(cover.getStorageKey());
                imageRepository.delete(cover);
            } else {
                cover.setPlacement(EbookImagePlacement.UNUSED);
                cover.setChapter(null);
                imageRepository.save(cover);
            }
        }
    }

    private EbookImage currentCover(UUID ebookId) {
        return imageRepository.findByEbookIdAndPlacement(ebookId, EbookImagePlacement.COVER)
                .stream().findFirst().orElse(null);
    }

    private List<EbookChapter> chapters(UUID ebookId) {
        return chapterRepository.findByEbookIdOrderByChapterNumberAsc(ebookId);
    }

    private boolean canGenerate() {
        return openAiProperties.isConfigured() && storage.isConfigured();
    }

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
