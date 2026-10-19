-- Store redesign: draft + "Publish changes", removed products, extras for buyers,
-- account perks and per-file download counts — PostgreSQL.
--
-- Additive only. With the default JPA_DDL_AUTO=update Hibernate adds the columns
-- on boot; run by hand only with validate/none. Safe to re-run.

-- Unpublished changes of the whole store (JSON) and when they were last saved.
ALTER TABLE stores ADD COLUMN IF NOT EXISTS draft text;
ALTER TABLE stores ADD COLUMN IF NOT EXISTS draft_updated_at timestamp;

-- A product the author removed from the store (buyers keep it).
ALTER TABLE store_products ADD COLUMN IF NOT EXISTS removed_at timestamp;

-- An extra's name for buyers, what it's for, and whether only account holders get it.
ALTER TABLE store_product_files ADD COLUMN IF NOT EXISTS label varchar(120);
ALTER TABLE store_product_files ADD COLUMN IF NOT EXISTS note varchar(300);
ALTER TABLE store_product_files ADD COLUMN IF NOT EXISTS account_only boolean NOT NULL DEFAULT false;

-- Guest downloads counted per file ("fileId=count,...").
ALTER TABLE download_grant ADD COLUMN IF NOT EXISTS file_downloads text;
