package com.ebookwriter.SaaS.service.blueprint;

import com.ebookwriter.SaaS.config.properties.BlueprintProperties;
import com.ebookwriter.SaaS.config.properties.OpenAiProperties;
import com.ebookwriter.SaaS.dto.blueprint.BlueprintData;
import com.ebookwriter.SaaS.dto.blueprint.BlueprintData.Chapter;
import com.ebookwriter.SaaS.dto.blueprint.BlueprintData.Gap;
import com.ebookwriter.SaaS.dto.blueprint.BlueprintOverviewResponse;
import com.ebookwriter.SaaS.dto.blueprint.BlueprintQuestionDTO;
import com.ebookwriter.SaaS.dto.blueprint.BookGenerationInput;
import com.ebookwriter.SaaS.dto.knowledge.BookKnowledgeData;
import com.ebookwriter.SaaS.entity.BlueprintQuestion;
import com.ebookwriter.SaaS.entity.BlueprintStatus;
import com.ebookwriter.SaaS.entity.BookBlueprint;
import com.ebookwriter.SaaS.entity.BookKnowledge;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookStatus;
import com.ebookwriter.SaaS.entity.KnowledgeStatus;
import com.ebookwriter.SaaS.entity.QuestionStatus;
import com.ebookwriter.SaaS.prompt.BlueprintPrompts;
import com.ebookwriter.SaaS.repository.BlueprintQuestionRepository;
import com.ebookwriter.SaaS.repository.BookBlueprintRepository;
import com.ebookwriter.SaaS.repository.BookKnowledgeRepository;
import com.ebookwriter.SaaS.repository.EbookRepository;
import com.ebookwriter.SaaS.request.BlueprintUpdateRequest;
import com.ebookwriter.SaaS.service.ai.OpenAiTextClient;
import com.ebookwriter.SaaS.service.ai.OpenAiTextException;
import com.ebookwriter.SaaS.service.knowledge.KnowledgeAssembler;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Stage two of the knowledge-based flow:
 * <pre>
 *   BookKnowledge + brief (goal, audience) ─▶ OpenAI ─▶ Book Blueprint
 *        ─▶ knowledge gaps ─▶ a few questions ─▶ author's answers ─▶ BLUEPRINT_READY
 * </pre>
 * Builds the blueprint from the stored {@link BookKnowledge} (never a second
 * copy of the materials), lets the author edit it and answer the questions, and
 * exposes {@link #getGenerationInput(UUID)} — BookKnowledge + blueprint +
 * answers — for the writing stage. OpenAI only; Claude is not called, and no
 * book text is written. The book stays a DRAFT and no credits are used.
 */
@Slf4j
@Service
public class BookBlueprintService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final EnumSet<BlueprintStatus> CLAIMABLE = EnumSet.of(
            BlueprintStatus.NOT_STARTED, BlueprintStatus.FAILED, BlueprintStatus.BLUEPRINT_REVIEW,
            BlueprintStatus.QUESTIONS_REQUIRED, BlueprintStatus.BLUEPRINT_READY);

    private final EbookRepository ebookRepository;
    private final BookKnowledgeRepository knowledgeRepository;
    private final BookBlueprintRepository blueprintRepository;
    private final BlueprintQuestionRepository questionRepository;
    private final OpenAiTextClient openAi;
    private final OpenAiProperties openAiProperties;
    private final BlueprintProperties limits;
    private final TransactionTemplate tx;
    private final BlueprintBuildWorker worker;

    public BookBlueprintService(EbookRepository ebookRepository, BookKnowledgeRepository knowledgeRepository,
                                BookBlueprintRepository blueprintRepository, BlueprintQuestionRepository questionRepository,
                                OpenAiTextClient openAi, OpenAiProperties openAiProperties, BlueprintProperties limits,
                                TransactionTemplate tx, @Lazy BlueprintBuildWorker worker) {
        this.ebookRepository = ebookRepository;
        this.knowledgeRepository = knowledgeRepository;
        this.blueprintRepository = blueprintRepository;
        this.questionRepository = questionRepository;
        this.openAi = openAi;
        this.openAiProperties = openAiProperties;
        this.limits = limits;
        this.tx = tx;
        this.worker = worker;
    }

    // =====================================================================
    // Contract for the writing stage
    // =====================================================================

    /** The stored blueprint (any status that has one). */
    public Optional<BlueprintData> getBlueprint(UUID ebookId) {
        return blueprintRepository.findByEbookId(ebookId)
                .filter(b -> b.getBlueprintJson() != null)
                .map(b -> readBlueprint(b.getBlueprintJson()));
    }

    /** True when the blueprint is final (BLUEPRINT_READY) and the knowledge it was built on is still current. */
    public boolean isBlueprintReady(UUID ebookId) {
        return getGenerationInput(ebookId).isPresent();
    }

    /**
     * BookKnowledge + BookBlueprint + the author's answers — the input of the
     * writing stage. Present only when the blueprint is BLUEPRINT_READY and the
     * knowledge is still up to date.
     */
    public Optional<BookGenerationInput> getGenerationInput(UUID ebookId) {
        Optional<BookBlueprint> row = blueprintRepository.findByEbookId(ebookId)
                .filter(b -> b.getStatus() == BlueprintStatus.BLUEPRINT_READY && b.getBlueprintJson() != null);
        Optional<BookKnowledge> knowledge = knowledgeRepository.findByEbookId(ebookId)
                .filter(k -> k.getStatus().hasKnowledge() && k.getKnowledgeJson() != null);
        if (row.isEmpty() || knowledge.isEmpty() || isOutdated(row.get(), knowledge.get())) {
            return Optional.empty();
        }
        BlueprintData blueprint = readBlueprint(row.get().getBlueprintJson());
        List<BookGenerationInput.Answer> answers = questionRepository.findByEbookIdOrderBySortOrderAscCreatedAtAsc(ebookId)
                .stream().filter(q -> q.getStatus() == QuestionStatus.ANSWERED)
                .map(q -> new BookGenerationInput.Answer(q.getId(), q.getChapterId(), q.getGapId(), q.getQuestion(), q.getAnswer()))
                .toList();
        List<Gap> unresolved = blueprint.knowledgeGaps().stream()
                .filter(g -> !BlueprintAssembler.GAP_ANSWERED.equals(g.status())).toList();
        return Optional.of(new BookGenerationInput(ebookId, KnowledgeAssembler.read(knowledge.get().getKnowledgeJson()),
                blueprint, answers, unresolved));
    }

    // =====================================================================
    // Author-facing
    // =====================================================================

    public BlueprintOverviewResponse overview(UUID ebookId, UUID userId) {
        requireOwned(ebookId, userId);
        return overview(ebookId);
    }

    BlueprintOverviewResponse overview(UUID ebookId) {
        Optional<BookBlueprint> row = blueprintRepository.findByEbookId(ebookId);
        Optional<BookKnowledge> knowledge = knowledgeRepository.findByEbookId(ebookId);
        boolean knowledgeReady = knowledge.map(k -> k.getStatus().hasKnowledge()).orElse(false);
        BlueprintStatus status = row.map(BookBlueprint::getStatus).orElse(BlueprintStatus.NOT_STARTED);
        BlueprintData data = row.map(BookBlueprint::getBlueprintJson).map(BookBlueprintService::readBlueprint).orElse(null);
        List<BlueprintQuestion> questions = questionRepository.findByEbookIdOrderBySortOrderAscCreatedAtAsc(ebookId);

        Map<String, String> chapterTitles = data == null ? Map.of()
                : data.chapters().stream().collect(Collectors.toMap(Chapter::id, Chapter::title, (a, b) -> a));
        List<BlueprintQuestionDTO> dtos = questions.stream().map(q -> new BlueprintQuestionDTO(q.getId(), q.getGapId(),
                q.getChapterId(), q.getChapterId() == null ? null : chapterTitles.get(q.getChapterId()), q.getQuestion(),
                q.getReason(), q.getPriority(), q.getStatus(), q.getAnswer(), q.getAnsweredAt())).toList();

        BlueprintOverviewResponse.Summary summary = null;
        if (data != null) {
            int grounded = (int) data.chapters().stream()
                    .filter(c -> !c.knowledgeReferences().isEmpty() || !c.sourceReferences().isEmpty()).count();
            int openGaps = (int) data.knowledgeGaps().stream()
                    .filter(g -> !BlueprintAssembler.GAP_ANSWERED.equals(g.status())).count();
            summary = new BlueprintOverviewResponse.Summary(data.chapters().size(), grounded, data.knowledgeGaps().size(),
                    openGaps, questions.size(), count(questions, QuestionStatus.ANSWERED),
                    count(questions, QuestionStatus.SKIPPED), count(questions, QuestionStatus.OPEN));
        }
        BlueprintOverviewResponse.Usage usage = row.map(b -> new BlueprintOverviewResponse.Usage(b.getModel(),
                b.getOpenAiCalls(), b.getInputTokens(), b.getOutputTokens(), b.getEstimatedCostUsd(), b.getGeneration(),
                limits.getMaxGenerations())).orElse(null);
        boolean outdated = row.isPresent() && row.get().getBlueprintJson() != null
                && (knowledge.isEmpty() || isOutdated(row.get(), knowledge.get()));
        return new BlueprintOverviewResponse(ebookId, status, row.map(BookBlueprint::getErrorMessage).orElse(null),
                knowledgeReady, outdated, openAi.isConfigured(), row.map(BookBlueprint::isUserEdited).orElse(false),
                data, dtos, summary, usage, row.map(b -> readList(b.getWarningsJson())).orElse(List.of()),
                row.map(BookBlueprint::getGeneratedAt).orElse(null), row.map(BookBlueprint::getReadyAt).orElse(null));
    }

    /**
     * Build (or rebuild) the blueprint in the background. Rebuilding a blueprint
     * the author has edited needs {@code force=true}: the author's edited/added
     * chapters, edited fields and answers are kept, but the rest of the structure
     * is proposed again.
     */
    public BlueprintOverviewResponse startBuild(UUID ebookId, UUID userId, boolean force) {
        Ebook ebook = requireDraft(ebookId, userId);
        if (!openAi.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Blueprint building is not available right now (OpenAI is not configured).");
        }
        BookKnowledge knowledge = knowledgeRepository.findByEbookId(ebookId)
                .filter(k -> k.getStatus().hasKnowledge())
                .orElseThrow(() -> new IllegalStateException(
                        "Scrivetta needs to learn from your materials first (knowledge is not ready)."));
        BookBlueprint blueprint = findOrCreate(ebook);
        if (blueprint.getStatus() == BlueprintStatus.BUILDING_BLUEPRINT
                && blueprint.getStartedAt() != null
                && blueprint.getStartedAt().isAfter(LocalDateTime.now().minusMinutes(limits.getStaleBuildingMinutes()))) {
            throw new IllegalStateException("Scrivetta is already building your blueprint.");
        }
        if (blueprint.getGeneration() >= limits.getMaxGenerations()) {
            throw new IllegalStateException("This book's blueprint has been built the maximum number of times ("
                    + limits.getMaxGenerations() + ").");
        }
        if (blueprint.isUserEdited() && blueprint.getStatus().hasBlueprint() && !force) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You have edited this blueprint. Rebuilding keeps "
                    + "your edited and added chapters and your answers, but proposes the rest of the structure again. "
                    + "Confirm to rebuild.");
        }
        if (knowledge.getStatus() == KnowledgeStatus.KNOWLEDGE_READY) {
            knowledgeRepository.markReadyForBlueprint(ebookId, KnowledgeStatus.KNOWLEDGE_READY,
                    KnowledgeStatus.READY_FOR_BLUEPRINT, LocalDateTime.now());
        }
        LocalDateTime now = LocalDateTime.now();
        if (blueprintRepository.claimForBuilding(ebookId, CLAIMABLE, now.minusMinutes(limits.getStaleBuildingMinutes()),
                BlueprintStatus.BUILDING_BLUEPRINT, now) != 1) {
            throw new IllegalStateException("Scrivetta is already building your blueprint.");
        }
        worker.run(ebookId);
        return overview(ebookId);
    }

    /** Run a claimed build. Never throws: failures end in FAILED with a reason. */
    public void build(UUID ebookId) {
        long started = System.currentTimeMillis();
        BookBlueprint row = blueprintRepository.findByEbookId(ebookId).orElse(null);
        if (row == null) return;
        try {
            Ebook ebook = ebookRepository.findById(ebookId).orElseThrow();
            BookKnowledge knowledgeRow = knowledgeRepository.findByEbookId(ebookId)
                    .filter(k -> k.getStatus().hasKnowledge() && k.getKnowledgeJson() != null)
                    .orElse(null);
            if (knowledgeRow == null) {
                fail(ebookId, "Your materials changed — let Scrivetta learn from them again first.", null);
                return;
            }
            BookKnowledgeData knowledge = KnowledgeAssembler.read(knowledgeRow.getKnowledgeJson());
            BlueprintData previous = row.getBlueprintJson() == null ? null : readBlueprint(row.getBlueprintJson());
            List<BlueprintQuestion> existing = questionRepository.findByEbookIdOrderBySortOrderAscCreatedAtAsc(ebookId);
            List<BlueprintQuestion> answered = existing.stream().filter(q -> q.getStatus() == QuestionStatus.ANSWERED).toList();

            String userPrompt = BlueprintPrompts.user(ebook, compactKnowledge(knowledge), validRefs(knowledge),
                    confirmedFields(previous), answersText(answered),
                    row.isUserEdited() && previous != null ? structureText(previous) : null);

            OpenAiTextClient.JsonCompletion completion = openAi.completeJson(
                    BlueprintPrompts.system(limits.getMaxQuestions()), userPrompt,
                    OpenAiTextClient.CallOptions.blueprint(openAiProperties));
            BlueprintAssembler.Result result = BlueprintAssembler.assemble(completion.json(), knowledge,
                    row.isUserEdited() ? previous : null,
                    answered.stream().map(q -> q.getId().toString()).collect(Collectors.toSet()),
                    limits.getMaxQuestions(), limits.getMaxChapters());

            int generation = row.getGeneration();
            BlueprintStatus status = tx.execute(s -> persistBuild(ebookId, ebook, result, existing, answered,
                    completion, knowledgeRow.getProcessingRuns(), generation));
            log.info("Blueprint built for ebook {} in {} ms: {} chapters, {} gaps, {} questions → {}; {} in / {} out tokens",
                    ebookId, System.currentTimeMillis() - started, result.blueprint().chapters().size(),
                    result.blueprint().knowledgeGaps().size(), result.questions().size(), status,
                    completion.inputTokens(), completion.outputTokens());
        } catch (OpenAiTextException | IllegalArgumentException e) {
            log.warn("Blueprint build failed for ebook {}: {}", ebookId, e.getMessage());
            fail(ebookId, "Scrivetta could not build your blueprint right now. Please try again.", null);
        } catch (RuntimeException e) {
            log.error("Blueprint build failed unexpectedly for ebook {}", ebookId, e);
            fail(ebookId, "Building the blueprint failed unexpectedly. Please try again.", null);
        }
    }

    private BlueprintStatus persistBuild(UUID ebookId, Ebook ebook, BlueprintAssembler.Result result,
                                         List<BlueprintQuestion> existing, List<BlueprintQuestion> answered,
                                         OpenAiTextClient.JsonCompletion completion, int knowledgeRun, int generation) {
        BlueprintData data = result.blueprint();
        Set<String> chapterIds = data.chapters().stream().map(Chapter::id).collect(Collectors.toSet());

        // Answered questions are the author's knowledge: keep them, re-link to the new chapters.
        for (BlueprintQuestion q : answered) {
            String link = result.answerLinks().get(q.getId().toString());
            if (link != null) q.setChapterId(link);
            else if (q.getChapterId() != null && !chapterIds.contains(q.getChapterId())) q.setChapterId(null);
            q.setGapId(null);
        }
        questionRepository.saveAll(answered);
        // Open / skipped questions of the previous build are replaced by the new ones.
        questionRepository.deleteAll(existing.stream().filter(q -> q.getStatus() != QuestionStatus.ANSWERED).toList());

        int order = answered.size();
        Map<String, String> questionByGap = new HashMap<>();
        for (BlueprintAssembler.ProposedQuestion pq : result.questions()) {
            BlueprintQuestion saved = questionRepository.save(BlueprintQuestion.builder()
                    .ebook(ebook).gapId(pq.gapId()).chapterId(pq.chapterId()).question(pq.question())
                    .reason(pq.reason()).priority(pq.priority()).sortOrder(order++).status(QuestionStatus.OPEN)
                    .generation(generation).build());
            if (pq.gapId() != null) questionByGap.put(pq.gapId(), saved.getId().toString());
        }
        List<Gap> gaps = data.knowledgeGaps().stream().map(g -> questionByGap.containsKey(g.id())
                ? g.withStatus(BlueprintAssembler.GAP_OPEN, questionByGap.get(g.id()))
                : g.withStatus(BlueprintAssembler.GAP_NOT_ASKED, null)).toList();
        data = data.withGaps(gaps);

        BlueprintStatus status = result.questions().isEmpty()
                ? BlueprintStatus.BLUEPRINT_REVIEW : BlueprintStatus.QUESTIONS_REQUIRED;
        BookBlueprint row = blueprintRepository.findByEbookId(ebookId).orElseThrow();
        row.setBlueprintJson(write(data));
        row.setSchemaVersion(BlueprintData.CURRENT_SCHEMA_VERSION);
        row.setStatus(status);
        row.setErrorMessage(null);
        row.setKnowledgeRun(knowledgeRun);
        row.setWarningsJson(write(result.warnings()));
        row.setGeneratedAt(LocalDateTime.now());
        row.setReadyAt(null);
        double cost = completion.inputTokens() / 1_000_000.0 * openAiProperties.getKnowledgeInputUsdPerMillion()
                + completion.outputTokens() / 1_000_000.0 * openAiProperties.getKnowledgeOutputUsdPerMillion();
        row.setModel(completion.model() != null ? completion.model() : openAiProperties.resolveBlueprintModel());
        row.setOpenAiCalls(completion.attempts());
        row.setInputTokens(completion.inputTokens());
        row.setOutputTokens(completion.outputTokens());
        row.setEstimatedCostUsd(cost);
        row.setTotalInputTokens(row.getTotalInputTokens() + completion.inputTokens());
        row.setTotalOutputTokens(row.getTotalOutputTokens() + completion.outputTokens());
        row.setTotalEstimatedCostUsd(row.getTotalEstimatedCostUsd() + cost);
        blueprintRepository.save(row);
        return status;
    }

    /** Apply the author's edits (title, fields, chapter rename/reorder/add/remove/purpose). */
    public BlueprintOverviewResponse update(UUID ebookId, UUID userId, BlueprintUpdateRequest request) {
        requireDraft(ebookId, userId);
        tx.executeWithoutResult(s -> {
            BookBlueprint row = requireEditable(ebookId);
            BlueprintData d = readBlueprint(row.getBlueprintJson());
            Set<String> edited = new LinkedHashSet<>(d.userEditedFields());
            String title = edit(d.workingTitle(), request.getWorkingTitle(), "workingTitle", edited, 200);
            String subtitle = edit(d.subtitle(), request.getSubtitle(), "subtitle", edited, 300);
            String concept = edit(d.concept(), request.getConcept(), "concept", edited, 1_000);
            String audience = edit(d.audience(), request.getAudience(), "audience", edited, 500);
            String goal = edit(d.readerGoal(), request.getReaderGoal(), "readerGoal", edited, 1_000);
            String promise = edit(d.promise(), request.getPromise(), "promise", edited, 1_000);
            if (title == null || title.isBlank()) throw badRequest("The book needs a title.");

            List<Chapter> chapters = d.chapters();
            List<Gap> gaps = d.knowledgeGaps();
            if (request.getChapters() != null) {
                chapters = editChapters(d.chapters(), request.getChapters());
                Set<String> ids = chapters.stream().map(Chapter::id).collect(Collectors.toSet());
                gaps = gaps.stream().map(g -> new Gap(g.id(), g.description(), g.whyItMatters(), g.severity(),
                        g.chapterIds().stream().filter(ids::contains).toList(), g.status(), g.questionId())).toList();
                List<BlueprintQuestion> orphans = questionRepository.findByEbookIdOrderBySortOrderAscCreatedAtAsc(ebookId)
                        .stream().filter(q -> q.getChapterId() != null && !ids.contains(q.getChapterId())).toList();
                orphans.forEach(q -> q.setChapterId(null));
                questionRepository.saveAll(orphans);
            }
            BlueprintData updated = new BlueprintData(d.schemaVersion(), concept, title, subtitle, audience, goal,
                    promise, d.structureRationale(), chapters, gaps, List.copyOf(edited));
            row.setBlueprintJson(write(updated));
            row.setUserEdited(true);
            blueprintRepository.save(row);
        });
        return overview(ebookId);
    }

    /** Save (or change) the answer to a question, or skip it. The last open question completes the blueprint. */
    public BlueprintOverviewResponse answer(UUID ebookId, UUID userId, UUID questionId, String answer, boolean skip) {
        requireDraft(ebookId, userId);
        tx.executeWithoutResult(s -> {
            BookBlueprint row = requireEditable(ebookId);
            BlueprintQuestion q = questionRepository.findByIdAndEbookId(questionId, ebookId)
                    .orElseThrow(() -> new IllegalArgumentException("Question not found"));
            String text = answer == null ? "" : answer.strip();
            if (skip) {
                q.setStatus(QuestionStatus.SKIPPED);
                q.setAnswer(null);
                q.setAnsweredAt(null);
            } else {
                if (text.isEmpty()) throw badRequest("Write an answer, or skip the question.");
                if (text.length() > limits.getMaxAnswerChars()) {
                    throw badRequest("The answer is too long (max " + limits.getMaxAnswerChars() + " characters).");
                }
                q.setStatus(QuestionStatus.ANSWERED);
                q.setAnswer(text);
                q.setAnsweredAt(LocalDateTime.now());
            }
            questionRepository.save(q);
            syncGapsWithAnswers(row);
            if (row.getStatus() == BlueprintStatus.QUESTIONS_REQUIRED
                    && questionRepository.countByEbookIdAndStatus(ebookId, QuestionStatus.OPEN) == 0) {
                row.setStatus(BlueprintStatus.BLUEPRINT_READY);
                row.setReadyAt(LocalDateTime.now());
            }
            blueprintRepository.save(row);
        });
        return overview(ebookId);
    }

    /** The author approves the structure (BLUEPRINT_REVIEW → BLUEPRINT_READY). */
    public BlueprintOverviewResponse approve(UUID ebookId, UUID userId) {
        requireDraft(ebookId, userId);
        tx.executeWithoutResult(s -> {
            BookBlueprint row = requireEditable(ebookId);
            if (row.getStatus() == BlueprintStatus.BLUEPRINT_READY) return;
            if (questionRepository.countByEbookIdAndStatus(ebookId, QuestionStatus.OPEN) > 0) {
                throw new IllegalStateException("Answer or skip the remaining questions first.");
            }
            row.setStatus(BlueprintStatus.BLUEPRINT_READY);
            row.setReadyAt(LocalDateTime.now());
            blueprintRepository.save(row);
        });
        return overview(ebookId);
    }

    // =====================================================================
    // internals
    // =====================================================================

    /** Gap status follows its question: ANSWERED / SKIPPED / OPEN. */
    private void syncGapsWithAnswers(BookBlueprint row) {
        BlueprintData d = readBlueprint(row.getBlueprintJson());
        Map<String, QuestionStatus> byId = new HashMap<>();
        for (BlueprintQuestion q : questionRepository.findByEbookIdOrderBySortOrderAscCreatedAtAsc(row.getEbook().getId())) {
            byId.put(q.getId().toString(), q.getStatus());
        }
        List<Gap> gaps = d.knowledgeGaps().stream().map(g -> {
            if (g.questionId() == null || !byId.containsKey(g.questionId())) return g;
            return g.withStatus(switch (byId.get(g.questionId())) {
                case ANSWERED -> BlueprintAssembler.GAP_ANSWERED;
                case SKIPPED -> BlueprintAssembler.GAP_SKIPPED;
                case OPEN -> BlueprintAssembler.GAP_OPEN;
            }, g.questionId());
        }).toList();
        row.setBlueprintJson(write(d.withGaps(gaps)));
    }

    List<Chapter> editChapters(List<Chapter> current, List<BlueprintUpdateRequest.ChapterEdit> edits) {
        if (edits.isEmpty()) throw badRequest("A book needs at least one chapter.");
        if (edits.size() > limits.getMaxChapters()) {
            throw badRequest("A blueprint can have at most " + limits.getMaxChapters() + " chapters.");
        }
        Map<String, Chapter> byId = current.stream().collect(Collectors.toMap(Chapter::id, c -> c, (a, b) -> a));
        Set<String> seen = new HashSet<>();
        List<Chapter> out = new ArrayList<>();
        for (BlueprintUpdateRequest.ChapterEdit e : edits) {
            String title = clean(e.getTitle(), 200);
            if (title == null || title.isBlank()) throw badRequest("Every chapter needs a title.");
            String purpose = clean(e.getPurpose(), 1_000);
            List<String> topics = e.getTopics() == null ? null
                    : e.getTopics().stream().map(t -> clean(t, 200)).filter(t -> t != null && !t.isBlank()).limit(20).toList();
            int order = out.size() + 1;
            if (e.getId() == null || e.getId().isBlank()) {
                out.add(new Chapter(UUID.randomUUID().toString(), order, title, purpose, topics == null ? List.of() : topics,
                        List.of(), List.of(), List.of(), List.of(), BlueprintAssembler.ORIGIN_AUTHOR, true));
                continue;
            }
            Chapter c = byId.get(e.getId());
            if (c == null) throw badRequest("Chapter not found: " + e.getId());
            if (!seen.add(c.id())) throw badRequest("Duplicate chapter: " + e.getId());
            List<String> newTopics = topics == null ? c.topics() : topics;
            boolean changed = !title.equals(c.title()) || !Objects.equals(purpose, c.purpose()) || !newTopics.equals(c.topics());
            out.add(new Chapter(c.id(), order, title, purpose, newTopics, c.keyPoints(), c.knowledgeReferences(),
                    c.sourceReferences(), c.gapIds(), c.origin(), c.edited() || changed));
        }
        return out;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static String edit(String current, String requested, String field, Set<String> edited, int max) {
        if (requested == null) return current;
        String v = clean(requested, max);
        if (!Objects.equals(v, current)) edited.add(field);
        return v;
    }

    private static String clean(String s, int max) {
        if (s == null) return null;
        String v = s.strip();
        return v.length() > max ? v.substring(0, max) : v;
    }

    /** BookKnowledge without the bookkeeping, trimmed to the planner's input budget. */
    String compactKnowledge(BookKnowledgeData k) {
        BookKnowledgeData compact = new BookKnowledgeData(k.schemaVersion(), null, k.project(), k.overallSummary(),
                k.topics(), k.processes(), k.examples(), k.importantDetails(), k.terminology(), k.userInsights(),
                k.technicalDetails(), k.facts(), k.intendedSequence(), k.knowledgeGaps(), List.of(), null);
        String json = write(compact);
        for (int cap : new int[]{30, 15, 8}) {
            if (json.length() <= limits.getMaxKnowledgeChars()) break;
            json = write(KnowledgeAssembler.capLists(compact, cap));
        }
        return json;
    }

    static String validRefs(BookKnowledgeData k) {
        return k.sources().stream().filter(BookKnowledgeData.SourceRef::analyzed)
                .map(s -> "- " + s.ref()).collect(Collectors.joining("\n"));
    }

    private static String confirmedFields(BlueprintData previous) {
        if (previous == null || previous.userEditedFields().isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (String f : previous.userEditedFields()) {
            String v = switch (f) {
                case "workingTitle" -> previous.workingTitle();
                case "subtitle" -> previous.subtitle();
                case "concept" -> previous.concept();
                case "audience" -> previous.audience();
                case "readerGoal" -> previous.readerGoal();
                case "promise" -> previous.promise();
                default -> null;
            };
            if (v != null) sb.append(f).append(": ").append(v).append('\n');
        }
        return sb.toString();
    }

    private static String answersText(List<BlueprintQuestion> answered) {
        if (answered.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (BlueprintQuestion q : answered) {
            sb.append("- questionId: ").append(q.getId()).append("\n  Q: ").append(q.getQuestion())
                    .append("\n  A: ").append(q.getAnswer()).append('\n');
        }
        return sb.toString();
    }

    private static String structureText(BlueprintData d) {
        StringBuilder sb = new StringBuilder();
        for (Chapter c : d.chapters()) {
            sb.append(c.order()).append(". [id: ").append(c.id()).append("] ").append(c.title());
            if (c.purpose() != null) sb.append(" — ").append(c.purpose());
            if (BlueprintAssembler.ORIGIN_AUTHOR.equals(c.origin())) sb.append(" [added by author]");
            else if (c.edited()) sb.append(" [edited by author]");
            sb.append('\n');
        }
        return sb.toString();
    }

    private void fail(UUID ebookId, String message, List<String> warnings) {
        blueprintRepository.findByEbookId(ebookId).ifPresent(b -> {
            // A failed rebuild keeps the previous blueprint visible but not "ready".
            b.setStatus(BlueprintStatus.FAILED);
            b.setErrorMessage(message);
            if (warnings != null) b.setWarningsJson(write(warnings));
            blueprintRepository.save(b);
        });
    }

    private BookBlueprint requireEditable(UUID ebookId) {
        BookBlueprint row = blueprintRepository.findByEbookId(ebookId)
                .orElseThrow(() -> new IllegalStateException("There is no blueprint yet."));
        if (!row.getStatus().hasBlueprint() || row.getBlueprintJson() == null) {
            throw new IllegalStateException(row.getStatus() == BlueprintStatus.BUILDING_BLUEPRINT
                    ? "Scrivetta is building your blueprint — wait for it to finish." : "There is no blueprint to edit yet.");
        }
        return row;
    }

    private Ebook requireOwned(UUID ebookId, UUID userId) {
        return ebookRepository.findByIdAndUserId(ebookId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Ebook not found"));
    }

    private Ebook requireDraft(UUID ebookId, UUID userId) {
        Ebook ebook = requireOwned(ebookId, userId);
        if (ebook.getStatus() != EbookStatus.DRAFT) {
            throw new IllegalStateException("The blueprint can only be changed before the book is generated.");
        }
        return ebook;
    }

    private BookBlueprint findOrCreate(Ebook ebook) {
        return blueprintRepository.findByEbookId(ebook.getId()).orElseGet(() -> {
            try {
                return blueprintRepository.saveAndFlush(BookBlueprint.builder().ebook(ebook)
                        .status(BlueprintStatus.NOT_STARTED).build());
            } catch (DataIntegrityViolationException e) {
                return blueprintRepository.findByEbookId(ebook.getId()).orElseThrow(() -> e);
            }
        });
    }

    private static boolean isOutdated(BookBlueprint b, BookKnowledge k) {
        return !k.getStatus().hasKnowledge() || k.getProcessingRuns() != b.getKnowledgeRun();
    }

    private static int count(List<BlueprintQuestion> qs, QuestionStatus s) {
        return (int) qs.stream().filter(q -> q.getStatus() == s).count();
    }

    static BlueprintData readBlueprint(String json) {
        try {
            return MAPPER.readValue(json, BlueprintData.class);
        } catch (Exception e) {
            throw new IllegalStateException("Stored blueprint is unreadable: " + e.getMessage(), e);
        }
    }

    private static List<String> readList(String json) {
        if (json == null) return List.of();
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String write(Object value) {
        return KnowledgeAssembler.write(value);
    }
}
