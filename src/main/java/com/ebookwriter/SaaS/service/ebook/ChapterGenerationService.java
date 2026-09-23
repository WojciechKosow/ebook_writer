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

    /** Write a chapter at its planned length (no credit pressure). */
    @Transactional
    public void generate(UUID ebookId, UUID chapterId) {
        generate(ebookId, chapterId, null);
    }

    /**
     * Write a chapter following the orchestrator's {@link ChapterDirective} (its
     * word target, whether it ends the book, and which planned chapters were left
     * out to end the book naturally). A {@code null} directive writes the chapter
     * at its planned length, ending the book if it is the last one.
     */
    @Transactional
    public void generate(UUID ebookId, UUID chapterId, ChapterDirective directive) {

        Ebook ebook = ebookRepository.findById(ebookId)
                .orElseThrow(() -> new IllegalArgumentException("Ebook not found: " + ebookId));
        List<EbookChapter> chapters = chapterRepository.findByEbookIdOrderByChapterNumberAsc(ebookId);

        EbookChapter chapter = chapters.stream()
                .filter(c -> c.getId().equals(chapterId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Chapter not found: " + chapterId));

        List<EbookChapter> inBook = ManuscriptContext.inBook(chapters);
        if (directive == null) {
            boolean last = !inBook.isEmpty()
                    && inBook.get(inBook.size() - 1).getId().equals(chapter.getId());
            directive = new ChapterDirective(plannedWords(chapter), false, last, List.of());
        }
        int targetWords = Math.max(WritingBudget.MIN_CHAPTER_WORDS, directive.targetWords());
        long maxTokens = outputTokenBudget(targetWords);

        String system = ChapterPrompts.system(ebook.getLanguage());
        String userPrompt = ChapterPrompts.user(
                ebook,
                ManuscriptContext.outline(inBook),
                chapter,
                ManuscriptContext.previousSummaries(inBook, chapter.getChapterNumber()),
                directive,
                availableImages(chapterId),
                positionOf(inBook, chapter),
                inBook.size()
        );

        AnthropicService.Completion completion =
                anthropicService.completeDetailed(system, userPrompt, maxTokens);
        String raw = completion.text();

        String[] parts = raw.split(ChapterPrompts.SUMMARY_DELIMITER, 2);
        String content = parts[0].trim();
        String summary = parts.length > 1 ? parts[1].trim() : "";

        // A response cut off by the token limit stops mid-thought (and never reached
        // the summary delimiter). Repair it back to the last complete block so the
        // chapter ends cleanly, and never keep a heading with no body at the end.
        if (completion.truncated() && parts.length == 1) {
            String repaired = ManuscriptIntegrity.repairTruncated(content);
            log.warn("Chapter {} of ebook {} hit the output limit; repaired its tail ({} -> {} chars)",
                    chapter.getChapterNumber(), ebookId, content.length(),
                    repaired == null ? 0 : repaired.length());
            content = repaired;
        } else {
            content = ManuscriptIntegrity.trimTrailingOrphans(content);
        }

        chapter.setContent(content);
        chapter.setSummary(summary);
        chapter.setStatus(ChapterStatus.WRITTEN);
        chapterRepository.save(chapter);

        log.info("Wrote chapter {}/{} of ebook {} ({} chars)",
                chapter.getChapterNumber(), chapters.size(), ebookId, content.length());
    }

    /** The chapter's planned length in words. */
    static int plannedWords(EbookChapter chapter) {
        return Math.max(WritingBudget.MIN_CHAPTER_WORDS, chapter.getApproxPages() * WORDS_PER_PAGE);
    }

    /**
     * Output tokens for a chapter of {@code targetWords}: generous headroom (words
     * → tokens, Markdown and component syntax, non-English text, adaptive thinking
     * and the summary) so a chapter that runs a little long to finish its thought
     * is never cut off. The target is the length signal; this is only a backstop.
     */
    static long outputTokenBudget(int targetWords) {
        return Math.min(MAX_OUTPUT_TOKENS, targetWords * 3L + 3000L);
    }

    /** 1-based position of the chapter among the chapters that are in the book. */
    private static int positionOf(List<EbookChapter> inBook, EbookChapter chapter) {
        for (int i = 0; i < inBook.size(); i++) {
            if (inBook.get(i).getId().equals(chapter.getId())) {
                return i + 1;
            }
        }
        return chapter.getChapterNumber();
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

