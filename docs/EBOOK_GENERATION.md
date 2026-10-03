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
  ├─ CoverGenerationService   Step 3.7 — plan + generate the editable cover visual
  ├─ PdfGenerationService     Step 4   — assemble HTML, render to PDF
  └─ EbookValidationService   Step 4.5 — final quality gate before COMPLETED
```

- **Structural completeness.** When the brief promises a fixed structure — a
  "7-day plan", a "10-step guide", a "30-day challenge", "5 principles" —
  `StructureRequirement` detects it from the title/topic/instructions, the scope
  estimate gives every unit room, and the planning prompt insists the outline
  cover **every** unit (all seven days), never a partial subset. The chapter
  writer is told to finish every unit in its scope. This closes the "The 7-Day Focus Reset stops at Day 3" class of bug at
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
  consistent terminology, let the length follow the material) are in `PromptGuidelines`.
- After the manuscript is written and edited, the **AI image pipeline** runs
  (see [AI-generated images](#ai-generated-images)). It is best-effort: with no
  OpenAI key, images disabled, or an empty plan, the book passes straight
  through to rendering, and a single image failing never fails the book.

## Data model

- `Ebook` — the brief, status, progress, plan-derived metadata (title,
  subtitle, description, writing guidelines), an optional `authorName` (cover
  byline), the chosen `coverLayout`, timestamps.
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
  (AI or user), a `displayWidthPercent` (resize), an optional crop focal point
  (`focalX`/`focalY`), content type, dimensions, size,
  and an optional AI `aiDescription`/`tags`. Assets belong to the ebook and
  persist from before generation through editing.

## Status & progress

`DRAFT` (created, assets can be uploaded, nothing generated) → *start* →
`PENDING → PLANNING (10%) → WRITING (20–80%) → EDITING (85%) →
PLANNING_IMAGES (88%) → GENERATING_IMAGES (90–95%) → RENDERING (96%) →
COMPLETED (100%)`, or `FAILED` with an error message. Before `COMPLETED`, a final
validation gate (`EbookValidationService`) can move a genuinely broken render to
`FAILED` (and refund the hold) rather than publishing it. WRITING progress is
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
| GET    | `/api/ebooks/generation-budget?briefChars=&sourceChars=` | Creation form: each depth with a preliminary length/credit estimate, plus the balance. |
| GET    | `/api/ebooks/{id}/scope`      | A draft's length + credit estimate for its depth (and the other depths), `requiredCredits`, `canGenerate`. |
| POST   | `/api/ebooks/{id}/scope/assess` | Have OpenAI assess the draft's scope (cached until brief/materials/blueprint change); returns the refined scope. |
| POST   | `/api/ebooks/{id}/scope-decision` | Answer a book paused in `AWAITING_APPROVAL`: `{"decision": "CONTINUE" \| "FIT" \| "CANCEL"}`. `402` if continuing needs more credits than the balance; `CANCEL` only after planning. |
| PUT    | `/api/ebooks/{id}/depth`      | Change a draft's depth (`{"depth": "QUICK|STANDARD|COMPREHENSIVE"}`); returns the new scope. `409` once started. |
| POST   | `/api/ebooks/{id}/start`      | Estimate the scope, require credits for it, reserve the credit hold and start; returns `202`. `402` (with `required`/`available`/`message`) if the credits don't cover the estimate; `409` if already started. |
| GET    | `/api/ebooks/{id}`            | Poll status/progress + per-chapter progress. |
| GET    | `/api/ebooks`                 | List the current user's ebooks. |
| GET    | `/api/ebooks/{id}/content`    | Load the editable manuscript: all chapters + their Markdown bodies. |
| PUT    | `/api/ebooks/{id}/content`    | Save edited chapters, then re-render the PDF (`409` until COMPLETED). |
| GET    | `/api/ebooks/{id}/download`   | Download the finished PDF (`409` until COMPLETED). |
| POST   | `/api/ebooks/{id}/images`     | Upload an asset (multipart `file`, optional `role`). Returns `201`. Works on a draft or a finished book. |
| GET    | `/api/ebooks/{id}/images`     | List the book's assets (role, placement, usage, dimensions). |
| GET    | `/api/ebooks/{id}/images/{imageId}/raw` | Stream an asset's bytes for preview (bucket is private). |
| PATCH  | `/api/ebooks/{id}/images/{imageId}` | Update editor metadata: `role`, `displayWidthPercent` (resize, 1–100) and/or `focalX`/`focalY` (crop focal point, 0–100). |
| PUT    | `/api/ebooks/{id}/images/{imageId}/cover` | Make this asset the cover (demotes any current cover). |
| POST   | `/api/ebooks/{id}/images/cover/regenerate` | Regenerate the AI cover visual, keeping title/subtitle/layout. |
| DELETE | `/api/ebooks/{id}/images/{imageId}` | Delete an asset (also removes it from storage). |

### Request body (`POST /api/ebooks`)

```json
{
  "topic": "Building SaaS Applications with Spring Boot",
  "targetAudience": "Junior Java developers",
  "style": "Practical, technical, easy to understand",
  "depth": "STANDARD",
  "language": "English",
  "additionalInstructions": "Focus on real-world development. Include examples.",
  "sourceMaterial": "(optional examples or source text)",
  "authorName": "(optional — printed on the cover as a byline)"
}
```

`depth` is the user's only control over scope: `QUICK`, `STANDARD` (default) or
`COMPREHENSIVE`. There is **no page-count input** — Scrivetta determines the
length from the topic, the materials and the depth (see
[Depth, scope & credits](#depth-scope--credits)); a `targetPages` sent by an
older client is ignored. The status response returns `depth`, the
`estimatedPagesLow/High` taken at start, the `plannedPages` once planned, the
real `actualPageCount`, and `creditLimited` when the credits ended the book early.

Frontend flow: `POST /api/ebooks` (draft) → optionally upload materials /
assets → `GET .../scope` (estimate; `PUT .../depth` to change it) → `POST .../start` → poll `GET /api/ebooks/{id}` until
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

- **Main cover.** A composed, art-directed cover whose elements stay **separate
  and editable** — the AI-generated (text-free) visual is an image asset, and the
  title, subtitle, `authorName` byline and `SCRIVETTA` imprint are real typography
  rendered by Scrivetta (never baked into the image). See
  [AI editable cover](#ai-editable-cover). When no visual is present the cover
  falls back to a composed **typographic** cover with a topic kicker and a
  structural motif (a large program numeral like "7 DAYS", or the title's
  monogram).
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
  credit ceiling has genuine slack for them (`canAfford`). Otherwise — a short book, a
  tight budget — it emits a strong **opener band** at the top of the content page:
  the same hierarchy and label with no extra page. So hierarchy and pacing improve
  without padding page count or eating into content the trim would remove. A live
  preview (no budget yet) shows full-page openers so the feature is visible.

Everything flows through the one `EbookHtmlBuilder` + `ebook.css`, so opener pages
and the cover render identically in the editor preview and the PDF. Unit-tested in
`DocumentComposerTest` / `EbookHtmlBuilderTest`.

## AI editable cover

The cover is **not** a flattened AI image. The AI generates only the *visual*;
Scrivetta composes the editable publication around it:

```
CoverPlanningService  (art director: analyse book → layout + text-free prompt)
        ↓
