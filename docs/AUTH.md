# Authentication

Simple, self-contained auth for the eBook Writer backend: email/password
registration with email-verification links, login, logout, token refresh, and
password reset. No OAuth2 — just the essentials.

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

The emailed links point at the **frontend** (`app.frontend-url`), e.g.
`/verify?tokenId=...&token=...` and `/reset-password?tokenId=...&token=...`;
the frontend page then calls the matching API endpoint.

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

## Build & test

```bash
./gradlew build
```

Tests boot the full Spring context against in-memory H2, so no database is
needed to build.
