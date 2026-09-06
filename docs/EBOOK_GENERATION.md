# Ebook Generation (V0.1)

Turns a single brief into a complete, downloadable PDF ebook using the
Anthropic API. This is a validation build: `user idea → book plan → chapters →
editorial pass → HTML → PDF`. Nothing more.

## Pipeline

```
EbookGenerationService  (async orchestrator, status + progress + error handling)
  ├─ BookPlanningService      Step 1 — outline as structured JSON
  ├─ ChapterGenerationService Step 2 — write each chapter sequentially
  ├─ BookEditingService       Step 3 — editorial pass per chapter
  └─ PdfGenerationService     Step 4 — assemble HTML, render to PDF
```

- Chapters are generated **sequentially**, each aware of the outline and the
  summaries of earlier chapters, so content builds forward without repeating.
- The editorial pass reviews each chapter against the whole book (outline +
  other chapters' summaries) for repetition, contradictions, terminology drift,
  weak transitions, filler, and missing scope — without changing the topic.
- All Claude calls go through one wrapper (`AnthropicService`) that uses the
  official Anthropic Java SDK, adaptive thinking, and a small retry.
- All prompts live in `com.ebookwriter.SaaS.prompt` so they are easy to iterate
  on. Global rules (no filler, no AI mention, no fabricated citations,
  consistent terminology, respect audience/length) are in `PromptGuidelines`.

## Data model

- `Ebook` — the brief, status, progress, plan-derived metadata (title,
  subtitle, description, writing guidelines), timestamps.
- `EbookChapter` — per-chapter outline + content + summary + status. Persisted
  as each chapter is produced, so a failure keeps completed chapters.
- `EbookPdf` — rendered PDF bytes in their own table (keyed by ebook id) so
  status polls and listings never load the blob.

## Status & progress

`PENDING → PLANNING (10%) → WRITING (20–80%) → EDITING (90%) → RENDERING (95%)
→ COMPLETED (100%)`, or `FAILED` with an error message. WRITING progress is
spread evenly across the chapters.

## API

All endpoints require a valid access token (see `docs/AUTH.md`) and are scoped
to the authenticated user.

| Method | Path                          | Purpose |
|--------|-------------------------------|---------|
| POST   | `/api/ebooks`                 | Submit a brief; returns `202` with the ebook id and initial status. |
| GET    | `/api/ebooks/{id}`            | Poll status/progress + per-chapter progress. |
| GET    | `/api/ebooks`                 | List the current user's ebooks. |
| GET    | `/api/ebooks/{id}/content`    | Load the editable manuscript: all chapters + their Markdown bodies. |
| PUT    | `/api/ebooks/{id}/content`    | Save edited chapters, then re-render the PDF (`409` until COMPLETED). |
| GET    | `/api/ebooks/{id}/download`   | Download the finished PDF (`409` until COMPLETED). |

### Request body (`POST /api/ebooks`)

```json
{
  "topic": "Building SaaS Applications with Spring Boot",
  "targetAudience": "Junior Java developers",
  "style": "Practical, technical, easy to understand",
  "approxPageCount": 50,
  "language": "English",
  "additionalInstructions": "Focus on real-world development. Include examples.",
  "sourceMaterial": "(optional examples or source text)"
}
```

Frontend flow: `POST` → get id → poll `GET /api/ebooks/{id}` until
`status = COMPLETED` → `GET /api/ebooks/{id}/download`.

### Editing (`GET`/`PUT /api/ebooks/{id}/content`)

Once a book is `COMPLETED`, the frontend text editor loads the manuscript with
`GET .../content` and saves changes with `PUT .../content`. Chapters are stored
as Markdown, so the editor round-trips Markdown bodies directly. Saving persists
the chapters and re-renders the PDF **uncapped** (no page-budget trim) so the
download always matches the edited text. Editing is free — no credits are
charged or refunded. A book that is still generating returns `409`.

`GET .../content` returns each chapter with a stable `id`. A `PUT` sends the
**authoritative, ordered chapter list** — one save covers every chapter
operation:

- **edit** — an entry with an existing `id` updates that chapter's title/body;
- **add** — an entry with `id: null` creates a new chapter;
- **remove** — an existing chapter whose `id` is absent from the list is deleted;
- **reorder** — each chapter's position in the list becomes its new chapter number.

At least one chapter is required. An `id` that doesn't belong to the book (or a
duplicated `id`) returns `400`.

