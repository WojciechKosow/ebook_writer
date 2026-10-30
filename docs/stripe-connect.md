# Creator store payments (Stripe Connect)

Authors sell their Scrivetta ebooks from a creator store at `/store/{slug}`.
The **author is the seller**: the buyer pays the author's own Stripe account
directly (a *direct charge*). Scrivetta takes no commission
(`application_fee_amount` is never set) and never holds or moves author money.
Refunds, disputes and payouts happen in the author's own Stripe Dashboard.

Publishing a store and selling both require active Scrivetta PRO
(`user_subscriptions.status` = `active` or `trialing`).

## How it works

| Step | Where |
|---|---|
| Author creates a store, adds finished ebooks with a USD price | `POST /api/store`, `POST /api/store/products` |
| "Connect Stripe" creates a connected account (once) and returns a Stripe-hosted Account Link | `POST /api/store/stripe/connect` |
| Author returns from Stripe; the backend **fetches the account** and stores its state (ready = `card_payments` active, requirements due) | `POST /api/store/stripe/sync`, and Accounts v2 events at `/api/stripe/connect/account-events` |
| Buyer clicks Buy; the client sends only `productId` | `POST /api/public/checkout` |
| Backend re-checks published + PRO + `charges_enabled`, creates a `PENDING` `store_order` and a Checkout Session **on the connected account** | `StoreCheckoutService` |
| Stripe sends `checkout.session.completed` (`payment_status=paid`) → order `PAID`, download grant, email with the link | `/api/stripe/connect/webhook` |
| Success page polls our order status until `PAID` | `GET /api/public/orders/{id}` |
| Guest buyer downloads each file through the backend (token: 30 days, 5 downloads per file) | `GET /api/public/downloads/{token}/files/{fileId}` |
| Signed-in buyer with a verified account: **My Purchases**, unlimited downloads | `GET /api/purchases`, see [product-files.md](product-files.md) |
| Author unlinks the account (**Disconnect Stripe**) to connect a different one | `DELETE /api/store/stripe` |

Connected accounts are created with **Accounts v2** (`POST /v2/core/accounts`). Stripe refuses
v1 account creation for new Connect platforms, so the original v1 controller properties map to:

| Intent (v1 controller) | Accounts v2 |
|---|---|
| `losses.payments=stripe` | `defaults.responsibilities.losses_collector=stripe` |
| `fees.payer=account` | `defaults.responsibilities.fees_collector=stripe` (Stripe bills the account) |
| `stripe_dashboard.type=full` | `dashboard=full` |
| `requirement_collection=stripe` | implied by the full Dashboard |
| account can take payments | `configuration.merchant.capabilities.card_payments.requested=true` |

Onboarding links use `POST /v2/core/account_links` (`account_onboarding`, configuration
`merchant`). The store is **Ready to sell** when the merchant `card_payments` capability is
`active` (the v2 equivalent of `charges_enabled`); "currently due" lists requirements awaiting
the author with a currently/past-due deadline. Checkout is unchanged: the `acct_...` id is used
in the `Stripe-Account` header (direct charge).

Checkout Sessions use `price_data` from our database price, `payment_method_types=["card"]`
(synchronous only), `order_id` in `metadata` and `client_reference_id`, and
`consent_collection.terms_of_service=required` with a `custom_text` stating that the buyer
requests immediate access and loses the right of withdrawal. The acceptance time is stored
on the order (`consent_accepted_at`).

The Connect webhook handles only `checkout.session.completed`, `checkout.session.expired`,
`account.updated` (if Stripe sends it) and `charge.refunded` (full refund → `REFUNDED`, grant revoked). Every
event is deduplicated in `stripe_webhook_event`, and an order event whose `event.account`
is not the order's connected account is logged and ignored.

It is completely separate from the PRO subscription webhook (`/api/stripe/webhook`,
`STRIPE_WEBHOOK_SECRET`, `processed_stripe_events`).

## Test-mode setup

1. In the Stripe Dashboard (**test mode**) of the Scrivetta platform account, enable
   **Connect** (Settings → Connect). Complete the platform profile.
2. Copy the platform's test secret key into `STRIPE_CONNECT_SECRET_KEY` (`sk_test_...`).
3. Create the Connect webhook endpoint: Developers → Webhooks → Add endpoint →
   **"Events on Connected accounts"**, URL `https://<backend>/api/stripe/connect/webhook`,
   API version **`2026-06-24.dahlia`** (the version `stripe-java 33.1.1` is built for),
   events:
   - `checkout.session.completed`
   - `checkout.session.expired`
   - `account.updated`
   - `charge.refunded`
4. Put that endpoint's signing secret into `STRIPE_CONNECT_WEBHOOK_SECRET`.
5. Accounts v2 status events: Developers → **Event destinations** → create a destination for
   **connected accounts**, payload style **thin**, URL
   `https://<backend>/api/stripe/connect/account-events`, events:
   - `v2.core.account[configuration.merchant].capability_status_updated`
   - `v2.core.account[requirements].updated`
   - `v2.core.account.updated`

   Put its signing secret into `STRIPE_CONNECT_V2_EVENTS_SECRET`. Every such event only
   triggers a server-side re-fetch of the account.
