# Pricing & credit economy

Scrivetta sells AI usage as **credits**. A credit is an internal AI usage
budget — **not a page**. This document is the reference for the plans, the
credit-cost policy, how credits are granted, held, spent, refunded and expired,
the migration from the old "1 credit = 1 finished page" model, and the Stripe
setup.

## Plans

| Plan | Price | Credits | Marketplace storefront |
|---|---|---|---|
| **Basic** | Free | none included | no |
| **Pro** | $29.99 / month (recurring) | 3,000 per paid billing period | yes |

**Top-ups** (one-time, any plan, never expire):

| Pack (`CreditPack`) | Credits | Price | Per credit |
|---|---:|---:|---:|
| `STARTER` | 500 | $7.99 | $0.016 |
| `CREATOR` | 1,200 | $19.99 | $0.017 |
| `STUDIO` | 2,500 | $34.99 | $0.014 |

Pro's included credits work out to $0.010 each. Note the Creator pack is
slightly *more* expensive per credit than Starter at these prices.

Basic keeps the whole existing app: brief, knowledge ingestion, blueprint,
estimates, the editor and PDF export are free; generation needs credits (from
packs). Pro adds the monthly allowance and the creator storefront
(`SubscriptionService.isPro` — server-side, mirrored from Stripe webhooks).

## Credit-cost policy

One place: `CreditPricing`, configured under `credits.pricing.*`.

| Operation | Cost | Notes |
|---|---|---|
| Knowledge analysis, blueprint, scope assessment | free | small OpenAI model; cost recorded on `book_knowledge` / `book_blueprints` |
| Writing a book (planning, chapters, editorial pass, QA) | **11 credits per final rendered page** | Claude; the bulk of the cost, scales with length |
| AI illustration | **15 credits per image** | at most `openai.max-images-per-book` (6) are ever billed |
| AI cover | **30 credits** | with the book, or `POST /images/cover/regenerate` |
| Editing, preview, PDF export | free | no AI |

### How the numbers were set

There is no per-call cost tracking for Claude, so costs are an explicit,
deterministic policy derived from the business reference, not measured cost:

- Reference workload: a ~265-page text-led ebook with up to 6 illustrations and a
  cover ≈ **$13–14** of combined AI cost (Claude Opus writing + editorial pass,
  OpenAI analysis, images).
- Target: that workload ≈ **3,000 credits** (one Pro month).
  → 1 credit ≈ $0.0045 of AI cost at the reference.
