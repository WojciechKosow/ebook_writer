-- Asset library (every uploaded image kept once per user for reuse) — PostgreSQL.
--
-- Additive only. With the default JPA_DDL_AUTO=update Hibernate creates this on
-- boot; run by hand only with validate/none. Safe to re-run.

CREATE TABLE IF NOT EXISTS library_assets (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL,
    storage_key text NOT NULL,
    sha256 varchar(64) NOT NULL,
    content_type varchar(255) NOT NULL,
    original_filename varchar(255),
    size_bytes bigint NOT NULL,
    width integer NOT NULL,
    height integer NOT NULL,
    expires_at timestamp(6),
    created_at timestamp(6),
    last_used_at timestamp(6)
);

CREATE INDEX IF NOT EXISTS idx_library_assets_user ON library_assets (user_id);
CREATE INDEX IF NOT EXISTS idx_library_assets_user_sha ON library_assets (user_id, sha256);
CREATE INDEX IF NOT EXISTS idx_library_assets_expires ON library_assets (expires_at);