6. Leave `STRIPE_CONNECT_LIVE_ENABLED=false`. With it false, a live key is refused
   (every Connect call answers 503) and live-mode events are ignored.

> **Terms of service URL.** `consent_collection.terms_of_service=required` only works
> when a terms-of-service URL is set in the Dashboard's **Public details**. With direct
> charges the Checkout page belongs to the connected account, so verify in test mode
> whose setting Stripe uses. If session creation fails with an error about the terms of
> service URL, each author must set it in their own Dashboard (Settings → Public
> details) — or we revisit the approach. Do not switch to destination charges.

## Disconnecting an account

**Disconnect Stripe** in the store panel clears `stripe_account_id` and the synced state, so
the next **Connect Stripe** creates a new account. Only Scrivetta's link is removed: no Stripe
call is made, the account stays the author's (payouts, refunds and history in their own
Dashboard), and it works even when the old account doesn't exist for the current key — e.g. a
test-mode `acct_...` after going live. Orders already in Checkout keep their own
`stripe_account_id`, so their webhooks still fulfil them. Until a new account is ready, the
store shows the payments-setup notice and checkout is refused.

## Going live

1. Stripe Dashboard of the platform account, **live mode**: enable Connect (Settings →
   Connect) and complete the platform profile; set a terms-of-service URL in Settings →
   Public details.
2. Create the live Connect webhook endpoint and the live event destination exactly as in
   steps 3 and 5 of the test-mode setup below (same URLs, events and API version) — test-mode
   endpoints don't receive live events.
3. Set `STRIPE_CONNECT_SECRET_KEY=sk_live_...`, both new signing secrets
   (`STRIPE_CONNECT_WEBHOOK_SECRET`, `STRIPE_CONNECT_V2_EVENTS_SECRET`) and
   `STRIPE_CONNECT_LIVE_ENABLED=true`. The log should say
   `Creator store payments in LIVE mode`.
4. Authors connected in test mode click **Disconnect Stripe**, then **Connect Stripe** and
   complete the real onboarding. Test-mode `PENDING` orders never get a webhook; they stay
   `PENDING` and are harmless.

## Local testing

```bash
# 1. Backend with test keys
export STRIPE_CONNECT_SECRET_KEY=sk_test_...
# 2. Forward Connect events (prints a whsec_ secret — use it as STRIPE_CONNECT_WEBHOOK_SECRET)
stripe listen --forward-connect-to localhost:8080/api/stripe/connect/webhook \
  --events checkout.session.completed,checkout.session.expired,account.updated,charge.refunded
```

For Accounts v2 events, forward thin events to the second endpoint (its own secret →
`STRIPE_CONNECT_V2_EVENTS_SECRET`). Flag names depend on your Stripe CLI version — check
`stripe listen --help` for the thin-event options:

```bash
stripe listen --forward-thin-to localhost:8080/api/stripe/connect/account-events \
  --thin-events 'v2.core.account[configuration.merchant].capability_status_updated,v2.core.account[requirements].updated,v2.core.account.updated'
```

Without it the status still updates whenever the author returns from Stripe (the card also
re-syncs on that return). Use a separate `stripe listen --forward-to localhost:8080/api/stripe/webhook`
if you also test the PRO subscription; the secrets are all different.

Then:

1. Log in as an author with PRO, open **Store**, create the store, add a finished ebook,
   publish.
2. **Connect Stripe** → complete the test onboarding (test data such as the
   `000-000` SMS code, routing `110000000`, account `000123456789`) → back in Scrivetta
   the card shows **Ready to sell** once Stripe reports `charges_enabled`.
3. Open `/store/{slug}` in a private window, buy with card `4242 4242 4242 4242`.
4. The success page shows "Confirming payment…" until the webhook marks the order `PAID`;
   the buyer receives the download email (needs `POSTMARK_SERVER_TOKEN`, otherwise check
   the logs).
5. Refund the payment in the **connected account's** Dashboard (or
   `stripe refunds create --payment-intent pi_... --stripe-account acct_...`): the order
   becomes `REFUNDED` and the download link stops working.
6. Let a session expire, or `stripe checkout sessions expire cs_... --stripe-account acct_...`:
   the order becomes `EXPIRED`.

## Data

`stores` (incl. `stripe_account_id`, `charges_enabled`, `details_submitted`,
`requirements_currently_due`, `stripe_synced_at`), `store_products`, `store_order`,
`download_grant` (only the SHA-256 of the token is stored) and `stripe_webhook_event`.
See `docs/migrations/2026-10-14-creator-store-payments.sql`; product files, covers and
`store_order.buyer_user_id` are in `docs/migrations/2026-10-18-product-files.sql`.

## Known gaps (MVP)

- If the buyer email fails to send, the order stays `PAID` but the buyer has no link
  (logged as an error). Resending is a follow-up.
- Products can be Scrivetta ebooks (their current PDF is delivered) and/or carry uploaded
  PDF/EPUB/ZIP files — see [product-files.md](product-files.md).
- Going live needs explicit production configuration — see [Going live](#going-live).
