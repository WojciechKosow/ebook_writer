package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.entity.ChapterStatus;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.config.properties.AnthropicProperties;
import com.ebookwriter.SaaS.prompt.EditingPrompts;
import com.ebookwriter.SaaS.prompt.KnowledgeChapterPrompts;
import com.ebookwriter.SaaS.service.blueprint.BookBlueprintService;
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
    private final BookBlueprintService blueprintService;

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
        long estimatedTokens = chapter.getContent().length() / 2L + 3000L;
        long maxTokens = Math.min(MAX_OUTPUT_TOKENS, estimatedTokens);

        String system = EditingPrompts.system(ebook.getLanguage());
        List<EbookChapter> inBook = ManuscriptContext.inBook(chapters);
        boolean finalChapter = !inBook.isEmpty()
                && inBook.get(inBook.size() - 1).getId().equals(chapter.getId());
        // Edited against what the book explained before this chapter (so a
        // re-explanation becomes a reference back) and the book template.
        WritingContext writing = new WritingContext(
                TopicRegistry.forChapter(inBook, chapter.getChapterNumber()),
                BookTemplate.fromJson(ebook.getChapterTemplateJson()));
        String userPrompt = EditingPrompts.user(
                ebook,
                ManuscriptContext.outline(inBook),
                chapter,
                ManuscriptContext.otherSummaries(inBook, chapter.getChapterNumber()),
                finalChapter,
                knowledgeAddendum(ebook),
                writing
        );

        AnthropicService.Completion completion = LongForm.complete(anthropicService, system, userPrompt, maxTokens,
                anthropicProperties.resolveEditingModel());
        String edited = ManuscriptIntegrity.trimTrailingOrphans(completion.text().trim());

        // An edit must be at least as complete as the chapter it replaces. One
        // that is still cut off after its continuations, or that introduces a
        // structural gap the original did not have (a sentence left open, a
        // block announced but missing), is discarded in favour of the original.
        List<CompletenessCheck.Problem> before = CompletenessCheck.inspect(chapter.getContent(), finalChapter);
        List<CompletenessCheck.Problem> after = edited.isEmpty() ? List.of()
                : CompletenessCheck.inspect(edited, finalChapter);
        boolean regressed = after.size() > before.size();
        if (completion.truncated() || edited.isEmpty() || regressed) {
            log.warn("Edit of chapter {} in ebook {} discarded ({}); keeping the unedited chapter",
                    chapter.getChapterNumber(), ebookId,
                    completion.truncated() ? "still cut off after continuing"
                            : edited.isEmpty() ? "empty" : ChapterGenerationService.describe(after));
        } else {
            chapter.setContent(edited);
            // The box entries follow the edited text; the writer's own entries stay.
            chapter.setCoveredTopics(TopicRegistry.entries(chapter.getCoveredTopics(), edited));
        }
        chapter.setStatus(ChapterStatus.EDITED);
        chapterRepository.save(chapter);

        log.info("Edited chapter {}/{} of ebook {}",
                chapter.getChapterNumber(), chapters.size(), ebookId);
    }

    /** Knowledge-based books: keep the author's specifics through the editorial pass. */
    private String knowledgeAddendum(Ebook ebook) {
        if (!ebook.isKnowledgeBased()) return null;
        return blueprintService.getGenerationInput(ebook.getId())
                .map(in -> KnowledgeChapterPrompts.editingAddendum(
                        KnowledgeChapterContext.bookContext(in.knowledge(), in.blueprint())))
                .orElse(null);
    }
}
