package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.entity.ContentSource;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.entity.EbookImage;
import com.ebookwriter.SaaS.entity.EbookImagePlacement;
import com.ebookwriter.SaaS.repository.EbookChapterRepository;
import com.ebookwriter.SaaS.repository.EbookImageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Keeps each asset's stored placement in sync with the chapter Markdown, which
 * is the source of truth for where inline images actually appear. Called after
 * generation and after every editor save so the asset library's "usage"
 * information (and the AI-vs-user placement flag) stays accurate no matter how
 * the manuscript was changed.
 *
 * <p>Cover placements live outside chapter text, so they are never touched here
 * — only chapter (inline) usage is derived from the Markdown.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetUsageService {

    private final EbookChapterRepository chapterRepository;
    private final EbookImageRepository imageRepository;

    /**
     * Recompute inline placement for every non-cover asset of the ebook from the
     * chapter Markdown. An asset whose {@code ebook-image:<id>} token appears in a
     * chapter is placed in the first such chapter; one whose token appears nowhere
     * becomes UNUSED. Any placement this changes is attributed to {@code changedBy}.
     */
    @Transactional
    public void sync(UUID ebookId, ContentSource changedBy) {
        List<EbookChapter> chapters = chapterRepository.findByEbookIdOrderByChapterNumberAsc(ebookId);
        List<EbookImage> images = imageRepository.findByEbookIdOrderByCreatedAtAsc(ebookId);

        for (EbookImage image : images) {
            if (image.getPlacement() == EbookImagePlacement.COVER) {
                continue; // cover is managed explicitly, not via Markdown
            }
            String token = image.markdownRef();
            EbookChapter placedIn = chapters.stream()
                    .filter(c -> c.getContent() != null && c.getContent().contains(token))
                    .findFirst()
                    .orElse(null);

            UUID currentChapterId = image.getChapter() != null ? image.getChapter().getId() : null;
            UUID newChapterId = placedIn != null ? placedIn.getId() : null;
            boolean changed = image.getPlacement()
                    != (placedIn != null ? EbookImagePlacement.CHAPTER : EbookImagePlacement.UNUSED)
                    || !java.util.Objects.equals(currentChapterId, newChapterId);

            if (changed) {
                image.setPlacement(placedIn != null
                        ? EbookImagePlacement.CHAPTER : EbookImagePlacement.UNUSED);
                image.setChapter(placedIn);
                image.setPlacedBy(changedBy);
                imageRepository.save(image);
            }
        }
    }
}
