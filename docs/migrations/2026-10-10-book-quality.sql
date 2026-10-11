-- Book quality: reader-facing chapter subtitles, the book template's recurring
-- sections, the topic registry and the author's quality report — PostgreSQL.
--
-- Additive only. You normally do NOT need to run this by hand: with the default
-- JPA_DDL_AUTO=update, Hibernate adds these columns on boot. Run it manually
-- only if the production service uses JPA_DDL_AUTO=validate/none. Safe to re-run.
--
-- Existing chapters get no reader subtitle: the chapter brief (description) is
-- internal and is no longer printed, so nothing appears under their titles
-- until the book is regenerated.

ALTER TABLE ebook_chapters ADD COLUMN IF NOT EXISTS reader_subtitle text;
ALTER TABLE ebook_chapters ADD COLUMN IF NOT EXISTS covered_topics text;
ALTER TABLE ebooks ADD COLUMN IF NOT EXISTS chapter_template_json text;
ALTER TABLE ebooks ADD COLUMN IF NOT EXISTS quality_report_json text;
