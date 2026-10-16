-- Creator store profile, ebook pages and ordering — PostgreSQL.
--
-- Additive only. With the default JPA_DDL_AUTO=update Hibernate adds these on
-- boot; run by hand only with validate/none. Safe to re-run.

ALTER TABLE stores ADD COLUMN IF NOT EXISTS headline varchar(160);
ALTER TABLE stores ADD COLUMN IF NOT EXISTS author_name varchar(80);
ALTER TABLE stores ADD COLUMN IF NOT EXISTS contact_email varchar(254);
ALTER TABLE stores ADD COLUMN IF NOT EXISTS instagram_url varchar(500);
ALTER TABLE stores ADD COLUMN IF NOT EXISTS tiktok_url varchar(500);
ALTER TABLE stores ADD COLUMN IF NOT EXISTS website_url varchar(500);
ALTER TABLE stores ADD COLUMN IF NOT EXISTS logo_key varchar(255);
ALTER TABLE stores ADD COLUMN IF NOT EXISTS logo_content_type varchar(255);
ALTER TABLE stores ADD COLUMN IF NOT EXISTS photo_key varchar(255);
ALTER TABLE stores ADD COLUMN IF NOT EXISTS photo_content_type varchar(255);

ALTER TABLE store_products ADD COLUMN IF NOT EXISTS highlights text;
ALTER TABLE store_products ADD COLUMN IF NOT EXISTS sort_order integer NOT NULL DEFAULT 0;
