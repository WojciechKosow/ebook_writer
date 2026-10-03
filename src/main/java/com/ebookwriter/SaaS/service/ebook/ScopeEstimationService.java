package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.config.properties.CreditProperties;
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

    /** Estimate a stored book at its own depth. */
    @Transactional(readOnly = true)
    public ScopeEstimate estimate(Ebook ebook) {
        return estimate(ebook, ebook.effectiveDepth());
    }

    /** Estimate a stored book as if it had {@code depth} (for comparing the options). */
    @Transactional(readOnly = true)
    public ScopeEstimate estimate(Ebook ebook, BookDepth depth) {
        return ScopeEstimator.estimate(signals(ebook, depth), maxPages());
    }

    /** Estimates for every depth, so the UI can show what each option means for this book. */
    @Transactional(readOnly = true)
    public List<ScopeEstimate> estimateAllDepths(Ebook ebook) {
        ScopeEstimator.Signals base = signals(ebook, ebook.effectiveDepth());
        return java.util.Arrays.stream(BookDepth.values())
                .map(d -> ScopeEstimator.estimate(withDepth(base, d), maxPages()))
                .toList();
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
