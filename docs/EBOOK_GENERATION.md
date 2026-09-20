# Ebook Generation (V0.1)

Turns a single brief into a complete, downloadable PDF ebook using the
Anthropic API. This is a validation build: `user idea → book plan → chapters →
editorial pass → HTML → PDF`. Nothing more.

## Pipeline

```
EbookGenerationService  (async orchestrator, status + progress + error handling)
  ├─ BookPlanningService      Step 1   — outline as structured JSON
  ├─ AssetPlacementService    Step 1.5 — place any user-uploaded assets
  ├─ ChapterGenerationService Step 2   — write each chapter sequentially
  ├─ BookEditingService       Step 3   — editorial pass per chapter
  ├─ ImagePlanningService     Step 3.5 — plan AI images (structured JSON)
  ├─ ImageGenerationService   Step 3.6 — generate + store + place them
  └─ PdfGenerationService     Step 4   — assemble HTML, render to PDF
```

- **Structural completeness.** When the brief promises a fixed structure — a
  "7-day plan", a "10-step guide", a "30-day challenge", "5 principles" —
  `StructureRequirement` detects it from the title/topic/instructions and the
  planning prompt insists the outline cover **every** unit (all seven days), never
  a partial subset. `BookPlanningService` logs a loud warning if the clamped plan
  can't hold them, and the chapter writer is told to finish every unit in its
  scope. This closes the "The 7-Day Focus Reset stops at Day 3" class of bug at
  its source (planning) rather than compensating downstream.
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
- After the manuscript is written and edited, the **AI image pipeline** runs
  (see [AI-generated images](#ai-generated-images)). It is best-effort: with no
  OpenAI key, images disabled, or an empty plan, the book passes straight
  through to rendering, and a single image failing never fails the book.

## Data model

- `Ebook` — the brief, status, progress, plan-derived metadata (title,
  subtitle, description, writing guidelines), an optional `authorName` (cover
  byline), timestamps.
- `EbookChapter` — per-chapter outline + content + summary + status. Persisted
  as each chapter is produced, so a failure keeps completed chapters.
  `contentSource` records whether the current text is AI output or a user edit,
  so a future regeneration can preserve manual work.
- `EbookPdf` — rendered PDF bytes in their own table (keyed by ebook id) so
  status polls and listings never load the blob.
- `EbookImage` — a project **asset** (see [Assets](#assets)). Metadata only —
  the bytes live in a private Cloudflare R2 bucket under `storageKey`. Carries a
  `role` (what it is — general/logo/author/product/cover/illustration), a
  `placement` (where it's used — unused/cover/chapter + the chapter), `placedBy`
  (AI or user), a `displayWidthPercent` (resize), content type, dimensions, size,
  and an optional AI `aiDescription`/`tags`. Assets belong to the ebook and
  persist from before generation through editing.

## Status & progress

`DRAFT` (created, assets can be uploaded, nothing generated) → *start* →
`PENDING → PLANNING (10%) → WRITING (20–80%) → EDITING (85%) →
PLANNING_IMAGES (88%) → GENERATING_IMAGES (90–95%) → RENDERING (96%) →
COMPLETED (100%)`, or `FAILED` with an error message. WRITING progress is
spread evenly across the chapters. Planning is followed by a short asset-
placement step (10–20%) when the draft has uploaded assets. The two image
statuses are skipped straight through when the book has no image plan (images
disabled, no OpenAI key, or the planner proposed none).

## API

All endpoints require a valid access token (see `docs/AUTH.md`) and are scoped
to the authenticated user.

| Method | Path                          | Purpose |
|--------|-------------------------------|---------|
| POST   | `/api/ebooks`                 | Create a **draft** (no credits held, not generating); returns `201` with the ebook id and `status: DRAFT`. Upload assets to it, then start. |
| POST   | `/api/ebooks/{id}/start`      | Reserve the credit hold and start generating a draft; returns `202`. `409` if already started. |
| GET    | `/api/ebooks/{id}`            | Poll status/progress + per-chapter progress. |
| GET    | `/api/ebooks`                 | List the current user's ebooks. |
| GET    | `/api/ebooks/{id}/content`    | Load the editable manuscript: all chapters + their Markdown bodies. |
| PUT    | `/api/ebooks/{id}/content`    | Save edited chapters, then re-render the PDF (`409` until COMPLETED). |
| GET    | `/api/ebooks/{id}/download`   | Download the finished PDF (`409` until COMPLETED). |
| POST   | `/api/ebooks/{id}/images`     | Upload an asset (multipart `file`, optional `role`). Returns `201`. Works on a draft or a finished book. |
| GET    | `/api/ebooks/{id}/images`     | List the book's assets (role, placement, usage, dimensions). |
| GET    | `/api/ebooks/{id}/images/{imageId}/raw` | Stream an asset's bytes for preview (bucket is private). |
| PATCH  | `/api/ebooks/{id}/images/{imageId}` | Update editor metadata: `role` and/or `displayWidthPercent` (resize, 1–100). |
| PUT    | `/api/ebooks/{id}/images/{imageId}/cover` | Make this asset the cover (demotes any current cover). |
| DELETE | `/api/ebooks/{id}/images/{imageId}` | Delete an asset (also removes it from storage). |

### Request body (`POST /api/ebooks`)

```json
{
  "topic": "Building SaaS Applications with Spring Boot",
  "targetAudience": "Junior Java developers",
  "style": "Practical, technical, easy to understand",
  "approxPageCount": 50,
  "language": "English",
  "additionalInstructions": "Focus on real-world development. Include examples.",
  "sourceMaterial": "(optional examples or source text)",
  "authorName": "(optional — printed on the cover as a byline)"
}
```

Frontend flow: `POST /api/ebooks` (draft) → optionally upload assets to
`POST .../images` → `POST .../start` → poll `GET /api/ebooks/{id}` until
`status = COMPLETED` → open the editor (`GET/PUT .../content` + the images API)
→ `GET /api/ebooks/{id}/download`.

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

Markdown chapters → **editorial HTML** (`EbookContentRenderer`, see
[Content design system](#content-design-system)) → book scaffolding
(`EbookHtmlBuilder`: cover, table of contents, chapter openers) → normalised
XHTML (jsoup) → PDF (openhtmltopdf). A 6×9" book layout with a composed cover, a
real table of contents (dotted leaders + **actual** page numbers via CSS
`target-counter`, not estimates), intentional chapter-opening pages, subtle
folios, and a semantic component system. Liberation fonts (Serif/Sans/Mono) are
bundled and embedded so Latin-alphabet languages (Polish, Spanish, German, …)
and code blocks render correctly. Styling lives in
`src/main/resources/pdf/ebook.css`.

> **One layout model.** The exact same `EbookHtmlBuilder` + `ebook.css` produce
> both the PDF (`PdfGenerationService`) and the editor preview
> (`EbookPreviewService`), so the preview matches the download 1:1. There is no
> second layout implementation to keep in sync. Constraint: openhtmltopdf
> (Flying Saucer) supports neither CSS custom properties (`var()`) nor flexbox,
> so the stylesheet is written with literal values and box-model / float /
> table layout, and `target-counter` generated content is never floated (it
> NPEs) — the TOC uses a two-column table to right-align page numbers.

## Page-type system (covers & section dividers)

Beyond styling individual paragraphs, the book is composed as a sequence of
**page types** so it reads like a designed publication, not a formatted
document. `DocumentComposer` analyses the generated structure and decides, per
chapter, how its opener is composed — the decision is derived from content, not
hardcoded per topic, and is a pure function shared by preview and PDF.

```
MAIN COVER → CONTENTS → [ CHAPTER/DAY OPENER → BODY ] × N
```

- **Main cover.** A composed, art-directed cover: masthead rule, topic-derived
  kicker, strong title hierarchy, subtitle, optional `authorName` byline, and a
  subtle `SCRIVETTE` imprint. Its **visual concept is generated from the book's
  structure**, never a generic AI stock image: a large ghosted **program numeral**
  (the "7" of a 7-day plan, with a "DAYS"/"STEPS" label) when a fixed structure is
  detected, otherwise the title's **initial as a monogram**. A user-uploaded cover
  image still renders full-bleed with the title on a legibility scrim.
- **Chapter / day-step openers.** A major section can open with a dedicated
  **opener page** — big numeral, eyebrow label ("DAY 01" / "CHAPTER 03"), title,
  an optional duration chip (parsed from the scope, e.g. "10–20 MINUTES"), and a
  one-line statement (the planner's scope sentence) — with the body starting on
  the **next** page. Program units (a chapter titled "Day 3", "Step 2", "Part II")
  are detected and get the day/step treatment; the opener strips the redundant
  "Day 1 —" from the title since the label already carries it.
- **Intelligent selection, not one-divider-per-chapter.** Dedicated opener pages
  cost a page, so `DocumentComposer` only uses them when the book is **substantial**
  (≥ 4 chapters averaging ≥ 2 pages, or a ≥ 3-unit program) **and** the reserved
  page budget has genuine slack for them (`canAfford`). Otherwise — a short book, a
  tight budget — it emits a strong **opener band** at the top of the content page:
  the same hierarchy and label with no extra page. So hierarchy and pacing improve
  without padding page count or eating into content the trim would remove. A live
  preview (no budget yet) shows full-page openers so the feature is visible.

Everything flows through the one `EbookHtmlBuilder` + `ebook.css`, so opener pages
and the cover render identically in the editor preview and the PDF. Unit-tested in
`DocumentComposerTest` / `EbookHtmlBuilderTest`.

## Content design system

The step that moves the output from "AI text in a PDF" toward "a designed book".
On top of ordinary Markdown, chapter bodies may contain lightweight
**directive blocks** that `EbookContentRenderer` turns into distinct, reusable,
styled components — the same in the preview and the PDF:

```
:::key-idea        one crucial insight            :::warning     a caution / mistake
:::takeaway        a section summary              :::example     a worked example
:::pullquote       a large editorial quotation    :::exercise T  a reader task
:::done-when       a completion criterion         :::checklist T a verify list
:::steps Day 1     a numbered action plan (01, 02, …) with title | duration + description
:::flow            a process / cycle diagram, one node per line
```

- **Diagrams are drawn, not imaged.** `:::flow` (process/cycle/sequence) and
  `:::steps` (action plan) are rendered with our own typography — crisp text,
  on-brand, no AI spelling mistakes, editable, accessible. The image planner is
  explicitly told **not** to propose image-model diagrams/charts for anything
  that is boxes-and-arrows or labelled steps; those belong to these components.
- **Auto-detection.** A plain paragraph beginning `Done when:` is promoted to the
  `done-when` component automatically (capturing the whole paragraph), so even
  content that doesn't use the directive syntax still gets the treatment.
- **Safe by construction.** The transform is pure (Markdown in, HTML out), unknown
  block names degrade to a generic note, malformed/unclosed blocks never throw
  (they fall back to plain Markdown), and inline image tokens pass through
  untouched. Unit-tested in `EbookContentRendererTest`.
- **The writer emits them sparingly.** `ChapterPrompts` teaches the model the
  block syntax and — importantly — to use it only where content genuinely is that
  kind of thing. Most content stays ordinary prose; over-use looks cluttered.

Component styling has a consistent visual weight per type (`ebook.css`,
`.cmp--*`), and every component sets `page-break-inside: avoid` so it is never
split awkwardly across a page.

## Assets

Assets are the project's persistent images. They are uploaded **before**
generation (so the AI can use them) and remain available **after** it (so the
editor can add, replace, move, resize and remove them). An asset is never forced
into the book — irrelevant ones simply stay unused.

- **Storage.** Bytes live in a **private** Cloudflare R2 bucket (R2 speaks the S3
  API, so we use the AWS SDK v2 pointed at the account endpoint). The database
  keeps only metadata (`EbookImage`). Because the bucket is private, previews are
  served through the authenticated `.../images/{id}/raw` endpoint (`nosniff`), not
  a public URL.
- **Upload + validation.** `POST /api/ebooks/{id}/images` (multipart `file`).
  PNG, JPEG, WebP, GIF and SVG are accepted, up to 10 MB. The type is decided from
  the file's **magic bytes**, never the client's declared MIME. Uploads are
  ownership-scoped to the ebook's owner. Role is not forced at upload —
  everything starts `GENERAL`/unused.
- **AI asset usage (Step 1.5).** After planning, `AssetPlacementService` shows the
  outline and the asset list to the model, which decides per asset: **cover**,
  a **specific chapter**, or **unused** — plus a role, short description and tags.
  It prefers a suitable user asset over an empty spot and forces nothing. The
  cover asset is placed as the cover; chapter-assigned assets are offered to that
  chapter's writer, which embeds the `ebook-image:<id>` token where it fits (or
  not). This step is best-effort: if it fails the book is still produced, just
  without AI placement. (When the generation system can create images, an unfilled
  spot is where a generated illustration would go — future work.)
- **Cover.** The asset with `placement = COVER` renders at the top of the cover
  page. `PUT .../images/{id}/cover` sets it (demoting any previous cover).
- **Inline placement is the Markdown.** An asset placed in a chapter appears where
  its `ebook-image:<id>` token (the DTO's `markdownRef`) sits in that chapter's
  Markdown — the Markdown is the source of truth for in-chapter position. At
  render time every reference is rewritten and the bytes are **streamed straight
  from R2 into the PDF** (like the embedded fonts); `displayWidthPercent` is
  applied as the image width. A reference to a deleted asset is dropped rather
  than rendered broken.
- **Usage stays accurate.** After generation and after every editor save,
  `AssetUsageService` re-derives each asset's chapter placement from the Markdown,
  attributing the change to AI or user. So the asset library always reflects where
  images really are, however the manuscript was edited.

## AI-generated images

After the manuscript is written and edited, the pipeline can add AI-generated
illustrations. This is a separate concern from uploaded **assets**: the content
AI writes the book, the **image planner** decides what visuals are needed and
where, the **image generator** creates them, and the existing layout/render path
places them. No one prompt does all of these.

```
CONTENT (chapters)
      ↓
ImagePlanningService   decides what/where/why/how  → List<ImagePlan>  (validated)
      ↓
ImageGenerationService for each plan …
      ├─ OpenAiImageClient   call the OpenAI Image API → PNG bytes
      ├─ R2StorageService    store the bytes (private bucket)
      ├─ EbookImage          persist the generated image (metadata)
      └─ ImagePlacementService  insert its ebook-image:<id> token in the chapter
```

- **Planner (`ImagePlanningService`).** Shows the book (title, topic, audience,
  style, language, chapters + content excerpts) to the content model and asks,
  as **strict JSON**, which images add value. The rules are conservative:
  relevance over coverage, no decorative or near-duplicate images, no image just
  because a chapter exists, anchor each to the section it supports, keep the book
  visually consistent, respect language/audience. Output is a validated
  `List<ImagePlan>` — never free-form text. The model's JSON is parsed leniently
  and every entry is validated (a real chapter, a non-blank prompt); malformed
  entries are dropped and malformed output yields an empty plan. Counts are
  capped by `openai.max-images-per-book` / `-per-chapter`, ranked by the plan's
  `priority`.
- **`ImagePlan` vs. `GeneratedImage`.** The plan (`dto.image.ImagePlan`) is
  *what should exist* — id, chapter, anchor heading, type (illustration / diagram
  / chart / photo), purpose, description, generation prompt, aspect ratio,
  priority. The generated file is an **`EbookImage`** (bytes in R2), reusing the
  same asset storage/rendering as uploads. Planning data is never mixed into the
  file model.
- **Generator (`ImageGenerationService`).** For each plan it calls the OpenAI
  Image API through `OpenAiImageClient` (the only class that knows about OpenAI),
  stores the PNG in R2, records an `EbookImage` (`placedBy = AI`, role
  `ILLUSTRATION`), and places its inline token. **Failures are isolated per
  image**: one image failing is logged and skipped; the rest, and the book, still
  finish.
- **Placement is semantic, not physical.** The planner names a chapter and an
  optional section heading; `ImagePlacementService` turns that into an
  `![alt](ebook-image:<id>)` token in the chapter Markdown (after the heading, or
  after the chapter's opening block if the heading isn't found). The AI never
  picks pixel coordinates.
- **Editor ≈ PDF, for free.** Because the token lives in the chapter Markdown —
  the single source of truth — a generated image flows through the *same*
  `EbookHtmlBuilder` used by both the editor preview (`EbookPreviewService`,
  inlined as `data:` URIs) and the PDF (`PdfGenerationService`, streamed from R2).
  No separate placement algorithm for the PDF. `AssetUsageService` reconciles
  usage from the Markdown afterwards, so the asset library shows the generated
  images like any other.

Provider-specific logic is isolated in `OpenAiImageClient`; the aspect ratio the
planner chooses (`1:1` / `3:2` / `2:3`) is mapped to a concrete OpenAI size
there. The image model is configurable (`openai.image-model`); nothing hardcodes
the key or the model.

## The editor (post-generation)

Once a book is `COMPLETED` it can be edited without regenerating. Text and
structure use the editing contract above (`GET/PUT .../content`:
edit/add/remove/reorder chapters, saved as the authoritative list). Image
operations reuse the assets API:

- **add / insert** — upload (`POST .../images`) or pick an existing asset, then
  place its `markdownRef` token in a chapter via a content save.
- **replace** — swap one asset's token for another's in the Markdown.
- **move** — move the token (within or across chapters); usage re-syncs.
- **remove** — delete the token (asset stays in the library) or delete the asset
  (`DELETE .../images/{id}`).
- **resize** — `PATCH .../images/{id}` with `displayWidthPercent` (1–100).
- **cover** — `PUT .../images/{id}/cover`.

Every change is persisted server-side (no purely client-side state) and, on a
`COMPLETED` book, re-renders the PDF so the download stays in sync. User edits
set `contentSource = USER` / `placedBy = USER`, marking them intentional so a
future regeneration can preserve them.

> PDF embedding uses PDFBox/ImageIO, which decodes PNG, JPEG and GIF reliably.
> WebP and SVG can be uploaded and previewed but may not embed in the PDF
> (WebP depends on the JDK's ImageIO plugins; SVG-in-PDF needs an extra renderer
> module not yet added). Prefer PNG/JPEG for images that must appear in the
> download.

## Configuration (environment variables)

| Var | Default | Notes |
|-----|---------|-------|
| `ANTHROPIC_API_KEY` | *(blank)* | Required for generation. App still boots without it; generations fail as FAILED. |
| `ANTHROPIC_MODEL` | `claude-opus-5` | Must be a Claude 4.6+ model (adaptive thinking is used). Set to `claude-sonnet-5` to trade quality for cost/speed. |
| `ANTHROPIC_TIMEOUT_MINUTES` | `15` | Per-call client timeout. |
| `ANTHROPIC_MAX_RETRIES` | `2` | Extra retries on top of the SDK's own. |
| `ANTHROPIC_EDITING_ENABLED` | `true` | Whether to run the Step 3 editorial pass (below). |
| `ANTHROPIC_EDITING_MODEL` | *(blank)* | Model for the editorial pass; blank = same as `ANTHROPIC_MODEL`. Set a cheaper model to reduce cost. |
| `R2_ACCOUNT_ID` | *(blank)* | Cloudflare account id; used to derive the R2 endpoint. |
| `R2_ACCESS_KEY_ID` | *(blank)* | R2 access key id (R2 API token). |
| `R2_SECRET_ACCESS_KEY` | *(blank)* | R2 secret access key. |
| `R2_BUCKET` | *(blank)* | Bucket that holds ebook images. |
| `R2_ENDPOINT` | *(derived)* | Override the S3 endpoint; blank = `https://<R2_ACCOUNT_ID>.r2.cloudflarestorage.com`. |
| `R2_AUTO_CREATE_BUCKET` | `false` | Create the bucket at startup if missing. Needs a token allowed to create buckets. |
| `OPENAI_API_KEY` | *(blank)* | Enables AI image generation. App boots without it; books are produced text-only until it is set. Requires the `R2_*` vars too (generated images are stored in R2). |
| `OPENAI_IMAGE_MODEL` | `gpt-image-1` | Image model. Must return base64 image data (`b64_json`). |
| `OPENAI_BASE_URL` | `https://api.openai.com/v1` | API base URL (override for a proxy/compatible gateway). |
| `OPENAI_IMAGES_ENABLED` | `true` | Master switch for the whole image pipeline (planner + generator). |
| `OPENAI_MAX_IMAGES_PER_BOOK` | `6` | Hard ceiling on generated images per book. |
| `OPENAI_MAX_IMAGES_PER_CHAPTER` | `2` | Hard ceiling on generated images per chapter. |
| `OPENAI_READ_TIMEOUT_MS` | `120000` | Per-call read timeout (image generation is slow). |
| `OPENAI_CONNECT_TIMEOUT_MS` | `10000` | Per-call connect timeout. |
| `OPENAI_MAX_RETRIES` | `2` | Retries on a failed image call before that one image is given up. |

The app boots without R2 configured (like the Anthropic/Stripe placeholders);
only image upload and image rendering fail with a clear message until the
`R2_*` variables are set. Create an R2 bucket and an API token (Object
Read & Write) in the Cloudflare dashboard, then set the four `R2_*` values.

**The bucket must already exist.** `R2_BUCKET` has to name a bucket that exists
in the account `R2_ACCOUNT_ID` points to — names are lowercase and case-
sensitive. If it doesn't, uploads fail with *"The specified bucket does not
exist."* The app checks this at startup (`R2StartupCheck`) and logs an explicit
message; set `R2_AUTO_CREATE_BUCKET=true` to have it created on boot (only works
if the R2 token may create buckets — an object-scoped token cannot, so create it
in the dashboard instead).

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

**1 credit = 1 final generated page.** The AI cannot guarantee an exact page
count — ask for 15 and the finished book might be 13, 17, or 22 — so the
requested count is only a **target length**, never the price. The real cost is
read back from the rendered PDF, and the user pays for the pages they actually
got. We never tell the user "15 pages = 15 credits", because we can't promise
exactly 15 pages.

To make the real length payable without a runaway bill, the balance may go a
little negative: a small **overdraft**, capped centrally at
`credits.max-overdraft` (`CREDITS_MAX_OVERDRAFT`, default **10**). The lowest a
balance can ever reach from generation is `-10`.

1. **Reserve an overdraft-aware ceiling up front.** A draft holds no credits. On
   **start** (`CreditService.reserveGenerationHold`) we don't charge the target —
   we reserve `min(targetPages, balance) + maxOverdraft` credits as a hold. This
   ceiling (a) lets a book run up to `maxOverdraft` pages past what the user can
   strictly afford, honouring a natural ending a little over target; (b) is
   target-bounded, so a large balance isn't drained by one book; and (c) can never
   drive the balance below `-maxOverdraft` (`balance − hold ≥ −maxOverdraft`). The
   only precondition to start is holding **at least one credit** — we no longer
   reject just because the balance is below the requested target. The DRAFT →
   PENDING transition is claimed atomically (`EbookRepository.claimForStart`) so a
   double-click / retry / refresh can never reserve two holds.
2. **Aim for the target, cap at the ceiling.** Two pages are always spent on front
   matter (cover + table of contents, `EbookHtmlBuilder.FRONT_MATTER_PAGES`), so
   content is sized in *content pages* = pages − front matter. The planner is told
   to **aim for** the target length; `BookPlanningService` enforces the reserved
   ceiling as a hard maximum, scaling chapter `approxPages` down (and dropping
   extra chapters) so their sum can't exceed it.
3. **Size words to the real layout.** Chapter word targets use
   `WORDS_PER_PAGE ≈ 200` — the number of words that actually fit on a page in
   the 6×9" layout, measured against the real PDF pipeline
   (`WordsPerPageCalibrationTest`). This keeps the natural length close to the
   target so the overshoot the ceiling has to trim is small.
4. **Stop a runaway at the ceiling.** After rendering, the true page count is read
   from the PDF (`PDDocument.getNumberOfPages()`). A natural ending above the
   target renders in full. Only if the book exceeds the reserved ceiling (a genuine
   runaway that would breach the overdraft floor) is trailing content trimmed — at
   a **paragraph boundary**, never mid-sentence — and the book re-rendered, a
   cheap API-free loop, until it fits; trimmed chapters are persisted so the stored
   manuscript matches the PDF.
5. **Bill the real page count, idempotently.** The hold is trued up
   (`EbookGenerationService.reconcileCredits`): the user is charged for exactly the
   pages rendered (clamped to `[1, ceiling]`) and the unused reservation is refunded
   as a `GENERATION_ADJUSTMENT` ledger entry, leaving the balance at
   `balanceAtStart − actualPages`. `Ebook.actualPageCount` records the result. The
   true-up is claimed atomically (`EbookRepository.markReconciled`), so a retried
   worker or re-run generation is a no-op — credits are never charged twice for the
   same ebook. A generation that **fails before a PDF exists** refunds the whole
   hold (no real pages ⇒ no charge), guarded by `creditsRefunded` /
   `creditsReconciled` so a refund never stacks with the true-up.

**Worked examples** (`maxOverdraft = 10`):

| Balance | Target | Final pages | Charged | Balance after |
|--------:|-------:|------------:|--------:|--------------:|
| 100 | 15 | 13 | 13 | 87 |
| 100 | 15 | 15 | 15 | 85 |
| 15 | 15 | 20 | 20 | −5 |
| 15 | 15 | 25 | 25 | −10 |
| 15 | 15 | (would be 30) | 25 | −10 *(trimmed to the ceiling)* |

> **Structure vs. the trim.** The trim removes trailing content to fit the
> ceiling, so a promised structure must be *planned* to fit — that is why
> `StructureRequirement` steers the outline and word sizing up front (step 2/3).
> With a right-sized plan the trim rarely fires; when it does it only shaves a few
> trailing paragraphs. A book whose promised structure genuinely cannot fit is
> surfaced as a planning warning rather than silently delivered half-finished.

## Not yet (deliberately)

AI image generation/replacement from **inside the editor** (on-demand, not the
generation-time pipeline that now exists), AI-generated **covers**, custom
fonts, brand colours, background removal, PDF/DOCX source materials, reusable
cross-project asset libraries, templates, EPUB, KDP, marketplace, collaboration,
multiple AI providers, analytics, teams. The data model (assets as project
resources, `placedBy`/`contentSource` origin tracking) is built to accommodate
these without a rewrite.

> AI-generated **inline** images at generation time are now implemented — see
> [AI-generated images](#ai-generated-images).
