-- Studio editor (PostgreSQL).
--
-- Additive only. With the default JPA_DDL_AUTO=update Hibernate adds these
-- columns on boot; run by hand only with validate/none. Safe to re-run.

-- The author's progress mark on a chapter (draft / revising / final).
ALTER TABLE ebook_chapters ADD COLUMN IF NOT EXISTS editorial_status varchar(16);

-- The cover arrangement picked in the editor (editorial / framed / type);
-- null follows the planned cover_layout.
ALTER TABLE ebooks ADD COLUMN IF NOT EXISTS editor_cover_style varchar(16);

-- Saved from the editor without rebuilding the PDF (autosave): rebuilt on the
-- next export or download.
ALTER TABLE ebooks ADD COLUMN IF NOT EXISTS pdf_stale boolean NOT NULL DEFAULT false;

-- New ledger type for the writing assistant. SchemaConstraintPatch drops the
-- enum check on boot; run this only when that patch is disabled.
ALTER TABLE credit_transactions DROP CONSTRAINT IF EXISTS credit_transactions_type_check;
