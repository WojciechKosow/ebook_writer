package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.dto.cover.CoverLayout;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;
import com.ebookwriter.SaaS.entity.EbookImage;
import com.ebookwriter.SaaS.entity.EbookImagePlacement;
import com.ebookwriter.SaaS.entity.EbookImageRole;
import com.ebookwriter.SaaS.service.ebook.EbookValidationService.Issue;
import com.ebookwriter.SaaS.service.ebook.EbookValidationService.Severity;
import com.ebookwriter.SaaS.service.storage.R2StorageService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Primary production regression: <b>The 7-Day Focus Reset</b> — an introduction,
 * seven day chapters with day openers, exercises, checklists, action plans, a
 * flow diagram, a table, contextual illustrations, and a designed conclusion —
 * rendered through the real pipeline (same {@link EbookHtmlBuilder} + CSS as the
 * editor preview, openhtmltopdf, embedded fonts, images streamed from a stubbed
 * store) and then put through the automated production QA
 * ({@link PdfQualityInspector}).
 *
 * <p>It asserts what a reader would notice: every image drawn and none distorted
 * (including a user-uploaded square cover cropped into a portrait region), fonts
 * embedded, no empty or stray pages, nothing overflowing the page, contents page
 * numbers equal to the pages the entries point at, and a sparse final page being
 * reflowed away by the snug ending.
 */
class FocusResetRegressionTest {

    private final Map<String, byte[]> store = new HashMap<>();
    private PdfGenerationService renderer;

    @BeforeEach
    void setUp() {
        R2StorageService storage = mock(R2StorageService.class);
        when(storage.download(anyString())).thenAnswer(inv -> Optional.ofNullable(store.get(inv.<String>getArgument(0))));
        renderer = new PdfGenerationService(null, null, null, new EbookHtmlBuilder(), null, storage);
    }

    @Test
    void theSevenDayFocusResetPassesProductionQa() throws IOException {
        Ebook ebook = focusReset();
        ebook.setCoverLayout(CoverLayout.IMAGE_LED);
        List<EbookImage> images = new ArrayList<>();
        images.add(image(ebook, EbookImagePlacement.COVER, 1024, 1536));
        EbookImage desk = image(ebook, EbookImagePlacement.CHAPTER, 1536, 1024);
        EbookImage walk = image(ebook, EbookImagePlacement.CHAPTER, 1536, 1024);
        images.add(desk);
        images.add(walk);

        List<EbookChapter> chapters = chapters(desk, walk);
        byte[] pdf = renderer.render(ebook, chapters, images);

        PdfQualityInspector.Result qa = PdfQualityInspector.inspect(pdf,
                EbookValidationService.expectedDrawnImages(chapters, images));

        assertNoIssues(qa.issues(), "distorted", "missing or failed", "not embedded", "is empty",
                "stray artifact", "outside the page", "contents lists page", "could not be verified",
                "expected the 6x9in trim", "soft", "heavily compressed");
        assertTrue(qa.issues().stream().noneMatch(i -> i.severity() == Severity.FATAL));
        assertTrue(qa.pageCount() > chapters.size(), "cover + contents + every chapter render");

        // The designed ending is the book's last content: the closing line is on the final page.
        try (PDDocument doc = PDDocument.load(pdf)) {
            String lastPages = text(doc, Math.max(1, doc.getNumberOfPages() - 1), doc.getNumberOfPages());
            assertTrue(lastPages.contains("The first hour is yours"),
                    "the book's intentional closing line ends the PDF");
        }
    }

    @Test
    void aUserUploadedSquareCoverIsCroppedNotStretched() {
        Ebook ebook = focusReset();
        ebook.setCoverLayout(CoverLayout.IMAGE_LED); // 2:3 portrait region
        EbookImage square = image(ebook, EbookImagePlacement.COVER, 1200, 1200);
        square.setFocalX(40);
        square.setFocalY(50);

        byte[] pdf = renderer.render(ebook, chapters(null, null).subList(0, 2), List.of(square));

        PdfQualityInspector.Result qa = PdfQualityInspector.inspect(pdf, 1);
        assertNoIssues(qa.issues(), "distorted", "missing or failed");
    }

    @Test
    void aSparseFinalPageIsReflowedAwayBySnugEnding() {
        Ebook ebook = focusReset();
        List<EbookChapter> chapters = new ArrayList<>(chapters(null, null).subList(0, 2));
        EbookChapter ending = EbookChapter.builder().chapterNumber(3).title("Your Focus, From Here")
                .description("Bring the reset together.").approxPages(3).build();
        chapters.add(ending);

        // Grow the ending one short paragraph at a time until the last page holds
        // only a spilled paragraph — the exact "one sentence + empty page" anomaly.
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 26; i++) {
            body.append(paragraph(i)).append("\n\n");
        }
        byte[] pdf = null;
        boolean sparse = false;
        for (int i = 26; i < 80 && !sparse; i++) {
            body.append(paragraph(i)).append("\n\n");
            ending.setContent(body.toString());
            pdf = renderer.render(ebook, chapters, List.of());
            sparse = PdfQualityInspector.lastPageSparse(pdf);
        }
        assertTrue(sparse, "test setup should produce a sparse final page");
        int natural = pages(pdf);

        ebook.setLayoutSnugEnding(true);
        int snug = pages(renderer.render(ebook, chapters, List.of()));

