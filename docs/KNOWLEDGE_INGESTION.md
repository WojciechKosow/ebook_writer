# Knowledge ingestion — "Tell Scrivetta what you know"

First stage of the knowledge-based flow:

```
USER MATERIALS → FILE EXTRACTION → NORMALISATION → OPENAI → STRUCTURED BOOK KNOWLEDGE → DB
                                                                     ↓
                                                        KNOWLEDGE_READY → READY_FOR_BLUEPRINT
```

It does **not** plan or write the book. **OpenAI** is the knowledge-processing
layer here (cheap model). **Claude** is never called; it stays the
book-writing engine for later stages.

## API (`/api/ebooks/{id}/knowledge`, owner-scoped, DRAFT books only)

| Method | Path | |
|---|---|---|
| GET | `` | status, sources, summary counts, usage, limits |
| GET | `/full` | the structured `BookKnowledgeData` (404 until ready) |
| POST | `/sources` | multipart `file`: ZIP / PDF / DOCX / TXT / MD |
| PUT | `/notes` | `{"text": "..."}`: pasted notes (blank text removes them) |
| DELETE | `/sources/{sourceId}` | remove a source |
| POST | `/process` | start processing in the background (202), then poll GET |
| POST | `/continue` | KNOWLEDGE_READY → READY_FOR_BLUEPRINT |

The brief also takes an optional `bookGoal` (`POST /api/ebooks`).

## Status (`BookKnowledge.status`, separate from `Ebook.status`)

`CREATED → MATERIALS_UPLOADING → PROCESSING → ANALYZING → KNOWLEDGE_READY → READY_FOR_BLUEPRINT`,
or `FAILED`, which you can retry. If the materials change after processing, the
status goes back to `MATERIALS_UPLOADING`, so stale knowledge is never reported
as ready. The book stays a `DRAFT` throughout, and no credits are touched.

## Pipeline

1. **Upload**: `KnowledgeIngestionService` checks the file (size, format, max
   sources, the same file uploaded twice), extracts it at once and stores only
   the normalised text as a `KnowledgeSource` (`documentsJson`, `skippedJson`).
   If one file is corrupt, only that source is marked `FAILED`.
2. **Extraction**:
   - `DocumentTextExtractor` handles PDF (PDFBox), DOCX (StAX with XXE
     disabled, headings become `#`) and TXT/MD (UTF-8, falling back to Latin-1).
   - `ZipKnowledgeExtractor` reads the archive in memory and never writes
     entry paths to disk. It strips a single wrapping folder and keeps each
     file's path, extension and language.
   - It skips dependency and build folders, binaries, lockfiles and likely
     secrets (`.env`, `*.pem`), and records each skip with a reason.
   - It adds one *structure* document (the file tree).
   - Guards: entry count, bytes per file, total inflated bytes, compression
     ratio, wall-clock timeout. Nested archives are not unpacked.
3. **Normalisation** (`TextNormalizer`): removes the BOM and control
   characters, converts line endings to LF, trims whitespace, truncates long
   documents (and flags them), and computes a content hash.
4. **Batching** (`KnowledgeChunker`):
   - Every document gets a citable *ref* (its path, or `user-notes`).
   - Duplicates with the same hash are analysed once.
   - Documents are prioritised: notes, structure, documents, build files,
     config, code, tests, data.
   - They are packed into batches of ≤ `max-chars-per-chunk`; documents too
     large for one batch are split.
   - Packing stops at `max-analysis-chars` / `max-chunks`. Anything left over
     is recorded as *not analysed*.
5. **OpenAI** (`OpenAiTextClient` with `KnowledgePrompts`):
   - One JSON-mode call per batch. Later batches also receive the start of the
     notes as context.
   - `KnowledgeAssembler` checks every cited source against the real refs and
     drops invented ones. It then merges the batches deterministically.
   - When there was more than one batch, one consolidation call follows. If it
     fails, the merged result is used instead.
   - If one batch fails, it is skipped with a warning. The run fails only when
     every batch fails.
6. **Persistence**: one `book_knowledge` row per book stores the versioned
   JSON plus usage: calls, input/output tokens, estimated USD, analysed chars
   and run count, with lifetime totals.

## For the next stage

```java
bookKnowledgeService.isKnowledgeReady(ebookId);   // KNOWLEDGE_READY or READY_FOR_BLUEPRINT
bookKnowledgeService.getBookKnowledge(ebookId);   // Optional<BookKnowledgeData>
```

`BookKnowledgeData` contains:

- `book`: the brief
- `project`
- `overallSummary`
- `topics`, `processes` (with steps), `examples`
- `importantDetails`, `terminology`
- `userInsights` (problem / decision / mistake / tip…)
- `technicalDetails`, `facts`
- `intendedSequence` (the author's intended order)
- `knowledgeGaps`
- `sources` (every document and whether it was analysed, was a duplicate, or
  was left out)
- `coverage`

Every knowledge item has `sources` (refs).

## Configuration

- Model: `openai.knowledge-model` / `OPENAI_KNOWLEDGE_MODEL`
  (default `gpt-5-mini`).
- Reasoning effort: `OPENAI_KNOWLEDGE_REASONING_EFFORT` (default `low`; leave
  blank for non-reasoning models).
- Prices used for cost estimates: `OPENAI_KNOWLEDGE_INPUT_USD_PER_MILLION` and
  `OPENAI_KNOWLEDGE_OUTPUT_USD_PER_MILLION`.
- Limits: the `knowledge.*` keys in `application.yml` (`KnowledgeProperties`).
- Uses the existing `OPENAI_API_KEY` and `OPENAI_BASE_URL`.
