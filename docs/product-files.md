# Store product files and My Purchases

Creator-store products deliver digital files. A product is either a finished
Scrivetta ebook (its current PDF is always included) or a **standalone product**
with no ebook — and any product can carry up to `STORE_FILES_MAX_FILES_PER_PRODUCT`
uploaded **PDF, EPUB or ZIP** files, plus an optional cover image that is not part
of the download.

## Author

| Action | Endpoint |
|---|---|
| Standalone product (no `ebookId`, title required; starts hidden) | `POST /api/store/products` |
| Add a file (multipart `file`) | `POST /api/store/products/{id}/files` |
| Replace a file with a new version | `POST /api/store/products/{id}/files/{fileId}/replace` |
| Remove a file | `DELETE /api/store/products/{id}/files/{fileId}` |
| Own cover / thumbnail (PNG, JPG, WebP ≤ 3 MB) | `POST`/`DELETE /api/store/products/{id}/cover`, `POST …/cover/from-library/{assetId}` |

Every endpoint resolves the store from the signed-in author, so only the owner can
touch a product's files (anything else is a 404). The dashboard (`GET /api/store`)
lists each product's current `files` and whether it is `deliverable`; a product
can only be shown (`active`) — and bought — when it has something to deliver.

**Validation** (`ProductFileValidator`, server side, nothing is executed or extracted):

- extension must be `.pdf`, `.epub` or `.zip` **and** the bytes must match
  (`%PDF-`; ZIP local header; EPUB = ZIP with `mimetype` = `application/epub+zip`
  and `META-INF/container.xml`). The client's content type is ignored — the served
  type comes from the detected format;
- native executables (PE, ELF, Mach-O, shebang scripts) are refused under any name;
- ZIP/EPUB are inspected through the central directory only (names, never inflated):
  programs/installers inside (`.exe`, `.msi`, `.bat`, `.app/`, `.jar`, `.dmg`, …),
  absolute or `..` paths (zip-slip) and > 50 000 entries are refused;
- size ≤ `STORE_FILES_MAX_FILE_BYTES` (default 200 MB; keep ≤ the multipart limits).

Files are stored **unmodified** in the private R2 bucket under a server-generated key
(`products/{productId}/files/{uuid}.{ext}`); the key never leaves the backend. The
author's filename is sanitised (no directories / control characters) and only used as
the download name. Metadata kept per file: original filename, content type, format,
size, SHA-256, storage key, product/store, uploader, version, `replaces_id`, created
and removed timestamps.

**Replacing** creates a new row (`version + 1`, `replaces_id` → old) and marks the old
row `REPLACED` (history is kept); the old object is deleted after the transaction
commits. Buyers always get a product's **current** files, so past purchases keep
working. **Removing** marks the row `REMOVED`; the last file of a product that has
paid orders can't be removed (only replaced), and a product left with nothing to
deliver is hidden. A failed upload or a rolled-back transaction deletes the new object.

## Buyers

Access requires an order in `PAID` — set **only** by the signed Connect webhook,
never by the success redirect. Two ways to download:

1. **Guest (no account)** — the emailed token, as before: `GET /api/public/downloads/{token}`
   lists the files; `GET /api/public/downloads/{token}/files/{fileId}` streams one and
   counts one download. The grant allows 30 days and 5 downloads **per file** the
   product had when it was bought. The original `…/{token}/file` link still serves the
   first file.
2. **My Purchases (verified account)** — `GET /api/purchases` lists paid purchases with
   their current files; `POST /api/purchases/{orderId}/files/{fileId}/download-link`
   returns a 5-minute signed link (`/api/public/purchase-files?token=…`, JWT with its own
   type claim) that the browser downloads natively. Unlimited downloads; the entitlement
   is re-checked when the link is used.

An order belongs to an account (`store_order.buyer_user_id`) when the buyer was signed
in at checkout (Checkout then uses the account's email), or when a **verified**
(`enabled`) account has the email the buyer paid with — linked by the webhook, or
claimed the first time that account opens My Purchases. Unverified accounts get
nothing. Someone else's, unknown, unpaid or refunded orders are all the same 404.

**Refunds** (full) revoke both paths. Hiding the product, unpublishing the store or the
author losing PRO does **not** remove access. A file missing from storage answers 410
(logged as an error) and does not use up a guest download.

**Webhook retries** are idempotent: events are deduplicated by id, a PAID order is never
re-fulfilled, and a second download grant (and email) is never issued for an order.

## Configuration

| Variable | Default | |
|---|---|---|
| `STORE_FILES_MAX_FILE_BYTES` | `209715200` (200 MB) | per-file limit; keep ≤ `MULTIPART_MAX_FILE_SIZE` |
| `STORE_FILES_MAX_FILES_PER_PRODUCT` | `10` | |
| `R2_*` | — | existing private bucket; required for uploads/downloads of product files |

Schema: `docs/migrations/2026-10-18-product-files.sql` (applied automatically with
`JPA_DDL_AUTO=update`; `store_products.ebook_id` is made nullable by `SchemaConstraintPatch`).
