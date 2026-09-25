# Authentication

Self-contained auth for the Scrivetta backend: email/password registration
with email-verification links, login, logout, token refresh, password reset —
plus "Continue with Google" (OAuth 2.0 / OpenID Connect) on top of the same
session model.

## How it works

- **Access token** — a short-lived (15 min) HS256 JWT returned in the login
  response body. The client sends it as `Authorization: Bearer <token>` on
  protected endpoints. Its subject is the user id and it carries a `credAt`
  claim; `JwtAuthFilter` rejects any token minted before the user's last
  credential change, so a password reset instantly invalidates old tokens.
- **Refresh token** — a rotating, single-use token stored **only** in an
  `httpOnly`, `Secure`, `SameSite=Strict` cookie (`refreshToken`). Only its
  bcrypt hash is persisted. Each `/refresh` rotates it; replaying an already
  rotated token is treated as theft and revokes every session for that user.
- **One-time email tokens** — verification and password-reset links carry a
  `tokenId` + raw `token`. Only the bcrypt hash is stored, and each token is
  single-use with a 30-minute TTL.
- **Brute-force protection** — 5 failed logins locks the account for 5 minutes.
- Passwords are hashed with bcrypt. Security-relevant events (failed logins,
  lockouts, reset requests, refresh-token reuse) are audit-logged.

## Endpoints

All live under `/api/auth/**` and are public; every other route requires a
valid access token.

| Method | Path                             | Body / Params                                  | Purpose |
|--------|----------------------------------|------------------------------------------------|---------|
| POST   | `/api/auth/register`             | `{ displayName, email, password }`             | Create an account (disabled until verified); sends a verification email. |
| GET    | `/api/auth/verify`               | `?tokenId=&token=`                             | Activate the account from the emailed link. |
| POST   | `/api/auth/resend-verification-email` | `{ email }`                               | Resend the verification link. |
| POST   | `/api/auth/login`                | `{ email, password, rememberMe }`              | Returns the access token in the body + sets the `refreshToken` cookie. |
| POST   | `/api/auth/refresh`              | (cookie only)                                  | Rotates the refresh cookie and returns a new access token. |
| POST   | `/api/auth/logout`               | (cookie only)                                  | Revokes the refresh token and clears the cookie. |
| POST   | `/api/auth/forgot-password`      | `{ email }`                                    | Emails a reset link (always 200, never reveals whether the email exists). |
| POST   | `/api/auth/reset-password`       | `?tokenId=&token=` + `{ newPassword }`         | Sets a new password and revokes all sessions. |
| GET    | `/api/auth/me`                   | `Authorization: Bearer <token>`                | Returns the current user. |

| GET    | `/api/auth/oauth2/google/authorize` | `?client_state=` (random, 32–128 base64url chars) | Starts Google sign-in: sets the `oauth2_state` cookie, 302 → Google. |
| GET    | `/api/auth/oauth2/google/callback`  | `?code=&state=` (from Google)               | Google's redirect URI. 302 → `{FRONTEND_URL}/auth/callback#code=…` or `?error=<code>`. |
| POST   | `/api/auth/oauth2/exchange`      | `{ code, clientState, rememberMe }`            | Trades the one-time code for a session — same response as `/login`. |

The emailed links point at the **frontend** (`app.frontend-url`), e.g.
`/verify?tokenId=...&token=...` and `/reset-password?tokenId=...&token=...`;
the frontend page then calls the matching API endpoint.

## Sign in with Google

One Scrivetta `User` is the canonical account; sign-in methods are attached to
it. A password lives on `users.password`; a Google account is a row in
`user_identities` keyed by Google's stable `sub` (unique per
`(provider, provider_user_id)`, and one Google identity per user). Both
methods reach the same user, ebooks, credits and subscription.

**Flow** (all OAuth work is server-side; the client secret never leaves the backend):

