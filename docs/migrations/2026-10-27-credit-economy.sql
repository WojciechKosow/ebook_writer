-- Credit economy redesign (credits = AI usage budget, not pages) — PostgreSQL.
-- See docs/PRICING.md.
--
-- Additive only. With the default JPA_DDL_AUTO=update Hibernate adds these
-- columns on boot (the DB defaults back-fill existing rows); run by hand only
-- with validate/none. Safe to re-run. The data conversion itself is done by the
-- application (LegacyCreditMigration) at startup, one wallet per transaction.

-- Wallets: monthly PRO credits tracked apart from purchased credits; which
-- economy the balance is in (1 = legacy page credits, 2 = current credits).
ALTER TABLE credit_balances ADD COLUMN IF NOT EXISTS subscription_credits integer NOT NULL DEFAULT 0;
ALTER TABLE credit_balances ADD COLUMN IF NOT EXISTS economy_version integer NOT NULL DEFAULT 1;

-- Ledger: which part of a row moved monthly credits; unrecoverable clawback part.
ALTER TABLE credit_transactions ADD COLUMN IF NOT EXISTS subscription_amount integer NOT NULL DEFAULT 0;
ALTER TABLE credit_transactions ADD COLUMN IF NOT EXISTS shortfall integer NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_credit_transactions_stripe_ref ON credit_transactions (stripe_reference);

-- Ebooks: the page rate a generation is billed at (1 = legacy) and the monthly
-- part of its hold.
ALTER TABLE ebooks ADD COLUMN IF NOT EXISTS credits_per_page integer NOT NULL DEFAULT 1;
ALTER TABLE ebooks ADD COLUMN IF NOT EXISTS subscription_credits_held integer NOT NULL DEFAULT 0;

-- Subscriptions: the Stripe Price a subscription renews on.
ALTER TABLE user_subscriptions ADD COLUMN IF NOT EXISTS stripe_price_id varchar(255);

-- New enum values (also dropped at startup by SchemaConstraintPatch).
ALTER TABLE credit_transactions DROP CONSTRAINT IF EXISTS credit_transactions_type_check;
ALTER TABLE payment_orders DROP CONSTRAINT IF EXISTS payment_orders_credit_pack_check;

-- ---------------------------------------------------------------------------
-- ROLLBACK of the balance conversion (only if you must return to the old code).
-- Exact for wallets with no activity after the conversion; otherwise review the
-- ledger per user. Run with CREDITS_MIGRATION_ENABLED=false so it isn't re-run.
--
-- BEGIN;
-- UPDATE credit_balances b
--    SET balance = t.balance_after - t.amount,           -- the legacy balance
--        subscription_credits = 0,
--        economy_version = 1
--   FROM credit_transactions t
--  WHERE t.user_id = b.user_id
--    AND t.type = 'ECONOMY_MIGRATION'
--    AND b.balance = t.balance_after;                     -- untouched since conversion
-- UPDATE ebooks SET credits_charged = credits_charged / credits_per_page, credits_per_page = 1
--  WHERE credits_per_page > 1 AND credits_reconciled = false AND credits_refunded = false
--    AND created_at < '<deploy timestamp>';               -- only books converted, not new ones
-- COMMIT;
