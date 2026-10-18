-- Store product files (PDF / EPUB / ZIP), product covers, standalone products and
-- buyer accounts on orders (My Purchases) — PostgreSQL.
--
-- Additive only. With the default JPA_DDL_AUTO=update Hibernate creates the new
-- table and columns on boot, and SchemaConstraintPatch relaxes
-- store_products.ebook_id at startup; run this by hand only with
-- JPA_DDL_AUTO=validate/none. Safe to re-run.

-- A product may be standalone (uploaded files only, no Scrivetta ebook).
ALTER TABLE store_products ALTER COLUMN ebook_id DROP NOT NULL;
-- The author's own cover/thumbnail (private R2 key), separate from the files.
ALTER TABLE store_products ADD COLUMN IF NOT EXISTS cover_key varchar(255);
ALTER TABLE store_products ADD COLUMN IF NOT EXISTS cover_content_type varchar(255);

-- The buyer's Scrivetta account, once known (checkout while signed in, webhook,
-- or first visit to My Purchases with the verified purchase email).
ALTER TABLE store_order ADD COLUMN IF NOT EXISTS buyer_user_id uuid;
CREATE INDEX IF NOT EXISTS idx_store_order_buyer_user ON store_order (buyer_user_id);

-- Downloadable files. Rows are never deleted: a replacement is a new row
-- (version + 1, replaces_id), the old one gets removed_at / removed_reason.
CREATE TABLE IF NOT EXISTS store_product_files (
    id                uuid PRIMARY KEY,
    product_id        uuid         NOT NULL,
    store_id          uuid         NOT NULL,
    original_filename varchar(255) NOT NULL,
    content_type      varchar(100) NOT NULL,
    format            varchar(8)   NOT NULL,
    size_bytes        bigint       NOT NULL,
    sha256            varchar(64)  NOT NULL,
    storage_key       text         NOT NULL,
    version           integer      NOT NULL DEFAULT 1,
    replaces_id       uuid,
    sort_order        integer      NOT NULL DEFAULT 0,
    uploaded_by       uuid         NOT NULL,
    created_at        timestamp(6),
    removed_at        timestamp(6),
    removed_reason    varchar(16)
);
CREATE INDEX IF NOT EXISTS idx_store_product_files_product ON store_product_files (product_id);
CREATE INDEX IF NOT EXISTS idx_store_product_files_store ON store_product_files (store_id);
