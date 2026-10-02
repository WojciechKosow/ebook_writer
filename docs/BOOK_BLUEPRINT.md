# Book Blueprint — stage 2 of the knowledge-based flow

```
BookKnowledge + brief (goal, audience) → OpenAI → BOOK BLUEPRINT → KNOWLEDGE GAPS
      → a few QUESTIONS → author's ANSWERS → BLUEPRINT_READY
```

No book text is written and Claude is not called. The book stays a `DRAFT`, and
no credits are used. The blueprint references `BookKnowledge` (see
[KNOWLEDGE_INGESTION.md](KNOWLEDGE_INGESTION.md)) and never copies the
materials.

## API (`/api/ebooks/{id}/blueprint`, owner-scoped, DRAFT books only)

| Method | Path | |
|---|---|---|
| GET | `` | status, blueprint, questions (with answers), summary, usage, warnings |
| POST | `/build[?force=true]` | build / rebuild in the background (202), then poll GET |
| PUT | `` | edit title/subtitle/concept/audience/readerGoal/promise and the chapter list (rename, reorder, add, remove, purpose/topics) |
| PUT | `/questions/{qid}` | `{"answer": "..."}` or `{"skip": true}` |
| POST | `/approve` | BLUEPRINT_REVIEW → BLUEPRINT_READY |

## Status (`BookBlueprint.status`)

`NOT_STARTED → BUILDING_BLUEPRINT`, then either:

- `QUESTIONS_REQUIRED` → when every question is answered or skipped →
  `BLUEPRINT_READY`
- `BLUEPRINT_REVIEW` (no gaps worth asking about) → approve → `BLUEPRINT_READY`

`FAILED` can be retried. Building requires knowledge in `KNOWLEDGE_READY` or
`READY_FOR_BLUEPRINT`; a build moves `KNOWLEDGE_READY` to `READY_FOR_BLUEPRINT`.

## Data

**`book_blueprints`** — one row per book. It holds `blueprintJson`
(`BlueprintData`), status, the knowledge run it was built from (used to detect
outdated knowledge), the `userEdited` flag, usage and cost (last build and
lifetime), and warnings.

**`BlueprintData`** fields:

- `concept`, `workingTitle`, `subtitle`, `audience`, `readerGoal`, `promise`,
  `structureRationale`
- `chapters[]`, each with:
  - `id` (stable), `order`, `title`, `purpose`, `topics`
  - `keyPoints` (the author's points, each sourced)
  - `knowledgeReferences` ({type, name} pointing into BookKnowledge)
  - `sourceReferences` (BookKnowledge source refs)
  - `gapIds`, `origin` (AI/AUTHOR), `edited`
- `knowledgeGaps[]`: `id`, `description`, `whyItMatters`, `severity`,
  `chapterIds`, `status` (OPEN / ANSWERED / SKIPPED / NOT_ASKED), `questionId`
- `userEditedFields`

**`blueprint_questions`** — one row per question. It holds `gapId`,
`chapterId`, `question`, `reason`, `priority` (1–5), `status` (OPEN / ANSWERED
/ SKIPPED), `answer`, `answeredAt` and `generation`.

## Pipeline (`BookBlueprintService.build`)

1. **Prompt.** The prompt (`BlueprintPrompts`) contains:
   - the brief, with chapter guidance from `ContentBudget`
   - the compact BookKnowledge JSON (no bookkeeping, trimmed per list to
     `blueprint.max-knowledge-chars`)
   - the valid source refs
   - on rebuild: author-confirmed fields, answers, and the current structure
     with ids and edit markers
2. **One OpenAI call** (JSON mode) with model `openai.blueprint-model`, which
   defaults to the knowledge model, at reasoning effort `medium`.
3. **`BlueprintAssembler`** (pure):
   - validates knowledge refs against real BookKnowledge items and drops
     invented ones
   - resolves sources, and each chapter also inherits the sources of the
     knowledge it uses
   - links gaps to chapters in both directions and drops `canBeInferred` gaps
   - keeps one question per gap, sorted by priority, capped at
     `blueprint.max-questions` (8); gaps without a question become `NOT_ASKED`
   - on rebuild, keeps the author's edited and added chapters (matched by
     `sameAsChapterId`, then by title) and the author's edited fields
4. **Persist** (one transaction):
   - answered questions are kept and re-linked via `answerLinks`
   - open and skipped questions are replaced
   - status becomes `QUESTIONS_REQUIRED` or `BLUEPRINT_REVIEW`

Rebuilding a blueprint the author has edited returns 409 unless `force=true`.

## For the writing stage

```java
blueprintService.getGenerationInput(ebookId);
// Optional<BookGenerationInput>: knowledge + blueprint + answers + unresolvedGaps
```

It is present only when the blueprint is `BLUEPRINT_READY` and the knowledge
has not been re-processed since. `input.answersFor(chapterId)` returns the
author's answers for that chapter. `unresolvedGaps` lists what the author did
not provide; the writer must not invent it.

## Configuration

- `OPENAI_BLUEPRINT_MODEL` (blank = `OPENAI_KNOWLEDGE_MODEL`)
- `OPENAI_BLUEPRINT_REASONING_EFFORT`
- `OPENAI_BLUEPRINT_MAX_OUTPUT_TOKENS`
- `blueprint.*` limits: max questions, max chapters, max rebuilds, knowledge
  input size, max answer length
