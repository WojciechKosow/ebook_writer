package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.prompt.BookTemplatePrompts;
import com.ebookwriter.SaaS.repository.EbookChapterRepository;
import com.ebookwriter.SaaS.repository.EbookRepository;
import com.ebookwriter.SaaS.service.ai.AnthropicService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Step 1.2 — after the outline exists, decide the book template (the recurring
 * sections every chapter ends with, or none) and write each chapter's
 * reader-facing subtitle. One call for the whole book, for briefed and
 * blueprint books alike.
 *
 * <p>Reader texts are generated at their final length. A subtitle that comes
 * back too long is asked for again, shorter; one that is still too long, empty,
 * cut off, or simply a copy of the internal brief is dropped — never truncated
 * and never replaced by the brief. Best-effort: if the call fails the book gets
 * an empty template (a consistent "no recurring sections") and no subtitles.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookTemplateService {

    /** The length subtitles are written to. */
    static final int SUBTITLE_CHARS = 110;
    private static final long MAX_TOKENS = 6000L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AnthropicService anthropicService;
    private final EbookRepository ebookRepository;
    private final EbookChapterRepository chapterRepository;

    @Transactional
    public void prepare(UUID ebookId) {
        Ebook ebook = ebookRepository.findById(ebookId)
                .orElseThrow(() -> new IllegalArgumentException("Ebook not found: " + ebookId));
        List<EbookChapter> chapters = ManuscriptContext.inBook(
                chapterRepository.findByEbookIdOrderByChapterNumberAsc(ebookId));

        BookTemplate template = BookTemplate.none();
        Map<Integer, String> subtitles = new HashMap<>();
        try {
            String raw = anthropicService.complete(
                    BookTemplatePrompts.system(ebook.getLanguage(), SUBTITLE_CHARS),
                    BookTemplatePrompts.user(ebook, chapters), MAX_TOKENS);
            JsonNode root = MAPPER.readTree(jsonObject(raw));
            template = parseTemplate(root);
            subtitles.putAll(parseSubtitles(root));
        } catch (RuntimeException | java.io.IOException e) {
            log.warn("Ebook {}: book template / subtitles unavailable ({}); no recurring sections, no subtitles",
                    ebookId, e.getMessage());
        }

        Map<Integer, String> tooLong = new LinkedHashMap<>();
        subtitles.forEach((n, s) -> {
            if (s.length() > SUBTITLE_CHARS) {
                tooLong.put(n, s);
            }
        });
        if (!tooLong.isEmpty()) {
            try {
                String raw = anthropicService.complete(
                        BookTemplatePrompts.system(ebook.getLanguage(), SUBTITLE_CHARS),
                        BookTemplatePrompts.shorten(tooLong, SUBTITLE_CHARS), MAX_TOKENS);
                parseSubtitles(MAPPER.readTree(jsonObject(raw))).forEach((n, s) -> {
                    if (tooLong.containsKey(n)) {
                        subtitles.put(n, s);
                    }
                });
            } catch (RuntimeException | java.io.IOException e) {
                log.warn("Ebook {}: could not shorten {} subtitle(s): {}", ebookId, tooLong.size(), e.getMessage());
            }
        }

        ebook.setChapterTemplateJson(template.toJson());
        ebookRepository.save(ebook);
        int kept = 0;
        for (EbookChapter c : chapters) {
            String subtitle = acceptable(subtitles.get(c.getChapterNumber()), c);
            c.setReaderSubtitle(subtitle);
            kept += subtitle == null ? 0 : 1;
        }
        chapterRepository.saveAll(chapters);
        log.info("Ebook {}: book template {} recurring section(s) {}; {} of {} chapter subtitles",
                ebookId, template.sections().size(),
                template.sections().stream().map(BookTemplate.Section::heading).toList(), kept, chapters.size());
    }

    /**
     * A subtitle the reader may see, or null: not blank, within
     * {@link DocumentComposer#MAX_STATEMENT_CHARS}, not cut off with an ellipsis,
     * not the chapter title again, and not a copy of the internal brief.
     */
    static String acceptable(String subtitle, EbookChapter chapter) {
        if (subtitle == null || subtitle.isBlank()) {
            return null;
        }
        String s = subtitle.strip().replaceAll("\\s+", " ");
        if (s.length() > DocumentComposer.MAX_STATEMENT_CHARS || s.endsWith("…") || s.endsWith("...")) {
            return null;
        }
        String norm = BookTemplate.normalise(s);
        if (norm.isEmpty() || norm.equals(BookTemplate.normalise(chapter.getTitle()))) {
            return null;
        }
        String brief = BookTemplate.normalise(chapter.getDescription());
        if (norm.length() >= 30 && brief.contains(norm)) {
            return null; // the brief, not reader copy
        }
        return s;
    }

    static BookTemplate parseTemplate(JsonNode root) {
        List<BookTemplate.Section> sections = new ArrayList<>();
        for (JsonNode n : root.path("recurringSections")) {
            sections.add(new BookTemplate.Section(n.path("heading").asText(null), n.path("purpose").asText("")));
        }
        return new BookTemplate(sections);
    }

    static Map<Integer, String> parseSubtitles(JsonNode root) {
        Map<Integer, String> out = new HashMap<>();
        for (JsonNode n : root.path("chapters")) {
            int number = n.path("number").asInt(-1);
            String subtitle = n.path("subtitle").asText("");
            if (number > 0 && !subtitle.isBlank()) {
                out.put(number, subtitle.strip());
            }
        }
        return out;
    }

    private static String jsonObject(String raw) {
        int start = raw == null ? -1 : raw.indexOf('{');
        int end = raw == null ? -1 : raw.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new IllegalStateException("model did not return a JSON object");
        }
        return raw.substring(start, end + 1);
    }
}
