-- Store page designs (styles, texts, colors, custom CSS) — PostgreSQL.
--
-- Additive only. With the default JPA_DDL_AUTO=update Hibernate adds this on
-- boot; run by hand only with validate/none. Safe to re-run.

ALTER TABLE stores ADD COLUMN IF NOT EXISTS theme_settings text;
