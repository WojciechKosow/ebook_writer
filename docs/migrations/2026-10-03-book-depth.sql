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
