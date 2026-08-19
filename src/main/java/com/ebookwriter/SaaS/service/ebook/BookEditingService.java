package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.entity.ChapterStatus;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.config.properties.AnthropicProperties;
import com.ebookwriter.SaaS.prompt.EditingPrompts;
import com.ebookwriter.SaaS.repository.EbookChapterRepository;
import com.ebookwriter.SaaS.repository.EbookRepository;
import com.ebookwriter.SaaS.service.ai.AnthropicService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Step 3 — editorial pass over one chapter, aware of the rest of the book via
 * the outline and the other chapters' summaries.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookEditingService {

    private static final long MAX_OUTPUT_TOKENS = 16000L;

    private final AnthropicService anthropicService;
    private final AnthropicProperties anthropicProperties;
    private final EbookRepository ebookRepository;
    private final EbookChapterRepository chapterRepository;

    @Transactional
    public void edit(UUID ebookId, UUID chapterId) {

        Ebook ebook = ebookRepository.findById(ebookId)
                .orElseThrow(() -> new IllegalArgumentException("Ebook not found: " + ebookId));
        List<EbookChapter> chapters = chapterRepository.findByEbookIdOrderByChapterNumberAsc(ebookId);

        EbookChapter chapter = chapters.stream()
                .filter(c -> c.getId().equals(chapterId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Chapter not found: " + chapterId));

        if (chapter.getContent() == null || chapter.getContent().isBlank()) {
            log.warn("Skipping edit of empty chapter {} in ebook {}", chapterId, ebookId);
            return;
        }

        // Size the ceiling off the existing content so the editor has room to
        // return the full chapter plus modest expansion.
        long estimatedTokens = chapter.getContent().length() / 3L + 2000L;
        long maxTokens = Math.min(MAX_OUTPUT_TOKENS, estimatedTokens);

        String system = EditingPrompts.system(ebook.getLanguage());
        String userPrompt = EditingPrompts.user(
                ebook,
                ManuscriptContext.outline(chapters),
                chapter,
                ManuscriptContext.otherSummaries(chapters, chapter.getChapterNumber())
        );

        String edited = anthropicService
                .complete(system, userPrompt, maxTokens, anthropicProperties.resolveEditingModel())
                .trim();

        if (!edited.isEmpty()) {
            chapter.setContent(edited);
        }
        chapter.setStatus(ChapterStatus.EDITED);
        chapterRepository.save(chapter);

        log.info("Edited chapter {}/{} of ebook {}",
                chapter.getChapterNumber(), chapters.size(), ebookId);
    }
}
