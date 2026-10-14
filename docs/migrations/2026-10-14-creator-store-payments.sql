-- Creator store payments (Stripe Connect, direct charges) — PostgreSQL.
--
-- Additive only. You normally do NOT need to run this by hand: with the default
-- JPA_DDL_AUTO=update, Hibernate creates these tables on boot. Run it manually
-- only if the production service uses JPA_DDL_AUTO=validate/none. Safe to re-run.

CREATE TABLE IF NOT EXISTS stores (
    id                         uuid PRIMARY KEY,
    owner_id                   uuid         NOT NULL UNIQUE,
    slug                       varchar(40)  NOT NULL UNIQUE,
    name                       varchar(80)  NOT NULL,
    description                text,
    published                  boolean      NOT NULL DEFAULT false,
    stripe_account_id          varchar(255) UNIQUE,
    charges_enabled            boolean      NOT NULL DEFAULT false,
    details_submitted          boolean      NOT NULL DEFAULT false,
    requirements_currently_due text,
    stripe_synced_at           timestamp(6),
    created_at                 timestamp(6),
    updated_at                 timestamp(6)
);

CREATE TABLE IF NOT EXISTS store_products (
    id          uuid PRIMARY KEY,
    store_id    uuid         NOT NULL,
    ebook_id    uuid         NOT NULL,
    title       varchar(200) NOT NULL,
    description text,
    price_cents bigint       NOT NULL,
    currency    varchar(3)   NOT NULL,
    active      boolean      NOT NULL DEFAULT true,
    created_at  timestamp(6),
    updated_at  timestamp(6),
    UNIQUE (store_id, ebook_id)
);
CREATE INDEX IF NOT EXISTS idx_store_products_store ON store_products (store_id);

CREATE TABLE IF NOT EXISTS store_order (
    id                  uuid PRIMARY KEY,
    version             bigint,
    store_id            uuid         NOT NULL,
    product_id          uuid         NOT NULL,
    stripe_account_id   varchar(255) NOT NULL,
    checkout_session_id varchar(255) UNIQUE,
    payment_intent_id   varchar(255),
    buyer_email         varchar(255),
    amount              bigint       NOT NULL,
    currency            varchar(3)   NOT NULL,
    status              varchar(16)  NOT NULL,
    consent_accepted_at timestamp(6),
    paid_at             timestamp(6),
    created_at          timestamp(6),
    updated_at          timestamp(6)
);
CREATE INDEX IF NOT EXISTS idx_store_order_payment_intent ON store_order (payment_intent_id);
CREATE INDEX IF NOT EXISTS idx_store_order_store ON store_order (store_id);

CREATE TABLE IF NOT EXISTS download_grant (
    id             uuid PRIMARY KEY,
    order_id       uuid         NOT NULL UNIQUE,
    token_hash     varchar(64)  NOT NULL UNIQUE,
    expires_at     timestamp(6) NOT NULL,
    downloads_used integer      NOT NULL DEFAULT 0,
    max_downloads  integer      NOT NULL,
    revoked_at     timestamp(6),
    created_at     timestamp(6)
);

CREATE TABLE IF NOT EXISTS stripe_webhook_event (
    event_id     varchar(255) PRIMARY KEY,
    type         varchar(255) NOT NULL,
    processed_at timestamp(6) NOT NULL
);
