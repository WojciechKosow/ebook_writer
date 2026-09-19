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
  subtitle, description, writing guidelines), timestamps.
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
  "sourceMaterial": "(optional examples or source text)"
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

Markdown chapters → HTML (commonmark) → normalised XHTML (jsoup) → PDF
(openhtmltopdf). A 6×9" book layout with cover, table of contents, page
numbers, and clean chapter separation. Liberation fonts (Serif/Sans/Mono) are
bundled and embedded so Latin-alphabet languages (Polish, Spanish, German, …)
and code blocks render correctly. Styling lives in
`src/main/resources/pdf/ebook.css`.

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

Pages are money: 1 credit ≈ 1 page, so the page count is a **hard budget**, not
a soft target.

1. **Reserve a budget up front.** A draft holds no credits. On **start** we don't
   charge the raw requested count — we reserve `min(requestedPages + tolerance,
   balance)` credits as a hold (`credits.page-budget-tolerance`, default `+20%`).
   This is the ceiling generation may reach, and it can never exceed what the user
   can pay. If starting can't secure the hold, the ebook stays a `DRAFT` (its
   uploaded assets are kept) so the user can top up and retry.
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
