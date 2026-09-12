package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.entity.ContentSource;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.entity.EbookImage;
import com.ebookwriter.SaaS.entity.EbookImagePlacement;
import com.ebookwriter.SaaS.entity.EbookImageRole;
import com.ebookwriter.SaaS.prompt.AssetPlacementPrompts;
import com.ebookwriter.SaaS.repository.EbookChapterRepository;
import com.ebookwriter.SaaS.repository.EbookImageRepository;
import com.ebookwriter.SaaS.repository.EbookRepository;
import com.ebookwriter.SaaS.service.ai.AnthropicService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Step 1.5 — decide how the user's uploaded assets are used. After planning and
 * before writing, the model is shown the outline and the asset list and returns,
 * per asset, whether it is the cover, belongs to a chapter, or should go unused,
 * plus a role, short description and tags.
 *
 * <p>Decisions are applied as the asset's <em>assignment</em>: the cover asset is
 * placed as the cover, and a chapter-assigned asset is tentatively placed in that
 * chapter so the chapter writer can offer it. Whether an inline asset actually
 * ends up in the book is reconciled from the written Markdown afterwards
 * ({@link AssetUsageService}) — the model may choose not to use an offered image.
 *
 * <p>Best-effort: any failure here is logged and generation continues without
 * asset placement rather than failing the whole book.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetPlacementService {

    private static final long PLACEMENT_MAX_TOKENS = 2000L;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AnthropicService anthropicService;
    private final EbookRepository ebookRepository;
    private final EbookChapterRepository chapterRepository;
    private final EbookImageRepository imageRepository;

    @Transactional
    public void plan(UUID ebookId) {
        List<EbookImage> assets = imageRepository.findByEbookIdOrderByCreatedAtAsc(ebookId);
        if (assets.isEmpty()) {
            return; // nothing uploaded — the book is text-only
        }

        Ebook ebook = ebookRepository.findById(ebookId)
                .orElseThrow(() -> new IllegalArgumentException("Ebook not found: " + ebookId));
        List<EbookChapter> chapters = chapterRepository.findByEbookIdOrderByChapterNumberAsc(ebookId);

        try {
            String system = AssetPlacementPrompts.system(ebook.getLanguage());
            String user = AssetPlacementPrompts.user(ebook, chapters, assets);
            String raw = anthropicService.complete(system, user, PLACEMENT_MAX_TOKENS);
            PlacementPlan plan = parse(raw);
            apply(plan, assets, chapters);
        } catch (RuntimeException e) {
            // Assets are a nice-to-have; never fail a generation because placement
            // planning hiccuped. The book is still produced (text + any cover the
            // user set manually), just without AI-decided asset placement.
            log.warn("Asset placement planning failed for ebook {}; continuing without it: {}",
                    ebookId, e.getMessage());
        }
    }

    private void apply(PlacementPlan plan, List<EbookImage> assets, List<EbookChapter> chapters) {
        if (plan == null || plan.placements() == null) {
            return;
        }
        Map<UUID, EbookImage> byId = new HashMap<>();
        for (EbookImage a : assets) {
            byId.put(a.getId(), a);
        }
        Map<Integer, EbookChapter> byNumber = new HashMap<>();
        for (EbookChapter c : chapters) {
            byNumber.put(c.getChapterNumber(), c);
        }

        boolean coverTaken = false;
        for (AssetPlacement p : plan.placements()) {
            EbookImage asset = resolveAsset(p, byId, assets);
            if (asset == null) {
                continue;
            }

            asset.setRole(parseRole(p.role()));
            asset.setAiDescription(trimToNull(p.description()));
            asset.setTags(joinTags(p.tags()));
            asset.setPlacedBy(ContentSource.AI);

            String decision = p.decision() == null ? "unused" : p.decision().trim().toLowerCase();
            if (decision.equals("cover") && !coverTaken) {
                asset.setPlacement(EbookImagePlacement.COVER);
                asset.setChapter(null);
                coverTaken = true;
            } else if (decision.equals("chapter") && p.chapterNumber() != null
                    && byNumber.containsKey(p.chapterNumber())) {
                asset.setPlacement(EbookImagePlacement.CHAPTER);
                asset.setChapter(byNumber.get(p.chapterNumber()));
            } else {
                asset.setPlacement(EbookImagePlacement.UNUSED);
                asset.setChapter(null);
            }
            imageRepository.save(asset);
        }
    }

    /** Match by id first, then leniently by filename, so a slightly-off id still lands. */
    private EbookImage resolveAsset(AssetPlacement p, Map<UUID, EbookImage> byId, List<EbookImage> assets) {
        if (p.id() != null) {
            try {
                EbookImage byUuid = byId.get(UUID.fromString(p.id().trim()));
                if (byUuid != null) {
                    return byUuid;
                }
            } catch (IllegalArgumentException ignored) {
                // not a UUID — fall through to filename matching
            }
            for (EbookImage a : assets) {
                if (p.id().equalsIgnoreCase(a.getOriginalFilename())) {
                    return a;
                }
            }
        }
        return null;
    }

    private PlacementPlan parse(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new RuntimeException("Model did not return a JSON object for asset placement");
        }
        try {
            return OBJECT_MAPPER.readValue(raw.substring(start, end + 1), PlacementPlan.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse asset placement JSON: " + e.getMessage(), e);
        }
    }

    private static EbookImageRole parseRole(String role) {
        if (role == null || role.isBlank()) {
            return EbookImageRole.GENERAL;
        }
        try {
            return EbookImageRole.valueOf(role.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return EbookImageRole.GENERAL;
        }
    }

    private static String joinTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        return String.join(",", tags.stream().map(String::trim).filter(s -> !s.isEmpty()).toList());
    }

    private static String trimToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    // ---- JSON shapes --------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PlacementPlan(List<AssetPlacement> placements) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AssetPlacement(String id, String decision, Integer chapterNumber,
                          String role, String description, List<String> tags) {
    }
}
