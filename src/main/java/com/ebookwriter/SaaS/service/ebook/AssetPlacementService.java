package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.config.properties.AnthropicProperties;
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
import com.ebookwriter.SaaS.service.ai.ModelChoice;
import com.ebookwriter.SaaS.service.ai.VisionImage;
import com.ebookwriter.SaaS.service.storage.R2StorageService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Step 1.5 — decide how the user's uploaded assets are used. After planning and
 * before writing, the premium model is shown the outline, the asset list with
 * the author's note on each image, and the images themselves (downscaled, see
 * {@link VisionImage}). Per asset it returns whether it is the cover, belongs to a
 * chapter (and which part of it), or should go unused, plus what the image
 * actually shows ({@code aiAnalysis}) — so the writer places it and refers to it
 * from its content, not its file name. An image the author placed by hand keeps
 * its place.
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

    private static final long PLACEMENT_MAX_TOKENS = 8000L;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** Images looked at per request (each ≈ 1k input tokens at {@link VisionImage#MAX_EDGE}). */
    static final int IMAGES_PER_REQUEST = 12;

    private final AnthropicService anthropicService;
    private final EbookRepository ebookRepository;
    private final EbookChapterRepository chapterRepository;
    private final EbookImageRepository imageRepository;
    /** Where the images' bytes live; null or unconfigured = place them by their notes and names only. */
    private final R2StorageService storage;
    /** The premium model looks at the images (part of preparing the book, for both writing qualities). */
    private final AnthropicProperties anthropicProperties;

    @Transactional
    public void plan(UUID ebookId) {
        List<EbookImage> assets = imageRepository.findByEbookIdOrderByCreatedAtAsc(ebookId).stream()
                .filter(a -> a.getPlacedBy() != ContentSource.AI || a.getRole() != EbookImageRole.ILLUSTRATION)
                .toList();
        if (assets.isEmpty()) {
            return; // nothing uploaded — the book is text-only
        }
        Ebook ebook = ebookRepository.findById(ebookId)
                .orElseThrow(() -> new IllegalArgumentException("Ebook not found: " + ebookId));
        List<EbookChapter> chapters = chapterRepository.findByEbookIdOrderByChapterNumberAsc(ebookId);
        String system = AssetPlacementPrompts.system(ebook.getLanguage());
        // A cover the author chose stays the cover.
        boolean coverTaken = assets.stream().anyMatch(a -> a.getPlacedBy() == ContentSource.USER
                && a.getPlacement() == EbookImagePlacement.COVER);
        int seen = 0;
        for (int from = 0; from < assets.size(); from += IMAGES_PER_REQUEST) {
            List<EbookImage> batch = assets.subList(from, Math.min(assets.size(), from + IMAGES_PER_REQUEST));
            try {
                List<VisionImage> images = look(batch);
                seen += images.size();
                Set<UUID> attached = new HashSet<>();
                images.forEach(i -> attached.add(UUID.fromString(i.label())));
                String user = AssetPlacementPrompts.user(ebook, chapters, batch, attached);
                ModelChoice model = anthropicProperties == null ? null : ModelChoice.of(anthropicProperties.getModel());
                String raw = images.isEmpty() || model == null
                        ? anthropicService.complete(system, user + "\n" + AssetPlacementPrompts.OUTRO, PLACEMENT_MAX_TOKENS)
                        : anthropicService.completeWithImages(system, user, images, AssetPlacementPrompts.OUTRO,
                                PLACEMENT_MAX_TOKENS, model);
                coverTaken = apply(parse(raw), batch, chapters, coverTaken);
            } catch (RuntimeException e) {
                // Assets are a nice-to-have; never fail a generation because placement
                // planning hiccuped. The book is still produced (text + any cover the
                // user set manually), just without AI-decided placement for this batch.
                log.warn("Asset placement planning failed for ebook {} (images {}–{}); continuing without it: {}",
                        ebookId, from + 1, from + batch.size(), e.getMessage());
            }
        }
        log.info("Ebook {}: placed {} uploaded image(s); the model looked at {} of them", ebookId, assets.size(), seen);
    }

    /** The batch's images, downloaded and prepared for the model; those that can't be shown are left out. */
    private List<VisionImage> look(List<EbookImage> batch) {
        List<VisionImage> out = new ArrayList<>();
        if (storage == null || !storage.isConfigured()) {
            return out;
        }
        for (EbookImage a : batch) {
            try {
                storage.download(a.getStorageKey())
                        .flatMap(bytes -> VisionImage.prepare(a.getId().toString(), bytes, a.getContentType()))
                        .ifPresent(out::add);
            } catch (RuntimeException e) {
                log.warn("Could not load image {} for analysis: {}", a.getId(), e.getMessage());
            }
        }
        return out;
    }

    /**
     * Apply one batch's decisions. An image the author placed by hand keeps its
     * place (only the AI's description and analysis are added). Returns whether
     * the cover is now taken.
     */
    boolean apply(PlacementPlan plan, List<EbookImage> assets, List<EbookChapter> chapters, boolean coverTaken) {
        if (plan == null || plan.placements() == null) {
            return coverTaken;
        }
        Map<UUID, EbookImage> byId = new HashMap<>();
        for (EbookImage a : assets) {
            byId.put(a.getId(), a);
        }
        Map<Integer, EbookChapter> byNumber = new HashMap<>();
        for (EbookChapter c : chapters) {
            byNumber.put(c.getChapterNumber(), c);
        }
        for (AssetPlacement p : plan.placements()) {
            EbookImage asset = resolveAsset(p, byId, assets);
            if (asset == null) {
                continue;
            }
            asset.setAiDescription(trimToNull(p.description()));
            asset.setAiAnalysis(analysis(p));
            asset.setTags(joinTags(p.tags()));
            boolean placedByAuthor = asset.getPlacedBy() == ContentSource.USER
                    && asset.getPlacement() != EbookImagePlacement.UNUSED;
            if (placedByAuthor) {
                imageRepository.save(asset);
                continue;
            }
            asset.setRole(parseRole(p.role()));
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
        return coverTaken;
    }

    /** The stored analysis: what the model saw, and the part of the chapter it illustrates. */
    static String analysis(AssetPlacement p) {
        String a = trimToNull(p.analysis());
        String section = trimToNull(p.section());
        if (a == null && section == null) {
            return null;
        }
        return (a == null ? "" : a) + (section == null ? "" : (a == null ? "" : "\n") + "Best placed with: " + section);
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

    static PlacementPlan parse(String raw) {
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
    record AssetPlacement(String id, String decision, Integer chapterNumber, String section,
                          String role, String description, String analysis, List<String> tags) {
    }
}