1. Frontend stores a random `clientState` in `sessionStorage` and navigates to
   `/api/auth/oauth2/google/authorize?client_state=…`.
2. Backend creates `state`, `nonce` and a PKCE verifier, stores them (plus a
   hash of `clientState`) in a signed, 10-minute, `HttpOnly`, `Secure`,
   `SameSite=Lax` cookie scoped to `/api/auth/oauth2`, and redirects to Google
   with `scope=openid email profile`, `code_challenge_method=S256`.
3. On the callback the backend checks `state` against the cookie, redeems the
   code at Google (client secret + PKCE verifier), and validates the ID token:
   RS256 signature from Google's JWKS, `iss`, `aud`/`azp` = our client id,
   `exp`, and `nonce`.
4. The user is resolved (below), and a single-use, 2-minute login code bound to
   `clientState` is issued (stored bcrypt-hashed as a `user_tokens` row of type
   `OAUTH_LOGIN`). The browser is sent to `{FRONTEND_URL}/auth/callback#code=…`
   — in the fragment, so it never reaches server logs or `Referer`.
5. The frontend POSTs `{code, clientState}` to `/api/auth/oauth2/exchange` and
   gets exactly what `/login` returns (access token + `refreshToken` cookie).

**Account resolution** (`OAuthAccountService`):

- `(google, sub)` already linked → sign in that user (even if the Google email changed).
- Otherwise Google must say `email_verified: true`, else `email_not_verified`.
- A user with that email exists → the Google identity is linked to it. If that
  account had never verified its email, its password (set by someone who never
  proved ownership) is replaced with a random one, its sessions revoked, and
  the account activated — closing the pre-registration takeover. The owner can
  set a password again with "Forgot password".
- That user already has a *different* Google identity → `account_conflict`
  (never silently re-pointed).
- No user → one is created, activated (Google verified the email), with a
  random unusable password; "Forgot password" adds a real one at any time.

Error codes sent to `/auth/callback?error=`: `access_denied`, `invalid_state`,
`invalid_request`, `code_expired`, `provider_error`, `email_not_verified`,
`account_conflict`, `not_configured`, `server_error`. Codes, tokens and ID
tokens are never logged.

**Schema:** new table `user_identities` (created by Hibernate
`ddl-auto=update`; manual SQL in `docs/migrations/2026-09-25-google-oauth.sql`).
Purely additive — existing users are untouched.

## Configuration (environment variables)

| Var | Default | Notes |
|-----|---------|-------|
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | local postgres | PostgreSQL connection. |
| `JWT_SECRET` | dev placeholder | **Set this in prod.** ≥ 32 chars (256 bits) for HS256. |
| `JWT_EXPIRATION` | `900000` | Access-token TTL (ms). |
| `JWT_EXPIRATION_REMEMBER_ME` | `2592000000` | Access-token TTL with "remember me" (ms). |
| `POSTMARK_SERVER_TOKEN` | *(blank)* | Postmark transactional API token; blank = sends fail but app still boots. |
| `MAIL_FROM` | `no-reply@localhost` | Verified Postmark sender signature/domain. |
| `MAIL_APP_NAME` | `eBook Writer` | Sender display name used in emails. |
| `FRONTEND_URL` | `http://localhost:5173` | Base URL used to build email links. |
| `APP_CORS_ALLOWED_ORIGINS` | `http://localhost:5173,http://localhost:3000` | Comma-separated exact origins (no trailing slash). |
| `GOOGLE_CLIENT_ID` | *(blank)* | Google OAuth "Web application" client id. Blank = Google sign-in disabled. |
| `GOOGLE_CLIENT_SECRET` | *(blank)* | Its client secret (backend only). |
| `GOOGLE_REDIRECT_URI` | `http://localhost:8080/api/auth/oauth2/google/callback` | This backend's public callback; register it verbatim in Google Cloud Console. |

## Build & test

```bash
./gradlew build
```

Tests boot the full Spring context against in-memory H2, so no database is
needed to build.
