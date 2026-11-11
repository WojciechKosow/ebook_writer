package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.config.properties.AnthropicProperties;
import com.ebookwriter.SaaS.config.properties.KnowledgeWritingProperties;
import com.ebookwriter.SaaS.dto.blueprint.BookGenerationInput;
import com.ebookwriter.SaaS.dto.knowledge.NormalizedDocument;
import com.ebookwriter.SaaS.entity.ChapterStatus;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.entity.WritingQuality;
import com.ebookwriter.SaaS.prompt.BriefPrompts;
import com.ebookwriter.SaaS.repository.EbookChapterRepository;
import com.ebookwriter.SaaS.repository.EbookRepository;
import com.ebookwriter.SaaS.service.ai.AnthropicService;
import com.ebookwriter.SaaS.service.blueprint.BookBlueprintService;
import com.ebookwriter.SaaS.service.knowledge.KnowledgeDocumentStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Basic model, step 1: the premium model prepares the book for the writer. It
 * reads the plan and the author's material and writes a brief for every chapter
 * still to be written — its sections in order, which of the author's specifics
 * go where, the practical element, what to leave to other chapters, and what not
 * to invent ({@link BriefPrompts}). The lighter model then writes each chapter
 * from its brief ({@link ChapterGenerationService}). Better-model books skip this:
 * the premium model writes them itself.
 *
 * <p>Chapters are briefed {@link #BATCH} at a time, so the premium model sees how
 * material is spread across neighbouring chapters, and its cost stays a small
 * part of the book's (one request per batch, not per chapter).
 *
 * <p>Best-effort: a batch that fails or comes back unusable leaves its chapters
 * without a brief, and the writer writes them from the plan and the material as
 * a Better-model book would be — never a failed book.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChapterBriefService {

    /** Chapters briefed per premium-model request. */
    static final int BATCH = 8;

    /** Most words per brief — a plan, not a draft. */
    static final int MAX_BRIEF_WORDS = 350;

    /** Briefs longer than this (chars) are cut at a line break: the writer needs the plan, not an essay. */
    static final int MAX_BRIEF_CHARS = 4_000;

    /** Material shown per chapter in a brief request (chars); the writer still gets all of it. */
    static final int MAX_MATERIAL_CHARS = 9_000;

    private static final long MAX_TOKENS = 16_000L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AnthropicService anthropicService;
    private final AnthropicProperties anthropicProperties;
    private final EbookRepository ebookRepository;
    private final EbookChapterRepository chapterRepository;
    private final BookBlueprintService blueprintService;
    private final KnowledgeDocumentStore documentStore;
    private final KnowledgeWritingProperties writingProperties;

    /** Brief every chapter still to be written that has no brief yet. No-op for a Better-model book. */
    @Transactional
    public void prepare(UUID ebookId) {
        Ebook ebook = ebookRepository.findById(ebookId)
                .orElseThrow(() -> new IllegalArgumentException("Ebook not found: " + ebookId));
        if (ebook.effectiveWritingQuality() != WritingQuality.BASIC) {
            return;
        }
        List<EbookChapter> inBook = ManuscriptContext.inBook(chapterRepository.findByEbookIdOrderByChapterNumberAsc(ebookId));
        List<EbookChapter> todo = inBook.stream()
                .filter(c -> c.getStatus() != ChapterStatus.WRITTEN && c.getStatus() != ChapterStatus.EDITED)
                .filter(c -> c.getWritingBrief() == null || c.getWritingBrief().isBlank())
                .toList();
        if (todo.isEmpty()) {
            return;
        }

        Optional<BookGenerationInput> knowledge = ebook.isKnowledgeBased() && blueprintService != null
                ? blueprintService.getGenerationInput(ebookId) : Optional.empty();
        Map<String, NormalizedDocument> documents = knowledge.isPresent() && documentStore != null
                ? documentStore.documentsByRef(ebookId) : Map.of();
        String bookContext = knowledge.map(in -> KnowledgeChapterContext.build(in, null, documents, writingProperties).book())
                .orElse(ebook.getWritingGuidelines());
        String outline = ManuscriptContext.outline(inBook);
        String system = BriefPrompts.system(ebook.getLanguage(), MAX_BRIEF_WORDS);

        int briefed = 0;
        for (int from = 0; from < todo.size(); from += BATCH) {
            List<EbookChapter> batch = todo.subList(from, Math.min(todo.size(), from + BATCH));
            List<BriefPrompts.ChapterInput> inputs = new ArrayList<>();
            for (EbookChapter c : batch) {
                String material = knowledge.map(in -> material(KnowledgeChapterContext.build(
                        in, c.getBlueprintChapterId(), documents, writingProperties))).orElse("");
                inputs.add(new BriefPrompts.ChapterInput(c.getChapterNumber(), c.getTitle(), c.getDescription(),
                        ChapterGenerationService.plannedWords(c), material));
            }
            try {
                String raw = anthropicService.complete(system, BriefPrompts.user(ebook, bookContext, outline, inputs),
                        MAX_TOKENS, anthropicProperties.briefWriter());
                Map<Integer, String> briefs = parse(raw);
                for (EbookChapter c : batch) {
                    String brief = acceptable(briefs.get(c.getChapterNumber()));
                    if (brief != null) {
                        c.setWritingBrief(brief);
                        briefed++;
                    }
                }
                chapterRepository.saveAll(batch);
            } catch (RuntimeException e) {
                log.warn("Ebook {}: chapter briefs for chapters {}–{} unavailable ({}); they are written from the plan",
                        ebookId, batch.get(0).getChapterNumber(), batch.get(batch.size() - 1).getChapterNumber(),
                        e.getMessage());
            }
        }
        log.info("Ebook {}: the premium model briefed {} of {} chapter(s) for the Basic-model writer",
                ebookId, briefed, todo.size());
    }

    /** The material a brief request shows for one chapter: its knowledge, answers, gaps and quoted sources. */
    static String material(KnowledgeChapterContext.Context ctx) {
        StringBuilder sb = new StringBuilder();
        section(sb, "The author's knowledge", ctx.knowledge());
        section(sb, "The author's answers", ctx.answers());
        section(sb, "Not provided by the author (do not invent)", ctx.gaps());
        section(sb, "The author's own material, quoted", ctx.excerpts());
        String s = sb.toString().strip();
        return s.length() <= MAX_MATERIAL_CHARS ? s : s.substring(0, MAX_MATERIAL_CHARS) + "\n[…]";
    }

    private static void section(StringBuilder sb, String label, String body) {
        if (body != null && !body.isBlank()) {
            sb.append(label).append(":\n").append(body.strip()).append("\n\n");
        }
    }

    /** chapterNumber → brief from the model's JSON. */
    static Map<Integer, String> parse(String raw) {
        Map<Integer, String> out = new HashMap<>();
        int start = raw == null ? -1 : raw.indexOf('{');
        int end = raw == null ? -1 : raw.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new IllegalStateException("model did not return a JSON object");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(raw.substring(start, end + 1));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("model returned invalid JSON: " + e.getMessage());
        }
        for (JsonNode n : root.path("chapters")) {
            int number = n.path("chapterNumber").asInt(-1);
            String brief = n.path("brief").asText(null);
            if (number > 0 && brief != null) {
                out.put(number, brief);
            }
        }
        return out;
    }

    /** A usable brief, trimmed to {@link #MAX_BRIEF_CHARS} at a line break; null when empty. */
    static String acceptable(String brief) {
        if (brief == null || brief.isBlank()) {
            return null;
        }
        String s = brief.strip();
        if (s.length() > MAX_BRIEF_CHARS) {
            int cut = s.lastIndexOf('\n', MAX_BRIEF_CHARS);
            s = s.substring(0, cut > MAX_BRIEF_CHARS / 2 ? cut : MAX_BRIEF_CHARS).strip();
        }
        return s;
    }
}