CoverGenerationService  (OpenAiImageClient → R2 → EbookImage, placement=COVER)
        ↓
EbookHtmlBuilder + ebook.css  (compose: image asset + real title/subtitle text)
        ↓
editor preview  ==  PDF output
```

- **The visual is text-free.** `CoverPlanningService` asks the content model, as an
  art director, to analyse the book (title, subtitle, topic, audience, tone,
  chapter concepts) and return strict JSON: a chosen **layout** and a concrete
  **image prompt** for the visual only. `CoverPrompts.IMAGE_CONSTRAINTS` is always
  appended in code, so the request forbids any text, letters, numbers, logos,
  watermarks, UI or mockups — a hard guarantee even if the model forgets. The
  title/subtitle/author are **never** sent to the image model; Scrivetta renders
  them as real, editable text on top.
- **The prompt is topic-aware and safe-area-aware.** It is generated from the
  actual book (no generic "make a nice image"), and the chosen layout tells the
  model which region to leave as clean negative space for the title. A
  deterministic, topic-derived fallback prompt is used when the art-director model
  is unavailable, so a cover can always be composed.
- **Cover layout system (`CoverLayout`).** Five internal composition variants —
  `EDITORIAL` (large visual above, title below on paper), `IMAGE_LED` (full-bleed
  visual, title over a scrim), `SPLIT` (visual + text in distinct regions),
  `MINIMAL` (small framed visual, strong type), `TYPOGRAPHIC` (no image). The
  planner picks one per book; the architecture supports many compositions without
  hardcoding a single cover.
- **Layout-aware image shape (no stretching, minimal crop).** Each visual layout
  fixes the **aspect ratio of its image region** (`CoverLayout.imageAspectRatio()`):
  `IMAGE_LED` is full-bleed portrait (2:3), the partial-region layouts are
  landscape (3:2). `CoverGenerationService` generates the visual **at that exact
  ratio** — the image is prepared *for* the composition rather than generated as a
  generic square and squashed to fit — and the matching CSS regions in `ebook.css`
  are sized to the same ratio. openhtmltopdf does **not** honour `object-fit`
  (verified by the rendered-PDF QA), so any cover whose shape differs from its
  region — e.g. a user upload — is cropped to the region's exact ratio around its
  focal point by `CoverImageFitter` before drawing, in both the PDF and the preview.
  An already-fitted AI cover is passed through byte-for-byte. The art-director prompt describes the target shape per layout; the
  model no longer picks pixel dimensions. The generated PNG is stored and embedded
  in the PDF **without any resize or recompression**, preserving quality.
- **Separate, editable elements.** The visual is a normal `EbookImage`
  (`placement=COVER`, `placedBy=AI`) — so the whole existing asset API already
  gives the editor **replace** (`POST …/images` + `PUT …/images/{id}/cover`),
  **upload**, **resize/reposition** (`PATCH …/images/{id}`) and **remove**
  (`DELETE …/images/{id}`) for free, with no duplicate infrastructure. The
  title/subtitle are edited through the normal content model.
- **Focal point & quality.** `PATCH …/images/{id}` accepts `focalX`/`focalY`
  (0–100% of the image). When an asset's shape differs from its region (e.g. a
  square user upload in a portrait cover), `CoverImageFitter` crops it at full
  resolution to the region's ratio around that point (never stretched; the stored
  asset is untouched) — the same crop in preview and PDF. The cover visual is requested at
  `openai.cover-quality` (default `high`); PNG bytes are embedded losslessly, with
  no resize or recompression.
- **Regenerate.** `POST /api/ebooks/{id}/images/cover/regenerate` re-generates just
  the visual — keeping the title, subtitle, layout and the rest of the book — and
  replaces the previous AI cover (a user-uploaded cover is demoted, not deleted).
  On a `COMPLETED` book it re-renders the PDF so the download matches.
- **Validation / never-broken.** A visual layout with no image downgrades to the
  safe `TYPOGRAPHIC` cover at render time (`EbookHtmlBuilder.resolveLayout`), so a
  missing or failed visual yields a clean composed cover, never a broken one.
  Generation is best-effort in the pipeline (no OpenAI key / a failure ⇒
  typographic cover, book still ships); the editor-triggered regenerate surfaces a
  clear error instead, since the user explicitly asked for a visual.
- **Same model, editor and PDF.** The cover is an ordinary first page of the shared
  `EbookHtmlBuilder` + `ebook.css` layout — no PDF-only cover hack — so the editor
  preview and the exported PDF show the identical composition. Unit-tested in
  `CoverPlanningServiceTest` / `CoverLayoutTest` / `EbookHtmlBuilderTest`; no new
  env vars (it reuses the `OPENAI_*` image pipeline and `R2_*` storage).

## Pagination & final page

- **Semantic units stay together** (`EbookContentRenderer.paginate`, shared by
  preview and PDF): an image on its own line becomes a `<figure>` with its caption
  inside (captions can't separate from images; generic/overlong alt text isn't
  printed); a section heading is grouped with the block it introduces (paragraph,
  list, component, modest table/figure) so it can't be stranded at a page foot —
  a very long paragraph is left free so a page isn't pushed forward for one
  heading. Components, code, figures and table rows avoid page-internal breaks;
  table headers repeat on continuation pages.
- **No near-empty last page.** After rendering, if the final page holds only a
  spilled line or two (and no image), the renderer re-renders with the final
  chapter set slightly tighter (`chapter--snug`) and keeps it only if the page
  disappears. The decision is stored on the book (`layoutSnugEnding`) so the
  editor preview uses the identical layout.
- **Opener pages are intentional.** Chapter/day openers are sparse by design and
  are never treated as defects.
- **Deferred chapters** are excluded from the TOC, the body, the editor content,
  and image/cover planning. (An editor save sends the authoritative chapter list,
  so saving drops them.)

## Final validation (quality gate)

Before a rendered book is published as `COMPLETED`, `EbookValidationService`
inspects its final state (`inspect()` is a pure function of the book, chapters,
images and the true rendered page count, so it is unit-tested without a DB or
renderer):

- **Fatal** (raises `EbookValidationException` ⇒ book → `FAILED`, hold refunded):
  a render with no pages, or a book with no rendered chapter content. Deliberately
  narrow, so a legitimately-shaped book is never failed on a cosmetic issue.
- **Warning** (logged, never fatal): a visual cover layout with no placed cover
  image (the builder still downgrades it to a typographic cover), a cover asset
  whose real aspect ratio drifts from its layout region ratio beyond tolerance
  (a stale/wrong asset that would crop heavily), or a placed image with no
  storage key.

- **Ending** (warning): the final chapter ends mid-sentence, or its last paragraph
  refers to content that doesn't follow ("in the next chapter…").
- **Rendered-PDF QA** (`PdfQualityInspector`, over the exact bytes delivered): an
  unreadable/page-less PDF is **fatal**; warnings for pages not at the 6×9" trim,
  empty pages, pages holding only a stray token, a sparse final page, non-embedded
  fonts, fewer images drawn than placed (missing/broken), images drawn distorted
  (drawn vs intrinsic aspect), under-resolved (< 110 dpi) or extremely compressed
  JPEGs, glyphs outside the page (overflow/clipping), and **TOC page numbers that
  don't match** the page each entry links to.

The gate runs **after** rendering and **before** credit reconciliation, so a
genuinely broken book fails and the full hold is refunded rather than a broken
book silently becoming a finished product. Unit-tested in
`EbookValidationServiceTest`.

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
| `OPENAI_COVER_QUALITY` | `high` | `quality` sent for the cover visual (gpt-image: `low\|medium\|high\|auto`; blank = provider default). |
| `CREDITS_MAX_GENERATION_BUDGET` | `400` | Per-book safety maximum (pages): estimates and plans are capped at it and the hold is `min(balance, this) + overdraft`. Not a user-facing length. |
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

## Depth, scope & credits

**The page count is a result, never an input.** The user chooses a *depth*;
Scrivetta analyses the scope and determines the length:

```
USER chooses depth ─┐
brief + materials ──┼─▶ ScopeEstimator ─▶ estimate (pages/chapters/credits, a RANGE)
blueprint (if any) ─┘          │
                               ▼
               start: credits ≥ estimate high?  ── no ─▶ 402 "needs up to N credits"
                               │ yes
                               ▼
        plan (Claude, or blueprint sizing) at that depth ─▶ plannedPages
                               │
      plan clearly > agreed length (+10%) or > credit hold?
                               │ yes ─▶ AWAITING_APPROVAL: continue / keep within agreed length / cancel
                               ▼ no
     write each chapter ── projection > agreed (+15%) or credits short? ─▶ AWAITING_APPROVAL
                               ▼
              edit ─▶ images ─▶ render ─▶ actualPageCount ─▶ bill the real pages