```json
// PUT /api/ebooks/{id}/content
{
  "chapters": [
    { "id": "b1f9…", "title": "Introduction", "content": "# Introduction\n\nEdited body…" },
    { "id": null,    "title": "New Chapter",  "content": "Fresh content…" }
  ]
}
```

## PDF rendering

Markdown chapters → HTML (commonmark) → normalised XHTML (jsoup) → PDF
(openhtmltopdf). A 6×9" book layout with cover, table of contents, page
numbers, and clean chapter separation. Liberation fonts (Serif/Sans/Mono) are
bundled and embedded so Latin-alphabet languages (Polish, Spanish, German, …)
and code blocks render correctly. Styling lives in
`src/main/resources/pdf/ebook.css`.

## Configuration (environment variables)

| Var | Default | Notes |
|-----|---------|-------|
| `ANTHROPIC_API_KEY` | *(blank)* | Required for generation. App still boots without it; generations fail as FAILED. |
| `ANTHROPIC_MODEL` | `claude-opus-5` | Must be a Claude 4.6+ model (adaptive thinking is used). Set to `claude-sonnet-5` to trade quality for cost/speed. |
| `ANTHROPIC_TIMEOUT_MINUTES` | `15` | Per-call client timeout. |
| `ANTHROPIC_MAX_RETRIES` | `2` | Extra retries on top of the SDK's own. |
| `ANTHROPIC_EDITING_ENABLED` | `true` | Whether to run the Step 3 editorial pass (below). |
| `ANTHROPIC_EDITING_MODEL` | *(blank)* | Model for the editorial pass; blank = same as `ANTHROPIC_MODEL`. Set a cheaper model to reduce cost. |

### Cost & the editorial pass

The editorial pass (Step 3) is a **second full pass over every chapter**, so it
roughly doubles the per-book cost — it is typically the largest line item.
Three ways to run it, cheapest to best:

- **Off** (`ANTHROPIC_EDITING_ENABLED=false`): cheapest. Chapters are still
  written by the main model but you lose cross-chapter consistency cleanup
  (repetition / terminology drift between chapters).
- **Cheaper editing model** (`ANTHROPIC_EDITING_MODEL=claude-sonnet-5`): keeps
  the consistency pass at a fraction of the cost — a good default for
  validation.
- **On, same model**: best quality, highest cost.

## Page-count handling & billing

Pages are money: 1 credit ≈ 1 page, so the page count is a **hard budget**, not
a soft target.

1. **Reserve a budget up front.** On submit we don't charge the raw requested
   count — we reserve `min(requestedPages + tolerance, balance)` credits as a
   hold (`credits.page-budget-tolerance`, default `+20%`). This is the ceiling
   generation may reach, and it can never exceed what the user can pay.
2. **Aim for the requested length, cap at the budget.** Two pages are always
   spent on front matter (cover + table of contents,
   `EbookHtmlBuilder.FRONT_MATTER_PAGES`), so content is sized in *content
   pages* = pages − front matter. The planner is told to **aim for** the
   requested length and that the reserved budget is a **hard maximum**;
   `BookPlanningService` enforces the ceiling regardless, scaling chapter
   `approxPages` down (and dropping extra chapters) so their sum can't exceed it.
3. **Size words to the real layout.** Chapter word targets use
   `WORDS_PER_PAGE ≈ 200` — the number of words that actually fit on a page in
   the 6×9" layout, measured against the real PDF pipeline
   (`WordsPerPageCalibrationTest`). The previous `450` estimate was ~2× too high
   and made every book render 2–3× over its requested length.
4. **Hard-cap the delivered book.** After rendering, the true page count is read
   from the PDF (`PDDocument.getNumberOfPages()`). If it still exceeds the budget
   (the model overshot), trailing content is trimmed and the book re-rendered — a
   cheap, API-free loop — until it fits; trimmed chapters are persisted so the
   stored manuscript matches the PDF.
5. **Bill the real page count.** The hold is trued up: the user is charged for
   exactly the pages produced (clamped to `[1, budget]`) and the unused
   reservation is refunded as a `GENERATION_ADJUSTMENT` ledger entry.
   `Ebook.actualPageCount` records the result.

Together these close the gap where a book overran its requested length — a
5-page request rendering ~18–30 pages — while we billed only the requested
count and ate the difference. We now aim for what the user asked, never deliver
(or generate) past what they reserved, and pay for and charge the same number of
pages.

## Not in V0.1 (deliberately)

Illustrations/diagrams, image uploads, EPUB, KDP, marketplace, in-app editor,
collaboration, multiple AI providers, analytics, teams, payments, credits.
