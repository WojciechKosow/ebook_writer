package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.config.properties.KnowledgeWritingProperties;
import com.ebookwriter.SaaS.dto.blueprint.BookGenerationInput;
import com.ebookwriter.SaaS.entity.ChapterStatus;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.entity.EbookImage;
import com.ebookwriter.SaaS.prompt.ChapterPrompts;
import com.ebookwriter.SaaS.prompt.KnowledgeChapterPrompts;
import com.ebookwriter.SaaS.repository.EbookChapterRepository;
import com.ebookwriter.SaaS.repository.EbookImageRepository;
import com.ebookwriter.SaaS.repository.EbookRepository;
import com.ebookwriter.SaaS.service.ai.AnthropicService;
import com.ebookwriter.SaaS.service.blueprint.BookBlueprintService;
import com.ebookwriter.SaaS.service.knowledge.KnowledgeDocumentStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Step 2 — write a single chapter, using the outline and earlier chapters'
 * summaries as context so content builds forward without repeating.
 *
 * <p>Two prompt paths, same output handling and persistence:
 * <ul>
 *   <li><b>legacy</b> books — {@link ChapterPrompts}: the brief and outline;</li>
 *   <li><b>knowledge-based</b> books — {@link KnowledgeChapterPrompts}: the book
 *       context plus the knowledge, answers, gaps and source excerpts selected
 *       for THIS chapter by {@link KnowledgeChapterContext} from the author's
 *       BookKnowledge + blueprint.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChapterGenerationService {

    /**
     * Words that fill one rendered page in the actual 6×9" book layout. Measured
     * empirically against the real PDF pipeline (see WordsPerPageCalibrationTest)
     * — the layout fits ~200 words per content page, NOT the ~450 a plain-text
     * estimate suggests. Using the true value keeps a chapter's written length
     * close to the size it was planned with (and the credit pacing accurate).
     */
    static final int WORDS_PER_PAGE = 200;

    private static final long MAX_OUTPUT_TOKENS = 16000L;

    private final AnthropicService anthropicService;
    private final EbookRepository ebookRepository;
    private final EbookChapterRepository chapterRepository;
    private final EbookImageRepository imageRepository;
    private final BookBlueprintService blueprintService;
    private final KnowledgeDocumentStore documentStore;
    private final KnowledgeWritingProperties writingProperties;

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

        // What the chapter is written with beyond its brief: everything earlier
        // chapters already explained (refer back, never re-explain) and the book
        // template (the same recurring sections in every chapter, or none).
        WritingContext writing = new WritingContext(
                TopicRegistry.forChapter(inBook, chapter.getChapterNumber()),
                BookTemplate.fromJson(ebook.getChapterTemplateJson()));

        String system;
        String userPrompt;
        if (ebook.isKnowledgeBased()) {
            BookGenerationInput input = blueprintService.getGenerationInput(ebookId)
                    .orElseThrow(() -> new IllegalStateException(
                            "The book's knowledge or blueprint is no longer available for writing."));
            KnowledgeChapterContext.Context ctx = KnowledgeChapterContext.build(
                    input, chapter.getBlueprintChapterId(), documentStore.documentsByRef(ebookId), writingProperties);
            system = KnowledgeChapterPrompts.system(ebook.getLanguage());
            userPrompt = KnowledgeChapterPrompts.user(ebook, ManuscriptContext.outline(inBook), chapter,
                    ManuscriptContext.previousSummaries(inBook, chapter.getChapterNumber()), ctx, directive,
                    availableImages(chapterId), positionOf(inBook, chapter), inBook.size(), writing);
            log.info("Chapter {} of ebook {}: knowledge context {} chars ({} knowledge items, sources {}), prompt {} chars",
                    chapter.getChapterNumber(), ebookId, ctx.totalChars(), ctx.itemCount(), ctx.excerptRefs(),
                    userPrompt.length());
        } else {
            system = ChapterPrompts.system(ebook.getLanguage());
            userPrompt = ChapterPrompts.user(
                    ebook,
                    ManuscriptContext.outline(inBook),
                    chapter,
                    ManuscriptContext.previousSummaries(inBook, chapter.getChapterNumber()),
                    directive,
                    availableImages(chapterId),
                    positionOf(inBook, chapter),
                    inBook.size(),
                    writing
            );
        }

        // No unit reaches the book incomplete. A response cut off by the output
        // limit is continued from where it stopped; the result is then checked
        // structurally (unterminated sentence, unclosed block, a lead-in whose
        // block never came, a heading with nothing under it). An incomplete
        // chapter is written again; if that fails too, the chapter fails loudly
        // — never a silently shortened chapter.
        Draft draft = null;
        List<CompletenessCheck.Problem> problems = List.of();
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            AnthropicService.Completion completion =
                    LongForm.complete(anthropicService, system, userPrompt, maxTokens, null);
            draft = Draft.parse(completion.text());
            problems = new ArrayList<>(CompletenessCheck.inspect(draft.content(), directive.finalChapter()));
            if (completion.truncated()) {
                problems.add(0, new CompletenessCheck.Problem(CompletenessCheck.Kind.TRUNCATED, null));
            }
            if (problems.isEmpty()) {
                break;
            }
            log.warn("Chapter {} of ebook {} is incomplete (attempt {}/{}): {}", chapter.getChapterNumber(), ebookId,
                    attempt, MAX_ATTEMPTS, describe(problems));
        }
        if (!problems.isEmpty()) {
            throw new IncompleteContentException("Chapter " + chapter.getChapterNumber() + " (\""
                    + chapter.getTitle() + "\") is incomplete after " + MAX_ATTEMPTS + " attempts: "
                    + describe(problems));
        }

        String content = ManuscriptIntegrity.trimTrailingOrphans(draft.content());
        chapter.setContent(content);
        chapter.setSummary(draft.summary());
        chapter.setCoveredTopics(TopicRegistry.entries(draft.topics(), content));
        chapter.setStatus(ChapterStatus.WRITTEN);
        chapter.setGenerationError(null);
        chapterRepository.save(chapter);

        double pages = LengthMeter.pages(content);
        double budgetPages = (double) directive.targetWords() / WORDS_PER_PAGE;
        if (budgetPages > 0 && Math.abs(pages - budgetPages) / budgetPages > ChapterPrompts.LENGTH_TOLERANCE) {
            log.warn("Chapter {} of ebook {}: ~{} pages against a budget of ~{} (more than {}% off)",
                    chapter.getChapterNumber(), ebookId, String.format(java.util.Locale.ROOT, "%.1f", pages),
                    String.format(java.util.Locale.ROOT, "%.1f", budgetPages),
                    Math.round(ChapterPrompts.LENGTH_TOLERANCE * 100));
        }
        log.info("Wrote chapter {}/{} of ebook {} ({} chars, ~{} pages)", chapter.getChapterNumber(),
                chapters.size(), ebookId, content.length(), String.format(java.util.Locale.ROOT, "%.1f", pages));
    }

    /** Full generations of a chapter before it fails as incomplete (each with its own continuations). */
    static final int MAX_ATTEMPTS = 2;

    /** A chapter's body, its summary and its topic registry entries, split at their delimiters. */
    record Draft(String content, String summary, String topics) {
        static Draft parse(String raw) {
            String[] parts = raw.split(java.util.regex.Pattern.quote(ChapterPrompts.SUMMARY_DELIMITER), 2);
            String content = parts[0].strip();
            String rest = parts.length > 1 ? parts[1] : "";
            String[] tail = rest.split(java.util.regex.Pattern.quote(ChapterPrompts.TOPICS_DELIMITER), 2);
            return new Draft(content, tail[0].strip(), tail.length > 1 ? tail[1].strip() : "");
        }
    }

    /** Thrown when a chapter is still incomplete after every attempt; the chapter is marked FAILED with it. */
    static class IncompleteContentException extends RuntimeException {
        IncompleteContentException(String message) {
            super(message);
        }
    }

    static String describe(List<CompletenessCheck.Problem> problems) {
        return String.join("; ", problems.stream().map(CompletenessCheck.Problem::describe).toList());
    }

    /** The chapter's planned length in words. */
    static int plannedWords(EbookChapter chapter) {
        return Math.max(WritingBudget.MIN_CHAPTER_WORDS, chapter.getApproxPages() * WORDS_PER_PAGE);
    }

    /**
     * Output tokens for a chapter of {@code targetWords}: generous headroom (words
     * → tokens, Markdown and component syntax, non-English text, adaptive thinking
     * and the summary) so a chapter that runs a little long to finish its thought
     * is never cut off. The planned size is orientation; this is only a backstop.
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

