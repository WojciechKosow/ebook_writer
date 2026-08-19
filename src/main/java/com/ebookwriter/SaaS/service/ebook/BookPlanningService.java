package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.dto.plan.BookPlan;
import com.ebookwriter.SaaS.dto.plan.PlannedChapter;
import com.ebookwriter.SaaS.entity.ChapterStatus;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.prompt.PlanningPrompts;
import com.ebookwriter.SaaS.repository.EbookRepository;
import com.ebookwriter.SaaS.service.ai.AnthropicService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Step 1 — ask the model for a structured book plan and persist it as the
 * ebook's metadata plus a set of PENDING chapters.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookPlanningService {

    private static final long PLAN_MAX_TOKENS = 4000L;

    // Own Jackson 2 mapper: Spring Boot 4 registers a Jackson 3 ObjectMapper by
    // default, so we don't rely on an injected bean for this lenient parse.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AnthropicService anthropicService;
    private final EbookRepository ebookRepository;

    @Transactional
    public void plan(UUID ebookId) {

        Ebook ebook = ebookRepository.findById(ebookId)
                .orElseThrow(() -> new IllegalArgumentException("Ebook not found: " + ebookId));

        String system = PlanningPrompts.system(ebook.getLanguage());
        String user = PlanningPrompts.user(ebook);

        String raw = anthropicService.complete(system, user, PLAN_MAX_TOKENS);
        BookPlan plan = parsePlan(raw);

        ebook.setTitle(orDefault(plan.title(), ebook.getTopic()));
        ebook.setSubtitle(plan.subtitle());
        ebook.setDescription(plan.description());
        ebook.setWritingGuidelines(plan.writingGuidelines());
        ebook.setPlanJson(raw);

        ebook.getChapters().clear();
        int number = 1;
        for (PlannedChapter pc : plan.chapters()) {
            EbookChapter chapter = EbookChapter.builder()
                    .ebook(ebook)
                    .chapterNumber(number++)
                    .title(orDefault(pc.title(), "Chapter " + (number - 1)))
                    .description(pc.description())
                    .approxPages(Math.max(1, pc.approxPages()))
                    .status(ChapterStatus.PENDING)
                    .build();
            ebook.getChapters().add(chapter);
        }

        ebookRepository.save(ebook);
        log.info("Planned ebook {} — '{}' with {} chapters",
                ebookId, ebook.getTitle(), ebook.getChapters().size());
    }

    /** Extract the JSON object from the model output and parse it leniently. */
    private BookPlan parsePlan(String raw) {
        String json = extractJsonObject(raw);
        try {
            BookPlan plan = OBJECT_MAPPER.readValue(json, BookPlan.class);
            if (plan.chapters() == null || plan.chapters().isEmpty()) {
                throw new IllegalStateException("Plan contained no chapters");
            }
            return plan;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse book plan JSON: " + e.getMessage(), e);
        }
    }

    private String extractJsonObject(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new RuntimeException("Model did not return a JSON object for the plan");
        }
        return raw.substring(start, end + 1);
    }

    private String orDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }
}