- Images: `gpt-image-1` at high/auto quality is roughly $0.07–0.25 per image; the
  cover adds a Claude planning call. 15 / 30 credits ($0.15 / $0.30 at Pro's rate)
  cover that.
- The rest (~$12.5–13 for ~265 pages ≈ $0.048/page) → ≈ 10.7 credits/page → **11**.
- Check: `265 × 11 + 6 × 15 + 30 = 3,035` credits (`EbookBillingScenarioTest#case10`).

These are estimates, not guarantees: retries, long sources and model changes
move the real cost. Revisit the three numbers when models or prices change —
they are env-configurable (`CREDITS_PER_PAGE`, `CREDITS_PER_IMAGE`,
`CREDITS_PER_COVER`). A running book keeps the page rate it started with
(`ebooks.credits_per_page`).

## Lifecycle of a book's credits

1. **Estimate.** `GET /api/ebooks/{id}/scope` returns pages *and* credits:
   `creditsLow = pagesLow × 11 + cover`, `creditsHigh = requiredCredits =
   pagesHigh × 11 + image reserve (6 × 15 + 30)`. Shown before starting.
2. **Hold.** `start` / `resume` call `CreditService.reserveGenerationHold` under
   the wallet's `SELECT … FOR UPDATE` lock: requires `requiredCredits`, holds
   `pages × 11 + image reserve`, where `pages = min(affordable, 400) + 10`
   (overdraft, in pages). The balance can never go below `-10 × 11 = -110`.
   Concurrent starts serialise on the row lock; the DRAFT → PENDING claim is
   atomic, so a double click can't hold twice.
3. **Longer book?** Writing pauses (`AWAITING_APPROVAL`) and asks; continuing
   extends the hold by `extraPages × rate` (checked under the lock).
4. **Settle** once rendered (`reconcileCredits`, claimed once via
   `markReconciled`): charge `pages × rate + AI illustrations × 15 + AI cover × 30`,
   never more than held; refund the rest.
5. **Failure** before a PDF exists refunds the whole hold; written chapters are
   kept and the book can be resumed (a new hold, billed once at the end).

## Two kinds of credits

`credit_balances.balance` is the spendable total; `subscription_credits` is the
part that is this period's Pro allowance; `purchased = balance − subscription`.

- **Spending** takes monthly credits first, then purchased.
- **Refunds** return credits to the kind they came from — purchased first (the
  charge that stands is attributed monthly-first). Every ledger row records its
  monthly part in `subscription_amount`.
- **Renewal** (`invoice.paid`, `subscription_create` / `subscription_cycle`):
  leftover monthly credits expire (`SUBSCRIPTION_EXPIRY` row), then the
  allowance is granted (`SUBSCRIPTION_GRANT`, `stripe_reference = invoice id`).
  Idempotent per invoice, checked under the wallet lock — a redelivered or
  duplicated event never grants twice, and the checkout success page grants
  nothing. A small overdraft deficit is settled from the new credits.
- **Pro ends** (`customer.subscription.deleted` — period ran out after a cancel,
  or dunning gave up): remaining monthly credits expire. Purchased credits,
  books, purchases and the store's data stay; the store stops taking orders.
- **Failed renewal** (`invoice.payment_failed`): status `past_due` → not Pro
  (no grace period beyond Stripe's own retry schedule — unchanged). Credits
  already granted for the paid period stay usable.
- Known edge: a hold refunded after the period rolled over returns its monthly
  part to the monthly bucket, which can extend that small amount by one period.

## Refunds and chargebacks (explicit policy)

`charge.refunded` / `charge.dispute.created`:

- **Credit pack** (matched by payment_intent): reclaim `round(packCredits ×
  refunded fraction)` from **unspent purchased credits only**.
- **Subscription payment**: the invoice is found via `InvoicePayment.list`
  (`StripeInvoiceLookup`); if it is the **current** period, reclaim from unspent
  **monthly** credits only; an older period's credits already expired → nothing.
- Never below zero and never from the other kind of credits. Whatever was
  already spent is recorded as `shortfall` on the `REFUND_CLAWBACK` row and
  logged at WARN for manual review. Settled amounts (reclaimed + shortfall) per
  payment_intent make repeated refund/dispute events idempotent.

This replaces the old behaviour (clawbacks could drive the balance negative).

## Existing users — migration

Old balances were **page credits** (1 credit = 1 finished page, from $19.99/150
subscriptions and 25/60/150 packs). They are converted once, at startup, by
`LegacyCreditMigration`:

- `new = old × credits.migration.legacy-credit-value` (default = credits per
  page = **11**): a legacy credit bought one finished page and a page now costs
  11 credits, so every balance keeps what it buys. No other factor is assumed.
- All converted credits become **purchased** (non-expiring) — legacy credits never
  expired, so no customer loses anything.
- Books that were mid-generation (held, not settled) are converted with the
  wallet: their hold × 11 and `credits_per_page = 11`, so their settlement or
  refund is in the new unit. Settled history (completed books, ledger rows) is
  never rewritten; old books keep `credits_per_page = 1` and the UI labels their
  usage "page credits".
- Each conversion is one transaction under the wallet lock, stamped
  `economy_version = 2` and recorded as an `ECONOMY_MIGRATION` ledger row (old
  balance, factor, new balance). Idempotent; a wallet that isn't converted yet is
  locked against spending/granting (reads show its converted value).
- Rollback SQL: `docs/migrations/2026-10-27-credit-economy.sql`.

**Existing subscribers** stay on their Stripe price until you move them. Renewals
on a price listed in `STRIPE_LEGACY_SUBSCRIPTION_PRICE_IDS` grant
`CREDITS_LEGACY_SUBSCRIPTION_MONTHLY_GRANT` (default **1,650** = 150 pages × 11 —
exactly what the $19.99 plan paid for). If you leave that variable empty, every
renewal grants the full 3,000. Moving them to $29.99 is a business decision
(notice to customers) done in Stripe.

## Stripe setup (manual — not done from code)

1. **Products → Scrivetta Pro → Add price**: $29.99 USD, recurring monthly. Do
   this in **live** mode for production (and in test mode for staging).
2. Set `STRIPE_SUBSCRIPTION_PRICE_ID` to the new `price_…`.
3. Set `STRIPE_LEGACY_SUBSCRIPTION_PRICE_IDS` to the old $19.99 price id(s).
   Optionally archive the old price so it can't be used for new checkouts (it
   keeps working for existing subscriptions).
4. **Credit packs need no Stripe objects** — checkout sends the price inline from
   `CreditPack`.
5. Webhook endpoint must include: `checkout.session.completed`,
   `checkout.session.async_payment_succeeded`, `checkout.session.async_payment_failed`,
   `checkout.session.expired`, `invoice.paid`, `invoice.payment_failed`,
   `customer.subscription.updated`, `customer.subscription.deleted`,
   `charge.refunded`, `charge.dispute.created`.

## Environment variables

| Variable | Default | Meaning |
|---|---|---|
| `STRIPE_SUBSCRIPTION_PRICE_ID` | — | the $29.99/month Pro price (**must be changed**) |
| `STRIPE_LEGACY_SUBSCRIPTION_PRICE_IDS` | empty | old Pro price ids (comma-separated) |
| `CREDITS_SUBSCRIPTION_MONTHLY_GRANT` | 3000 | Pro credits per paid period |
| `CREDITS_LEGACY_SUBSCRIPTION_MONTHLY_GRANT` | 1650 | per period on a legacy price |
| `CREDITS_PER_PAGE` / `_IMAGE` / `_COVER` | 11 / 15 / 30 | operation costs |
| `CREDITS_MAX_OVERDRAFT` | 10 | overdraft tolerance, **pages** (was credits) |
| `CREDITS_MAX_GENERATION_BUDGET` | 400 | per-book page ceiling (runaway/cost guard) |
| `CREDITS_MIGRATION_ENABLED` | true | run the legacy conversion at startup |
| `CREDITS_MIGRATION_LEGACY_CREDIT_VALUE` | 0 (= per page) | conversion factor |

> **Before deploying, check the host's environment for old overrides.** If
> `CREDITS_SUBSCRIPTION_MONTHLY_GRANT=150` or `CREDITS_MAX_OVERDRAFT` (in the old
> credit meaning) are set explicitly, they override the new defaults — remove them.

## Cost monitoring

- Ledger (`credit_transactions`) records every movement by type: generation
  holds/settlements per ebook, cover regenerations, refunds, grants, expiries,
  clawbacks (with shortfalls) — enough to report credits consumed per operation
  and per user per month with SQL.
- Measured OpenAI cost (estimated from tokens) is stored per run on
  `book_knowledge` and `book_blueprints`. Claude and image calls are **not**
  metered yet; their cost is the policy estimate above, not measured.
- Guards against cost spikes: per-book page ceiling (400), max images per book
  (6), bounded overdraft, knowledge/blueprint run limits, and every paid
  operation is reserved before it runs.
