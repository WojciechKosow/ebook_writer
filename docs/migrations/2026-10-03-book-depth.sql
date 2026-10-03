-- Depth-based scope ("Quick / Standard / Comprehensive") replaces the user-chosen
-- page count — PostgreSQL.
--
-- Additive only. You normally do NOT need to run this by hand: with the default
-- JPA_DDL_AUTO=update, Hibernate adds these columns on boot and
-- SchemaConstraintPatch drops any ebooks_depth_check. Run it manually only if
-- the production service uses JPA_DDL_AUTO=validate/none. Safe to re-run.
--
-- ebooks.approx_page_count stays (NOT NULL on existing databases) but is no
-- longer read or written by generation; new books store 0.

ALTER TABLE ebooks ADD COLUMN IF NOT EXISTS depth varchar(32);
ALTER TABLE ebooks ADD COLUMN IF NOT EXISTS estimated_pages_low integer;
ALTER TABLE ebooks ADD COLUMN IF NOT EXISTS estimated_pages_high integer;
ALTER TABLE ebooks ADD COLUMN IF NOT EXISTS planned_pages integer;
ALTER TABLE ebooks ADD COLUMN IF NOT EXISTS credit_limited boolean NOT NULL DEFAULT false;
ALTER TABLE ebooks DROP CONSTRAINT IF EXISTS ebooks_depth_check;

-- Approval pauses + AI scope assessment.
ALTER TABLE ebooks ADD COLUMN IF NOT EXISTS approved_pages integer;
ALTER TABLE ebooks ADD COLUMN IF NOT EXISTS proposed_pages integer;
ALTER TABLE ebooks ADD COLUMN IF NOT EXISTS proposed_hold integer;
ALTER TABLE ebooks ADD COLUMN IF NOT EXISTS approval_stage varchar(16);
ALTER TABLE ebooks ADD COLUMN IF NOT EXISTS fit_to_budget boolean NOT NULL DEFAULT false;
ALTER TABLE ebooks ADD COLUMN IF NOT EXISTS scope_assessment_json text;
ALTER TABLE ebooks ADD COLUMN IF NOT EXISTS scope_assessment_key varchar(64);
-- EbookStatus gained AWAITING_APPROVAL (SchemaConstraintPatch drops this on boot too).
ALTER TABLE ebooks DROP CONSTRAINT IF EXISTS ebooks_status_check;
