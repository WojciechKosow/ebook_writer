package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.entity.ChapterStatus;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.entity.EbookImage;
import com.ebookwriter.SaaS.prompt.ChapterPrompts;
import com.ebookwriter.SaaS.repository.EbookChapterRepository;
import com.ebookwriter.SaaS.repository.EbookImageRepository;
import com.ebookwriter.SaaS.repository.EbookRepository;
import com.ebookwriter.SaaS.service.ai.AnthropicService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Step 2 — write a single chapter, using the outline and earlier chapters'
 * summaries as context so content builds forward without repeating.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChapterGenerationService {

    /**
     * Words that fill one rendered page in the actual 6×9" book layout. Measured
     * empirically against the real PDF pipeline (see WordsPerPageCalibrationTest)
     * — the layout fits ~200 words per content page, NOT the ~450 a plain-text
     * estimate suggests. Using the true value keeps generated length close to the
     * requested page count instead of overshooting it 2–3×.
     */
    static final int WORDS_PER_PAGE = 200;

    private static final long MAX_OUTPUT_TOKENS = 16000L;

    private final AnthropicService anthropicService;
    private final EbookRepository ebookRepository;
    private final EbookChapterRepository chapterRepository;
    private final EbookImageRepository imageRepository;

    @Transactional
    public void generate(UUID ebookId, UUID chapterId) {

        Ebook ebook = ebookRepository.findById(ebookId)
                .orElseThrow(() -> new IllegalArgumentException("Ebook not found: " + ebookId));
        List<EbookChapter> chapters = chapterRepository.findByEbookIdOrderByChapterNumberAsc(ebookId);

        EbookChapter chapter = chapters.stream()
                .filter(c -> c.getId().equals(chapterId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Chapter not found: " + chapterId));

        int targetWords = Math.max(300, chapter.getApproxPages() * WORDS_PER_PAGE);
        long maxTokens = Math.min(MAX_OUTPUT_TOKENS, targetWords * 2L + 1000L);

        String system = ChapterPrompts.system(ebook.getLanguage());
        String userPrompt = ChapterPrompts.user(
                ebook,
                ManuscriptContext.outline(chapters),
                chapter,
                ManuscriptContext.previousSummaries(chapters, chapter.getChapterNumber()),
                targetWords,
                availableImages(chapterId),
                chapters.size()
        );

        String raw = anthropicService.complete(system, userPrompt, maxTokens);

        String[] parts = raw.split(ChapterPrompts.SUMMARY_DELIMITER, 2);
        String content = parts[0].trim();
        String summary = parts.length > 1 ? parts[1].trim() : "";

        chapter.setContent(content);
        chapter.setSummary(summary);
        chapter.setStatus(ChapterStatus.WRITTEN);
        chapterRepository.save(chapter);

        log.info("Wrote chapter {}/{} of ebook {} ({} chars)",
                chapter.getChapterNumber(), chapters.size(), ebookId, content.length());
    }

    /**
     * The images the placement step assigned to this chapter, formatted as a
     * short list the writer can draw from (each line gives the exact token and a
     * description). Empty when nothing is assigned, so the prompt offers no
     * images and the model adds none.
     */
    private String availableImages(UUID chapterId) {
        List<EbookImage> assigned = imageRepository.findByChapterId(chapterId);
        if (assigned.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (EbookImage a : assigned) {
            String desc = (a.getAiDescription() != null && !a.getAiDescription().isBlank())
                    ? a.getAiDescription().trim()
                    : (a.getOriginalFilename() != null ? a.getOriginalFilename() : "image");
            sb.append("  - token: ").append(a.markdownRef())
                    .append(" | ").append(desc).append("\n");
        }
        return sb.toString();
    }
}

