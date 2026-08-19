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

## Page-count handling

The requested page count is a **soft target**. The planner distributes it
across chapters (`approxPages`), and each chapter is written to a word target
(~450 words/page). V0.1 aims for approximate matching, not exact page control.

## Not in V0.1 (deliberately)

Illustrations/diagrams, image uploads, EPUB, KDP, marketplace, in-app editor,
collaboration, multiple AI providers, analytics, teams, payments, credits.
