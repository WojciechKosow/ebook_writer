package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.dto.cover.CoverLayout;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.entity.EbookImage;
import com.ebookwriter.SaaS.entity.EbookImagePlacement;
import com.ebookwriter.SaaS.repository.EbookChapterRepository;
import com.ebookwriter.SaaS.repository.EbookImageRepository;
import com.ebookwriter.SaaS.repository.EbookRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The final quality gate: a book is validated <b>after</b> it is rendered and
 * <b>before</b> it is published as COMPLETED, so an obviously broken book can
 * never silently become a finished, downloadable product.
 *
 * <p>Two severities:
 * <ul>
 *   <li><b>FATAL</b> — a genuinely broken book (no readable pages, no rendered
 *       content). {@link #validateOrThrow} raises {@link EbookValidationException};
 *       the pipeline then fails the book and refunds the hold, like any other
 *       generation failure. Kept deliberately narrow so a good book is never
 *       failed on a cosmetic issue.</li>
 *   <li><b>WARNING</b> — a quality concern worth recording (a missing cover
 *       visual, a cover asset whose shape doesn't match its layout region, a
 *       placed image with no stored bytes). Logged, never fatal.</li>
 * </ul>
 *
 * <p>The decision logic ({@link #inspect}) is a pure function of the book's
 * rendered state, so it is fully unit-testable without a database or a renderer.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EbookValidationService {

    /**
     * How far a cover asset's real aspect ratio may drift from its layout's image
     * region before it is flagged (the two are matched by design, so a large drift
     * means a stale/wrong asset that will crop heavily). 18% tolerance absorbs the
     * image model's ±few-pixel rounding without false positives.
     */
    static final double COVER_RATIO_TOLERANCE = 0.18;

    private final EbookRepository ebookRepository;
    private final EbookChapterRepository chapterRepository;
    private final EbookImageRepository imageRepository;

    public enum Severity { FATAL, WARNING }

    /** A single validation finding. */
    public record Issue(Severity severity, String message) {
    }

    /** The outcome of validating a book: its findings, split by severity. */
    public record Report(List<Issue> issues) {
        public boolean hasFatal() {
            return issues.stream().anyMatch(i -> i.severity() == Severity.FATAL);
        }

        public List<Issue> fatal() {
            return issues.stream().filter(i -> i.severity() == Severity.FATAL).toList();
        }

        public List<Issue> warnings() {
            return issues.stream().filter(i -> i.severity() == Severity.WARNING).toList();
        }

        /** A one-line summary of the fatal issues, for the failure message. */
        String fatalSummary() {
            return String.join("; ", fatal().stream().map(Issue::message).toList());
        }
    }

    /**
     * Validate a rendered book and raise {@link EbookValidationException} if any
     * fatal issue is found; warnings are logged and the book proceeds. Loads the
     * book's current state and delegates to the pure {@link #inspect}.
     *
     * @param ebookId    the rendered book
     * @param pageCount  the true page count read back from the rendered PDF
     */
    @Transactional(readOnly = true)
    public Report validateOrThrow(UUID ebookId, int pageCount) {
        Ebook ebook = ebookRepository.findById(ebookId)
                .orElseThrow(() -> new IllegalArgumentException("Ebook not found: " + ebookId));
        List<EbookChapter> chapters = chapterRepository.findByEbookIdOrderByChapterNumberAsc(ebookId);
        List<EbookImage> images = imageRepository.findByEbookIdOrderByCreatedAtAsc(ebookId);

        Report report = inspect(ebook, chapters, images, pageCount);

        for (Issue w : report.warnings()) {
            log.warn("Ebook {} validation warning: {}", ebookId, w.message());
        }
        if (report.hasFatal()) {
            throw new EbookValidationException(
                    "Book failed final validation: " + report.fatalSummary());
        }
        log.info("Ebook {} passed final validation ({} page(s), {} warning(s))",
                ebookId, pageCount, report.warnings().size());
        return report;
    }

    /**
     * Inspect a rendered book's state and return its findings. Pure and static: no
     * I/O, so the exact rules are unit-testable. The checks mirror the product's
     * "broken books cannot become READY" contract without being so strict that a
     * legitimately-shaped book is ever failed.
     */
    static Report inspect(Ebook ebook, List<EbookChapter> chapters,
                          List<EbookImage> images, int pageCount) {
        List<Issue> issues = new ArrayList<>();

        // --- Document integrity (FATAL) ---
        if (pageCount <= 0) {
            issues.add(new Issue(Severity.FATAL, "rendered PDF has no pages"));
        }

        long chaptersWithContent = chapters == null ? 0 : chapters.stream()
                .filter(c -> c.getContent() != null && !c.getContent().isBlank())
                .count();
        if (chaptersWithContent == 0) {
            issues.add(new Issue(Severity.FATAL, "book has no rendered chapter content"));
        }

        // --- Cover (WARNING) ---
        EbookImage cover = images == null ? null : images.stream()
                .filter(i -> i.getPlacement() == EbookImagePlacement.COVER)
                .findFirst().orElse(null);
        CoverLayout layout = ebook == null ? null : ebook.getCoverLayout();

        // A visual layout with no cover asset still renders (the builder downgrades
        // it to a typographic cover), but the intended visual is missing.
        if (layout != null && layout.requiresVisual() && cover == null) {
            issues.add(new Issue(Severity.WARNING,
                    "cover layout " + layout + " expects a visual but no cover image is placed"));
        }

        // A cover asset should match its layout's image-region ratio (they are
        // matched by design). A large drift means a stale/wrong asset that would
        // crop heavily or look distorted.
        if (cover != null && layout != null && layout.imageAspectRatio() != null
                && cover.getWidth() > 0 && cover.getHeight() > 0) {
            double actual = (double) cover.getWidth() / cover.getHeight();
            double expected = layout.imageAspectRatio().ratio();
            double drift = Math.abs(actual - expected) / expected;
            if (drift > COVER_RATIO_TOLERANCE) {
                issues.add(new Issue(Severity.WARNING, String.format(
                        "cover image ratio %.2f does not match layout %s region ratio %.2f "
                                + "(may crop heavily)", actual, layout, expected)));
            }
        }

        // --- Placed images have stored bytes (WARNING) ---
        if (images != null) {
            for (EbookImage img : images) {
                if (img.getPlacement() != EbookImagePlacement.UNUSED
                        && (img.getStorageKey() == null || img.getStorageKey().isBlank())) {
                    issues.add(new Issue(Severity.WARNING,
                            "placed image " + img.getId() + " has no storage key"));
                }
            }
        }

        return new Report(List.copyOf(issues));
    }
}
