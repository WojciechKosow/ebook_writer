package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.config.properties.CreditProperties;
import com.ebookwriter.SaaS.config.properties.OpenAiProperties;
import com.ebookwriter.SaaS.prompt.ScopePrompts;
import com.ebookwriter.SaaS.repository.EbookRepository;
import com.ebookwriter.SaaS.service.ai.OpenAiTextClient;
import com.ebookwriter.SaaS.service.ai.OpenAiTextException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ebookwriter.SaaS.dto.blueprint.BlueprintData;
import com.ebookwriter.SaaS.dto.knowledge.BookKnowledgeData;
import com.ebookwriter.SaaS.entity.BookDepth;
import com.ebookwriter.SaaS.entity.BookKnowledge;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.KnowledgeSource;
import com.ebookwriter.SaaS.entity.KnowledgeSourceStatus;
import com.ebookwriter.SaaS.repository.BookKnowledgeRepository;
import com.ebookwriter.SaaS.repository.KnowledgeSourceRepository;
import com.ebookwriter.SaaS.service.blueprint.BookBlueprintService;
import com.ebookwriter.SaaS.service.knowledge.KnowledgeAssembler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Collects what Scrivetta knows about a book — depth, brief, pasted source text,
 * uploaded materials, extracted knowledge and the chapter blueprint — and turns
 * it into a {@link ScopeEstimate} with {@link ScopeEstimator}. The estimate gets
 * sharper as the author moves through the flow (brief → materials → blueprint).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScopeEstimationService {

    private final KnowledgeSourceRepository sourceRepository;
    private final BookKnowledgeRepository knowledgeRepository;
    private final BookBlueprintService blueprintService;
    private final CreditProperties creditProperties;
    private final OpenAiTextClient openAi;
    private final OpenAiProperties openAiProperties;
    private final EbookRepository ebookRepository;

    // Own Jackson 2 mapper (Spring Boot 4 registers Jackson 3 by default).
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Estimate a stored book at its own depth. */
    @Transactional(readOnly = true)
    public ScopeEstimate estimate(Ebook ebook) {
        return estimate(ebook, ebook.effectiveDepth());
    }

    /** Estimate a stored book as if it had {@code depth} (for comparing the options). */
    @Transactional(readOnly = true)
    public ScopeEstimate estimate(Ebook ebook, BookDepth depth) {
        ScopeEstimator.Signals s = signals(ebook, depth);
        return refine(ScopeEstimator.estimate(s, maxPages()), currentAssessment(ebook, s));
    }

    /** Estimates for every depth, so the UI can show what each option means for this book. */
    @Transactional(readOnly = true)
    public List<ScopeEstimate> estimateAllDepths(Ebook ebook) {
        ScopeEstimator.Signals base = signals(ebook, ebook.effectiveDepth());
        Optional<AiScopeAssessment> ai = currentAssessment(ebook, base);
        return java.util.Arrays.stream(BookDepth.values())
                .map(d -> refine(ScopeEstimator.estimate(withDepth(base, d), maxPages()), ai))
                .toList();
    }

    private ScopeEstimate refine(ScopeEstimate heuristic, Optional<AiScopeAssessment> ai) {
        return ai.map(a -> ScopeEstimator.combine(heuristic, a.rangeFor(heuristic.depth()), maxPages()))
                .orElse(heuristic);
    }

    // ---- AI scope assessment ------------------------------------------------

    /** Where the AI assessment stands for a book's current inputs. */
    public enum AssessmentStatus {
        /** OpenAI is not configured; estimates are material-based only. */
        UNAVAILABLE,
        /** No assessment for the current brief/materials/blueprint yet. */
        NEEDED,
        /** The cached assessment matches the current inputs. */
        READY
    }

    public AssessmentStatus assessmentStatus(Ebook ebook) {
        if (!openAi.isConfigured()) {
            return AssessmentStatus.UNAVAILABLE;
        }
        return currentAssessment(ebook, signals(ebook, ebook.effectiveDepth())).isPresent()
                ? AssessmentStatus.READY : AssessmentStatus.NEEDED;
    }

    /** The cached assessment's rationale, when it is current. */
    public Optional<String> assessmentRationale(Ebook ebook) {
        return currentAssessment(ebook, signals(ebook, ebook.effectiveDepth()))
                .map(AiScopeAssessment::rationale).filter(r -> r != null && !r.isBlank());
    }

    /**
     * Ask OpenAI how long this book needs to be at each depth, and cache the
     * answer on the ebook (keyed by its inputs, so it is reused until the brief,
     * materials or blueprint change). Best-effort: when OpenAI is unavailable or
     * answers nonsense, the material-based estimate stands. Never throws.
     *
     * @return true when a current assessment is now cached
     */
    public boolean assess(Ebook ebook) {
        if (!openAi.isConfigured() || ebook.getId() == null) {
            return false;
        }
        ScopeEstimator.Signals s = signals(ebook, ebook.effectiveDepth());
        if (currentAssessment(ebook, s).isPresent()) {
            return true;
        }
        try {
            OpenAiTextClient.JsonCompletion completion = openAi.completeJson(ScopePrompts.system(),
                    ScopePrompts.user(briefText(ebook), materialsText(ebook, s)),
                    OpenAiTextClient.CallOptions.scope(openAiProperties));
            AiScopeAssessment assessment = parseAssessment(completion.json());
            if (assessment == null) {
                log.warn("Scope assessment for ebook {} was unusable; keeping the material-based estimate", ebook.getId());
                return false;
            }
            ebook.setScopeAssessmentJson(MAPPER.writeValueAsString(assessment));
            ebook.setScopeAssessmentKey(fingerprint(ebook, s));
            ebookRepository.save(ebook);
            log.info("Scope assessed for ebook {}: {} ({}; {} in / {} out tokens)", ebook.getId(),
                    assessment.ranges(), assessment.contentAmount(), completion.inputTokens(), completion.outputTokens());
            return true;
        } catch (OpenAiTextException | JsonProcessingException e) {
            log.warn("Scope assessment failed for ebook {}: {}", ebook.getId(), e.getMessage());
            return false;
        } catch (RuntimeException e) {
            log.warn("Scope assessment failed unexpectedly for ebook {}", ebook.getId(), e);
            return false;
        }
    }

    private Optional<AiScopeAssessment> currentAssessment(Ebook ebook, ScopeEstimator.Signals s) {
        if (ebook.getScopeAssessmentJson() == null || ebook.getScopeAssessmentKey() == null
                || !ebook.getScopeAssessmentKey().equals(fingerprint(ebook, s))) {
            return Optional.empty();
        }
        try {
            return Optional.of(MAPPER.readValue(ebook.getScopeAssessmentJson(), AiScopeAssessment.class));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    /** Parse and sanity-check OpenAI's answer; null when it is not usable. */
    static AiScopeAssessment parseAssessment(JsonNode json) {
        if (json == null || !json.isObject()) {
            return null;
        }
        java.util.Map<BookDepth, AiScopeAssessment.Range> ranges = new java.util.EnumMap<>(BookDepth.class);
        for (BookDepth d : BookDepth.values()) {
            JsonNode r = json.path(d.name().toLowerCase(java.util.Locale.ROOT));
            int low = r.path("pagesLow").asInt(0);
            int high = r.path("pagesHigh").asInt(0);
            if (low <= 0 || high < low) {
                return null;
            }
            ranges.put(d, new AiScopeAssessment.Range(low, high));
        }
        String amount = json.path("contentAmount").asText(null);
        String rationale = json.path("rationale").asText(null);
        if (rationale != null && rationale.length() > 500) {
            rationale = rationale.substring(0, 500);
        }
        return new AiScopeAssessment(ranges, amount, rationale);
    }

    /** What the assessment depends on (not the depth: one assessment covers all three). */
    String fingerprint(Ebook ebook, ScopeEstimator.Signals s) {
        String basis = String.join("|", nz(ebook.getTopic()), nz(ebook.getBookGoal()), nz(ebook.getTargetAudience()),
                nz(ebook.getAdditionalInstructions()), nz(ebook.getLanguage()), String.valueOf(s.sourceChars()),
                String.valueOf(s.knowledgeUnits()), String.valueOf(s.structureUnits()), String.valueOf(s.knowledgeFlow()),
                s.blueprintChapters().toString());
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(basis.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String briefText(Ebook e) {
        return "Topic: " + nz(e.getTopic())
                + "\nBook goal: " + nz(e.getBookGoal())
                + "\nAudience: " + nz(e.getTargetAudience())
                + "\nStyle: " + nz(e.getStyle())
                + "\nLanguage: " + nz(e.getLanguage())
                + "\nAdditional instructions: " + nz(e.getAdditionalInstructions());
    }

    /** A compact picture of the materials: sizes, what the knowledge contains, the chapter structure. */
    private String materialsText(Ebook e, ScopeEstimator.Signals s) {
        StringBuilder sb = new StringBuilder();
        if (s.sourceChars() > 0) {
            sb.append("Source material: about ").append(Math.round(s.sourceChars() / ScopeEstimator.SOURCE_CHARS_PER_PAGE))
                    .append(" pages of text/code (").append(s.sourceChars()).append(" characters).\n");
        }
        if (s.structureUnits() > 0) {
            sb.append("The brief promises a structure of ").append(s.structureUnits()).append(" units.\n");
        }
        knowledgeRepository.findByEbookId(e.getId())
                .filter(k -> k.getStatus().hasKnowledge() && k.getKnowledgeJson() != null)
                .map(k -> KnowledgeAssembler.read(k.getKnowledgeJson()))
                .ifPresent(k -> {
                    sb.append("Written from the author's own materials (the writer never invents what is missing).\n");
                    if (k.overallSummary() != null) sb.append("Summary: ").append(k.overallSummary()).append('\n');
                    sb.append("Extracted knowledge: ").append(k.topics().size()).append(" topics, ")
                            .append(k.processes().size()).append(" processes, ").append(k.examples().size())
                            .append(" examples, ").append(k.technicalDetails().size()).append(" technical details, ")
                            .append(k.userInsights().size()).append(" author insights, ").append(k.facts().size())
                            .append(" facts.\n");
                    sb.append("Topics: ").append(String.join("; ",
                            k.topics().stream().map(BookKnowledgeData.Topic::name).limit(60).toList())).append('\n');
                });
        blueprintService.getBlueprint(e.getId()).ifPresent(bp -> {
            sb.append("Chapter structure (").append(bp.chapters().size()).append(" chapters):\n");
            bp.chapters().stream().limit(40).forEach(c -> sb.append("- ").append(c.title()).append(" — ")
                    .append(c.topics().size()).append(" topics, ").append(c.keyPoints().size())
                    .append(" key points from the author\n"));
        });
        if (e.getSourceMaterial() != null && !e.getSourceMaterial().isBlank()) {
            String src = e.getSourceMaterial().strip();
            sb.append("Excerpt of the pasted source text:\n").append(src, 0, Math.min(src.length(), 3000)).append('\n');
        }
        String text = sb.toString();
        return text.length() > 14_000 ? text.substring(0, 14_000) : text;
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "(none)" : s.strip();
    }


    /** A brief-only estimate (no book yet) — the creation form's preview. */
    public ScopeEstimate estimateBrief(BookDepth depth, int briefChars, long sourceChars) {
        return ScopeEstimator.estimate(ScopeEstimator.Signals.brief(depth, briefChars, 0, sourceChars), maxPages());
    }

    /** The per-book safety maximum (pages); estimates and plans never exceed it. */
    public int maxPages() {
        return Math.max(EbookHtmlBuilder.FRONT_MATTER_PAGES + 1, creditProperties.getMaxGenerationBudget());
    }

    ScopeEstimator.Signals signals(Ebook ebook, BookDepth depth) {
        int structureUnits = StructureRequirement.detect(ebook).map(StructureRequirement::count).orElse(0);
        int briefChars = len(ebook.getTopic()) + len(ebook.getBookGoal()) + len(ebook.getAdditionalInstructions());
        long pasted = len(ebook.getSourceMaterial());
        if (ebook.getId() == null) {
            return ScopeEstimator.Signals.brief(depth, briefChars, structureUnits, pasted);
        }

        List<KnowledgeSource> sources = sourceRepository.findByEbookIdOrderByCreatedAtAsc(ebook.getId());
        Optional<BlueprintData> blueprint = blueprintService.getBlueprint(ebook.getId());
        boolean knowledgeFlow = !sources.isEmpty() || blueprint.isPresent();
        if (!knowledgeFlow) {
            return ScopeEstimator.Signals.brief(depth, briefChars, structureUnits, pasted);
        }

        Optional<BookKnowledge> knowledgeRow = knowledgeRepository.findByEbookId(ebook.getId())
                .filter(k -> k.getStatus().hasKnowledge() && k.getKnowledgeJson() != null);
        long extracted = sources.stream()
                .filter(s -> s.getStatus() != KnowledgeSourceStatus.FAILED)
                .mapToLong(KnowledgeSource::getExtractedChars).sum();
        // Prefer what was actually analysed; fall back to what was extracted.
        long sourceChars = knowledgeRow.map(BookKnowledge::getAnalyzedChars).filter(c -> c > 0).orElse(extracted) + pasted;
        int knowledgeUnits = knowledgeRow.map(k -> knowledgeUnits(KnowledgeAssembler.read(k.getKnowledgeJson()))).orElse(0);
        List<ScopeEstimator.ChapterSignal> chapters = blueprint
                .map(bp -> bp.chapters().stream().map(ScopeEstimationService::chapterSignal).toList())
                .orElse(List.of());

        return new ScopeEstimator.Signals(depth, true, briefChars, structureUnits, sourceChars, knowledgeUnits, chapters);
    }

    /** How much a blueprint chapter carries, for sizing. */
    public static ScopeEstimator.ChapterSignal chapterSignal(BlueprintData.Chapter c) {
        return new ScopeEstimator.ChapterSignal(c.topics().size(), c.keyPoints().size(),
                c.knowledgeReferences().size(), c.sourceReferences().size());
    }

    /**
     * Distinct, substantive things the materials teach — each needs room in the
     * book. Terminology and the author's sequence are structure, not content, and
     * isolated facts are small, so they count less.
     */
    static int knowledgeUnits(BookKnowledgeData k) {
        if (k == null) {
            return 0;
        }
        double units = k.topics().size() + k.processes().size() + k.examples().size()
                + k.technicalDetails().size() + k.userInsights().size() + k.importantDetails().size()
                + k.facts().size() * 0.5;
        return (int) Math.round(units);
    }

    private static ScopeEstimator.Signals withDepth(ScopeEstimator.Signals s, BookDepth depth) {
        return new ScopeEstimator.Signals(depth, s.knowledgeFlow(), s.briefChars(), s.structureUnits(),
                s.sourceChars(), s.knowledgeUnits(), s.blueprintChapters());
    }

    private static int len(String s) {
        return s == null ? 0 : s.strip().length();
    }
}
