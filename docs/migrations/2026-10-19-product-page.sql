-- Product page design (sales page, free preview, after-purchase page) — PostgreSQL.
--
-- Additive only. With the default JPA_DDL_AUTO=update Hibernate adds the column
-- on boot; run by hand only with validate/none. Safe to re-run.

ALTER TABLE store_products ADD COLUMN IF NOT EXISTS page_settings text;
