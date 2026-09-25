-- Google sign-in ("Continue with Google") — PostgreSQL.
--
-- Additive only: creates one new table and relaxes one enum CHECK. No existing
-- row or column is modified; every existing user keeps working unchanged.
--
-- You normally do NOT need to run this by hand: with the default
-- JPA_DDL_AUTO=update, Hibernate creates user_identities on boot and
-- SchemaConstraintPatch drops user_tokens_type_check. Run it manually only if
-- the production service uses JPA_DDL_AUTO=validate/none. Safe to re-run.

CREATE TABLE IF NOT EXISTS user_identities (
    id               uuid          NOT NULL PRIMARY KEY,
    user_id          uuid          NOT NULL REFERENCES users (id),
    provider         varchar(32)   NOT NULL,
    provider_user_id varchar(255)  NOT NULL,
    email            varchar(255),
    picture_url      varchar(2048),
    created_at       timestamp(6)  NOT NULL,
    last_login_at    timestamp(6),
    CONSTRAINT uk_user_identities_provider_subject UNIQUE (provider, provider_user_id),
    CONSTRAINT uk_user_identities_user_provider    UNIQUE (user_id, provider)
);

CREATE INDEX IF NOT EXISTS idx_user_identities_user ON user_identities (user_id);

-- TokenType gained OAUTH_LOGIN (one-time login handoff codes); Hibernate never
-- widens the enum CHECK it generated, so drop it (values are enforced in code).
ALTER TABLE user_tokens DROP CONSTRAINT IF EXISTS user_tokens_type_check;
