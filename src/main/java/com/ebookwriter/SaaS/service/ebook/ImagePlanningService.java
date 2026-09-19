package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.config.properties.OpenAiProperties;
import com.ebookwriter.SaaS.dto.image.AspectRatio;
import com.ebookwriter.SaaS.dto.image.ImagePlan;
import com.ebookwriter.SaaS.dto.image.ImageType;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.prompt.ImagePlanningPrompts;
import com.ebookwriter.SaaS.repository.EbookChapterRepository;
import com.ebookwriter.SaaS.repository.EbookRepository;
import com.ebookwriter.SaaS.service.ai.AnthropicService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The Image Planner. After the manuscript is written and edited, it shows the
 * book (title, topic, audience, style, language, chapters and content excerpts)
 * to the content model and asks, as strict JSON, which AI images should exist,
 * where, and how to generate them.
 *
 * <p>Its output is a validated {@code List<ImagePlan>}: never free-form text.
 * The model's JSON is parsed leniently and every entry is validated (a real
 * chapter, a non-blank prompt) before use; malformed entries are dropped and a
 * malformed response yields an empty plan rather than crashing the book. The
 * planner is a pure decision-maker — it does not generate or store images.
 *
 * <p>Best-effort by design: any failure here returns an empty plan so the book
 * is still produced (text-only), mirroring {@link AssetPlacementService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImagePlanningService {

    private static final long PLAN_MAX_TOKENS = 3000L;

    // Own Jackson 2 mapper (Spring Boot 4 registers a Jackson 3 bean by default),
    // matching the lenient parse in BookPlanningService/AssetPlacementService.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AnthropicService anthropicService;
    private final EbookRepository ebookRepository;
    private final EbookChapterRepository chapterRepository;
    private final OpenAiProperties openAiProperties;

    /**
     * Produce the validated image plan for a book. Returns an empty list when the
     * book has no written content, when the pipeline is disabled/unconfigured, or
     * when planning fails for any reason — the caller treats "no plan" as "no
     * images", never as an error.
     */
    @Transactional(readOnly = true)
    public List<ImagePlan> plan(UUID ebookId) {
        if (!openAiProperties.isEnabled()) {
            return List.of();
        }

        Ebook ebook = ebookRepository.findById(ebookId)
                .orElseThrow(() -> new IllegalArgumentException("Ebook not found: " + ebookId));
        List<EbookChapter> chapters = chapterRepository.findByEbookIdOrderByChapterNumberAsc(ebookId);

        boolean hasContent = chapters.stream()
                .anyMatch(c -> c.getContent() != null && !c.getContent().isBlank());
        if (!hasContent) {
            return List.of();
        }

        int maxPerBook = Math.max(0, openAiProperties.getMaxImagesPerBook());
        int maxPerChapter = Math.max(1, openAiProperties.getMaxImagesPerChapter());
        if (maxPerBook == 0) {
            return List.of();
        }

        try {
            String system = ImagePlanningPrompts.system(ebook.getLanguage(), maxPerBook, maxPerChapter);
            String user = ImagePlanningPrompts.user(ebook, chapters);
            String raw = anthropicService.complete(system, user, PLAN_MAX_TOKENS);

            RawPlan parsed = parse(raw);
            Set<Integer> chapterNumbers = chapters.stream()
                    .filter(c -> c.getContent() != null && !c.getContent().isBlank())
                    .map(EbookChapter::getChapterNumber)
                    .collect(Collectors.toSet());

            List<ImagePlan> plans = toValidatedPlans(parsed, chapterNumbers, maxPerBook, maxPerChapter);
            log.info("Image plan for ebook {}: {} image(s) across {} chapter(s)",
                    ebookId, plans.size(),
                    plans.stream().map(ImagePlan::chapterNumber).distinct().count());
            return plans;
        } catch (RuntimeException e) {
            log.warn("Image planning failed for ebook {}; continuing without generated images: {}",
                    ebookId, e.getMessage());
            return List.of();
        }
    }

    /**
     * Validate and cap the model's raw plan. Entries without a usable prompt or a
     * real chapter are dropped; type/aspect ratio are coerced leniently; the
     * survivors are ranked by priority and truncated to the per-chapter and
     * per-book limits. Stable and side-effect free, so it is unit-testable
     * without the model. Returns plans with sequential ids ({@code img_1}, …).
     */
    static List<ImagePlan> toValidatedPlans(RawPlan raw, Set<Integer> validChapterNumbers,
                                            int maxPerBook, int maxPerChapter) {
        if (raw == null || raw.images() == null || validChapterNumbers.isEmpty() || maxPerBook <= 0) {
            return List.of();
        }

        // 1. Keep only well-formed entries anchored to a real chapter.
        List<ImagePlan> candidates = new ArrayList<>();
        for (RawImage r : raw.images()) {
            if (r == null || r.chapterNumber() == null
                    || !validChapterNumbers.contains(r.chapterNumber())) {
                continue;
            }
            String prompt = trimToNull(r.generationPrompt());
            if (prompt == null) {
                continue; // no prompt -> nothing to generate
            }
            candidates.add(new ImagePlan(
                    null, // id assigned after capping
                    r.chapterNumber(),
                    trimToNull(r.anchorHeading()),
                    ImageType.fromString(r.type()),
                    trimToNull(r.purpose()),
                    trimToNull(r.description()),
                    prompt,
                    AspectRatio.fromString(r.aspectRatio()),
                    normalisePriority(r.priority())));
        }

        // 2. Best images first (priority 1 = most important), stable for ties.
        candidates.sort((a, b) -> Integer.compare(a.priority(), b.priority()));

        // 3. Enforce per-chapter and per-book caps.
        List<ImagePlan> kept = new ArrayList<>();
        Map<Integer, Integer> perChapter = new HashMap<>();
        int idSeq = 1;
        for (ImagePlan p : candidates) {
            if (kept.size() >= maxPerBook) {
                break;
            }
            int used = perChapter.getOrDefault(p.chapterNumber(), 0);
            if (used >= maxPerChapter) {
                continue;
            }
            perChapter.put(p.chapterNumber(), used + 1);
            kept.add(new ImagePlan("img_" + idSeq++, p.chapterNumber(), p.anchorHeading(),
                    p.type(), p.purpose(), p.description(), p.generationPrompt(),
                    p.aspectRatio(), p.priority()));
        }
        return kept;
    }

    private RawPlan parse(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new RuntimeException("Model did not return a JSON object for the image plan");
        }
        try {
            return OBJECT_MAPPER.readValue(raw.substring(start, end + 1), RawPlan.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse image plan JSON: " + e.getMessage(), e);
        }
    }

    private static int normalisePriority(Integer priority) {
        // Missing or non-positive priorities sink to the back so explicitly ranked
        // images win the caps.
        return (priority == null || priority < 1) ? Integer.MAX_VALUE : priority;
    }

    private static String trimToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    // ---- JSON shapes --------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RawPlan(List<RawImage> images) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RawImage(Integer chapterNumber, String anchorHeading, String type, String purpose,
                    String description, String generationPrompt, String aspectRatio,
                    Integer priority) {
    }
}
