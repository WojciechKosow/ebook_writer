# Knowledge-based book generation — stage 3

```
BookKnowledge + BookBlueprint (BLUEPRINT_READY) + UserAnswers
   → Claude, chapter by chapter → existing images → existing editor → existing PDF
```

This stage uses the existing pipeline in `EbookGenerationService`. Only two
things differ for knowledge-based books: how the book is planned, and the
prompt each chapter gets. The rest is shared with legacy books: credit pacing
(`WritingBudget`), the editorial pass, images, cover, rendering, validation and
billing.

## Mode selection (`EbookService.start` → `BookBlueprintService.readiness`)

| Book state | Result |
|---|---|
| Blueprint is `BLUEPRINT_READY` and the knowledge is current | `KNOWLEDGE` |
| Materials or a blueprint exist but are not finished | 409, with what to finish first |
| No materials | `LEGACY` (the original brief-only flow; `null` mode on old books) |

## Planning (`KnowledgeBookPlanner`)

There is no Claude planning call:

- Each blueprint chapter becomes an `EbookChapter` with `blueprintChapterId`.
- The scope is the chapter's purpose plus its topics.
- Page sizes are spread over chapters by how much knowledge each one carries.
- The existing `clampToBudget` enforces the credit ceiling and keeps the final
  chapter.

## Per-chapter context (`KnowledgeChapterContext`)

Every chapter request carries five parts:

- **Book context.** Title, reader, goal, promise, concept, project (name, type,
  technologies), terminology, and the author's own order of work.
- **Chapter knowledge.** The BookKnowledge items the blueprint mapped to the
  chapter (score 100), then items that share the chapter's source files
  (+10 per code file, +2 for the notes). Items matching a word in the title or
  topics get +5. Items owned by another chapter get −8. The threshold is 7,
  and the section is capped at `knowledge-writing.max-chapter-knowledge-chars`.
- **Answers.** The author's answers linked to this chapter, plus book-wide
  answers.
- **Not provided.** Open gaps (skipped or not asked) for this chapter, plus
  book-level gaps.
- **Source files.** Excerpts of up to 3 of the chapter's own source files
  (code, build and config first; never the notes or the whole upload), each
  ≤ 3,500 chars and ≤ 9,000 chars in total.

Earlier chapters' summaries carry continuity. The prompt asks each summary to
end with "Names introduced: …".

## Prompts (`KnowledgeChapterPrompts`)

`ChapterPrompts.system` (formatting, components, summary, ending) is followed
by these rules:

- Source hierarchy: the author's knowledge first, then the blueprint, then
  general knowledge.
- Write concretely about this project, using its real code and names.
- Keep the author's experience, told in the author's voice.
- Never fill gaps with invented facts; when a gap must be covered, give a
  neutral general explanation instead.
- Style changes how things are said, not the facts. Do not translate
  identifiers.
- Keep continuity with earlier chapters.

The editorial pass and image planning get the project context.

## Failures

- A chapter that fails, after the client's own retries, is marked `FAILED`
  with `generationError`, and writing continues.
- Failed chapters get one more attempt at the end.
- If a chapter still fails, the book is marked `FAILED` and the hold is
  refunded. Written chapters are kept and `resumable=true`.
- `POST /api/ebooks/{id}/resume` reserves a new hold (same rules as `start`),
  writes only the missing chapters, and finishes and bills the book.

## Configuration

- Claude model: `ANTHROPIC_MODEL` (one place, `AnthropicProperties`).
- Editing model: `ANTHROPIC_EDITING_MODEL`.
- Context limits: `knowledge-writing.*` (`KnowledgeWritingProperties`).