```

| Depth | What changes |
|---|---|
| `QUICK` | essentials only, few side topics, one strong example, short explanations, selective use of materials |
| `STANDARD` | full practical treatment, worked examples, normal depth |
| `COMPREHENSIVE` | broad scope, thorough explanations, several examples and edge cases, extensive use of materials — never dropping important content to save pages, never padding |

Depth is applied in **every** stage: the estimate, the planning / blueprint
prompts (`BookDepth.plannerGuidance`), the blueprint chapter sizing, each
chapter prompt and the editorial pass (`BookDepth.writerGuidance`).

1. **Estimation (`ScopeEstimator`, `ScopeEstimationService`).** Not
   `pages = sourcePages × k`. It weighs, from weakest to strongest: the brief
   (depth sets a typical scope; a rich brief or a promised N-unit structure widens
   it), the **source volume** with diminishing returns (`T·ln(1+p/T)` — the first
   pages count ~1:1, whole repositories taper), the **distinct knowledge** extracted
   from the materials (topics, processes, examples, technical details, insights),
   and the **blueprint** (each chapter sized by the knowledge it carries). For a
   knowledge-based book the materials define the scope — 5 pages of notes at
   Comprehensive give a modest book, 150 pages of material are never summarised
   into 30. The result is a range (wider with less information) capped at the
   per-book safety maximum, with a `basis` (`BRIEF`, `SOURCE_TEXT`, `KNOWLEDGE`,
   `BLUEPRINT`). Unit-tested in `ScopeEstimatorTest`.
2. **Credits before generation.** `start` requires a balance of at least the
   estimate's **high end** (1 credit ≈ 1 page). Below it, `402` with a message such
   as *"At Comprehensive depth, Scrivetta estimates this book at about 120–160
   pages, so it needs up to 160 credits to generate — you have 90."* A book is
   never planned smaller to fit the balance.
3. **Planning follows the content.** The legacy planner gets the depth and the
   estimate explicitly marked *"not a target, not a limit"*; it sets each chapter's
   `approxPages` to what that chapter needs, and the sum is `plannedPages`. The
   only adjustments are system guards, never user input: a **runaway** guard (a
   plan over 2× the estimate's high end is scaled toward 1.5×, no chapter dropped)
   and the **per-book safety maximum**. Knowledge-based books size each blueprint
   chapter by its knowledge at the depth (`KnowledgeBookPlanner.size`).
4. **AI scope assessment (OpenAI, bounded).** `ScopeEstimationService.assess` asks
   OpenAI (`ScopePrompts`, blueprint model, low reasoning effort) how many pages a
   complete book needs at each depth, given the brief and a compact view of the
   materials (sizes, knowledge counts and topics, blueprint chapters). It is told to
   be conservative (200+ pages only with genuinely that much material). Its answer is
   cached on the ebook keyed by a fingerprint of the inputs and **bounded in code**
   (`ScopeEstimator.combine`): it may move the material-based estimate only within
   0.6×–1.8×, never past the depth cap (`QUICK` 90, `STANDARD` 250,
   `COMPREHENSIVE` 400 pages) or the per-book maximum. The draft page calls
   `POST /scope/assess`; `start` calls it too (best-effort). Without OpenAI the
   material-based estimate stands.
5. **The user agrees to a length; the book never grows or shrinks silently.**
   Starting sets `approvedPages` = the estimate's high end. Generation pauses in
   `AWAITING_APPROVAL` (`ScopeApproval`) when
   - the **plan** is more than 10% longer than agreed, or needs a bigger credit hold
     than reserved (`WritingBudget.holdFor`, the inverse of the writing capacity);
   - while **writing**, the projected length (words written + planned words left) is
     more than 15% past the agreed length, or the credits genuinely can't cover the
     rest of the book.
   Everything written is kept and the hold stays reserved. The user decides
   (`POST /scope-decision`): **CONTINUE** (agreed length := new length; the hold is
   extended by exactly what is missing, `402` if the balance can't cover it),
   **FIT** (keep it within the agreed length: after planning the outline is scaled
   down without dropping chapters; while writing the remaining chapters may be
   tightened or the book brought to its planned ending, flagged `creditLimited`), or
   **CANCEL** (after planning only: full refund, back to `DRAFT`). The run then
   resumes from where it stopped.
6. **Writing follows the plan.** The chapter prompt gives the depth and the
   planned size as *orientation, not a target or a limit*.
7. **Never mid-thought.** Generous output-token headroom; if a response still hits
   the limit, `ManuscriptIntegrity.repairTruncated` drops the incomplete tail. A
   truncated *edit* is discarded in favour of the complete original.
8. **Premium ending architecture.** The final chapter is written (and edited)
   against `ChapterPrompts.ENDING_ARCHITECTURE`.

## Page-count handling & billing

**1 credit = 1 final generated page.** The estimate is shown as an estimate
("~120–160 pages, ~120–160 credits"); the real cost is read back from the
rendered PDF, and the user pays for the pages they actually got.

To make the real length payable without a runaway bill, the balance may go a
little negative: a small **overdraft**, capped centrally at
`credits.max-overdraft` (`CREDITS_MAX_OVERDRAFT`, default **10**).

1. **Reserve a ceiling up front.** On **start** (`CreditService.reserveGenerationHold`)
   we reserve `min(balance, maxGenerationBudget) + maxOverdraft` as a hold — a
   credit ceiling, not a length. It can never drive the balance below
   `-maxOverdraft`. The DRAFT → PENDING transition is claimed atomically
   (`EbookRepository.claimForStart`) so a double-click can never reserve two holds.
2. **Size words to the real layout.** Chapter word targets use
   `WORDS_PER_PAGE ≈ 200`, measured against the real PDF pipeline
   (`WordsPerPageCalibrationTest`).
3. **Render in full.** The true page count is read from the PDF. Only if a render
   exceeds the credit ceiling (which the start gate and plan check make rare) is
   trailing content removed — whole `##` sections from the latest *earlier*
   chapter first, so the ending is preserved — and the book flagged `creditLimited`.
4. **Bill the real page count, idempotently** (`EbookGenerationService.reconcileCredits`):
   charge exactly the pages rendered (clamped to `[1, ceiling]`) and refund the
   rest as a `GENERATION_ADJUSTMENT`. Claimed atomically
   (`EbookRepository.markReconciled`). A generation that fails before a PDF exists
   refunds the whole hold.

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