        assertEquals(natural - 1, snug, "the spilled line reflows back and the near-empty page disappears");
    }

    // ---- fixtures -------------------------------------------------------------

    private static void assertNoIssues(List<Issue> issues, String... fragments) {
        for (Issue issue : issues) {
            for (String f : fragments) {
                assertFalse(issue.message().contains(f), "unexpected QA issue: " + issue.message());
            }
        }
    }

    private static Ebook focusReset() {
        return Ebook.builder()
                .id(UUID.randomUUID())
                .topic("A 7-day program to rebuild deep focus")
                .title("The 7-Day Focus Reset")
                .subtitle("Reclaim your attention in one week")
                .authorName("Scrivetta Test")
                .build();
    }

    private List<EbookChapter> chapters(EbookImage desk, EbookImage walk) {
        List<EbookChapter> list = new ArrayList<>();
        list.add(chapter(1, "Why Your Focus Broke", "How attention got fragmented, and why a reset works.",
                intro(desk)));
        String[] days = {
                "Remove the Biggest Distractions", "Design a Focus Environment", "Train Single-Tasking",
                "Build a Deep-Work Block", "Reset Your Evenings", "Handle Relapse Without Guilt",
                "Make It Stick"};
        for (int d = 1; d <= 7; d++) {
            list.add(chapter(d + 1, "Day " + d + " — " + days[d - 1],
                    "Today's change takes 20–30 minutes and targets one habit.",
                    day(d, d == 4 ? walk : null)));
        }
        list.add(chapter(9, "Your Focus, From Here",
                "Synthesis, a next-week plan, a final checklist and the closing.", conclusion()));
        return list;
    }

    private static EbookChapter chapter(int n, String title, String description, String content) {
        return EbookChapter.builder().chapterNumber(n).title(title).description(description)
                .approxPages(3).content(content).build();
    }

    private static String intro(EbookImage image) {
        return """
                Focus rarely disappears in one dramatic moment. It erodes: a notification here, a quick check there, until deep work feels foreign.

                ## The loop that keeps you distracted

                :::flow
                Difficult work
                Discomfort
                Check phone
                Relief
                Less tolerance for difficulty
                :::

                %s

                ## How this week works

                | Day | Focus | Time |
                |---|---|---|
                | 1 | Remove distractions | 30 min |
                | 2 | Environment | 25 min |
                | 3 | Single-tasking | 20 min |
                | 4 | Deep-work block | 45 min |
                | 5 | Evenings | 20 min |
                | 6 | Relapse | 15 min |
                | 7 | Make it stick | 30 min |

                :::key-idea
                Focus is a state your environment creates, not a trait you are born with.
                :::
                """.formatted(image == null ? "" : "![A cleared desk ready for deep work](" + image.markdownRef() + ")")
                + filler(4);
    }

    private static String day(int d, EbookImage image) {
        return """
                Today is about one change. Small, specific and finished before the day ends.

                ## Why it matters

                %s

                %s

                :::exercise The %d-minute audit
                Write down every app you opened today without deciding to. Circle the three that cost you the most attention.
                :::

                :::steps Day %d
                - Name the trigger | 5 min
                  Notice the moment you reach for the phone.
                - Remove the path | 10 min
                  Delete, hide or log out of the worst offender.
                - Protect the block | 15 min
                  Start one task with the phone in another room.
                :::

                :::checklist Done today
                - Trigger named
                - Path removed
                - Block protected
                :::

                Done when: you have finished one protected block without checking your phone.
                """.formatted(filler(2), image == null ? "" : "![A short walk resets attention](" + image.markdownRef() + ")",
                10 + d, d);
    }

    private static String conclusion() {
        return """
                Seven days ago focus felt like something that happened to you. Now it is something you arrange: fewer triggers, a protected block, evenings that end on purpose.

                ## What changed

                """ + filler(3) + """

                :::steps Your next week
                - Keep the morning block | daily
                  Guard the first hour exactly as on Day 4.
                - Review on Sunday | 15 min
                  Look at what pulled you away and remove one path.
                :::

                :::checklist Your reset is complete when
                - Notifications are off by default
                - One deep-work block happens daily
                - Evenings end without a screen
                :::

                :::key-idea
                Attention follows design. Keep designing.
                :::

                The first hour is yours. Keep it that way.
                """;
    }

    private static String filler(int paragraphs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paragraphs; i++) {
            sb.append("Attention works like a budget that is spent before you notice. Every switch between tasks leaves a residue that lingers, "
                    + "so the next task starts at a deficit. The practical answer is not more willpower but fewer decisions: remove the cue, "
                    + "shorten the path to the work, and let the environment carry the effort you used to demand from yourself.\n\n");
        }
        return sb.toString();
    }

    private static String paragraph(int i) {
        return "Step " + (i + 1) + " is to keep the block small and protected so the habit survives a busy week.";
    }

    private EbookImage image(Ebook ebook, EbookImagePlacement placement, int w, int h) {
        UUID id = UUID.randomUUID();
        String key = "ebooks/" + ebook.getId() + "/images/" + id + ".png";
        store.put(key, png(w, h));
        return EbookImage.builder().id(id).ebook(ebook)
                .role(placement == EbookImagePlacement.COVER ? EbookImageRole.COVER : EbookImageRole.ILLUSTRATION)
                .placement(placement).storageKey(key).contentType("image/png").width(w).height(h).build();
    }

    private static byte[] png(int w, int h) {
        try {
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setPaint(new GradientPaint(0, 0, new Color(40, 60, 90), w, h, new Color(220, 200, 160)));
            g.fillRect(0, 0, w, h);
            g.dispose();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static int pages(byte[] pdf) {
        try (PDDocument doc = PDDocument.load(pdf)) {
            return doc.getNumberOfPages();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String text(PDDocument doc, int from, int to) throws IOException {
        org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
        stripper.setStartPage(from);
        stripper.setEndPage(to);
        return stripper.getText(doc);
    }
}
