---
title: Security
description: Tokens and sessions, revocation, account flows, lockout, rate limiting, headers and the audit log.
---

Every request passes the same checks, in this order. Each rejection is an RFC 9457 problem detail (see
[Errors](../api/#errors)).

<figure>
  <img class="dark:sl-hidden" src="../../diagrams/request-pipeline-light.svg" width="720" height="816" alt="A request passes RateLimitFilter (429) and CookieCsrfFilter (403); public paths then go straight to the controller, others need a valid, unrevoked JWT (401) and the required role (403); every rejection and exception becomes an RFC 9457 problem detail." />
  <img class="light:sl-hidden" src="../../diagrams/request-pipeline-dark.svg" width="720" height="816" alt="A request passes RateLimitFilter (429) and CookieCsrfFilter (403); public paths then go straight to the controller, others need a valid, unrevoked JWT (401) and the required role (403); every rejection and exception becomes an RFC 9457 problem detail." />
</figure>

## Tokens and sessions

Authentication is stateless, using JWTs and Spring Security's OAuth2 resource server, plus a refresh token per session.

- `POST /api/v1/auth/login` accepts a username or an email address and returns two tokens, in the body
  (`{"accessToken", "tokenType", "expiresIn", "refreshToken"}`) and as HttpOnly `SameSite=Strict` cookies.
- The **access token** is a JWT signed with `JWT_SECRET` (HS256, the default) or `JWT_PRIVATE_KEY` (RS256, see
  [Signing keys](#signing-keys)) with the header `typ: at+jwt` (RFC 9068). It carries `iss`, `aud` (`JWT_AUDIENCE`), `sub`, `roles`, `username`, the session id `sid` and a unique `jti`, and expires after `JWT_ACCESS_TOKEN_TTL` (15 minutes). A token without `typ: at+jwt`, or for another audience, is rejected (RFC 8725), so a JWT issued for something else (a sign-up link, another service) cannot be used as an access token. Services that verify these tokens with the published keys must accept `typ: at+jwt`. Send it as `Authorization: Bearer …` or as the
  `auth_token` cookie.
- The **refresh token** is an opaque 256-bit random value; only its SHA-256 hash is stored (table `refresh_tokens`).
  Exchange it at `POST /api/v1/auth/refresh` (body `{"refreshToken": "…"}`, or the `refresh_token` cookie, which the
  browser only sends to `/api/v1/auth`) for a new access token **and** a new refresh token.
- A session ends after `JWT_REFRESH_TOKEN_TTL` (1 day) without a refresh, or `JWT_REMEMBER_ME_TTL` (30 days) when
  logging in with `"rememberMe": true`. Remember-me sessions get persistent cookies; others get session cookies.
- However often it is refreshed, a session also ends `JWT_MAX_SESSION_TTL` (7 days) after the login, or
  `JWT_REMEMBER_ME_MAX_SESSION_TTL` (90 days) for remember-me sessions. Refresh tokens never outlive that point; a
  refresh after it is refused like any invalid token, the session is deleted and `auth.session_expired_absolute` is
  audited.
- Public endpoints, including `/refresh`, have their own filter chain without token processing, so an expired or stale
  access token can never block login or refresh.
- Everything else requires a valid access token. Role checks use `@PreAuthorize("hasRole('ADMIN')")`.
- CORS exposes every response header the API documents (`X-Trace-Id`, `RateLimit-*`, `Retry-After`, `Location`,
  `Content-Disposition`) to the origins in `CORS_ALLOWED_ORIGINS`.
- The cookies are `HttpOnly` and `SameSite=Strict`. With `COOKIE_SECURE=true` (prod) they are named
  `__Host-auth_token` and `__Secure-refresh_token`: browsers then accept them only over HTTPS, and the access cookie
  only for this host, so a sibling subdomain cannot set or shadow it.
- `SameSite` does not stop sibling subdomains, which count as the same site, so a state-changing request
  authenticated by cookie (not by an `Authorization` header) must also carry `X-Requested-With: XMLHttpRequest` or
  come from an `Origin` in `CORS_ALLOWED_ORIGINS` (or the API's own). Otherwise it is refused with
  `403 cross-site-request` (OWASP CSRF Prevention). Browsers send `Origin` on every `POST`, `PUT`, `PATCH` and
  `DELETE`, so frontends on an allowed origin need nothing extra. Spring's CSRF tokens stay off: the API is stateless.

A client keeps calling the API with the access token, and when it gets `401`, calls `/refresh` once and retries.

<figure>
  <img class="dark:sl-hidden" src="../../diagrams/auth-sequence-light.svg" width="720" height="628" alt="The client posts its credentials to /auth/login; with two-factor authentication on, the API stores a challenge in Redis and answers 202 with an mfaToken, which the client sends with a code to /login/mfa; the API saves a refresh-token hash and returns an access JWT and a refresh token; when the access token expires, /auth/refresh marks the old refresh token used and returns a new pair." />
  <img class="light:sl-hidden" src="../../diagrams/auth-sequence-dark.svg" width="720" height="628" alt="The client posts its credentials to /auth/login; with two-factor authentication on, the API stores a challenge in Redis and answers 202 with an mfaToken, which the client sends with a code to /login/mfa; the API saves a refresh-token hash and returns an access JWT and a refresh token; when the access token expires, /auth/refresh marks the old refresh token used and returns a new pair." />
</figure>

How long a session lasts without and with refreshing:

<figure>
  <img class="dark:sl-hidden" src="../../diagrams/session-lifetime-light.svg" width="720" height="364" alt="Session timeline: a session that stops refreshing ends one day after its last refresh, while one that keeps refreshing is still cut off seven days after login; remember-me uses 30 and 90 days." />
  <img class="light:sl-hidden" src="../../diagrams/session-lifetime-dark.svg" width="720" height="364" alt="Session timeline: a session that stops refreshing ends one day after its last refresh, while one that keeps refreshing is still cut off seven days after login; remember-me uses 30 and 90 days." />
</figure>

### Listing and ending sessions

`GET /api/v1/users/me/sessions` lists the caller's active sessions, newest first: when each started and was last
refreshed, when it expires, and the `User-Agent` and IP of its login or last refresh, so users can recognise their
devices. `current` marks the session making the request. `DELETE /api/v1/users/me/sessions/{id}` ends one: its refresh
tokens are deleted and its access tokens are rejected through Redis (by their `sid`), so that device is logged out at
once. Ending the current session works like logging out. Another user's session id answers `404`, like an unknown one.

The client IP is `request.getRemoteAddr()`: behind a proxy, set `FORWARD_HEADERS_STRATEGY` (see
[Rate limiting](#rate-limiting)), or every session shows the proxy's address.

## Signing keys

`JWT_ALGORITHM` picks how access tokens are signed:

| | `HS256` (default) | `RS256` |
|---|---|---|
| Key | `JWT_SECRET`, a shared secret | `JWT_PRIVATE_KEY`, an RSA private key (PEM, 2048 bits or more) |
| Who can verify tokens | only this application | anyone with the public key from `GET /.well-known/jwks.json` |
| Who can sign tokens | anyone holding the secret | only this application |
| Use when | one service issues and checks the tokens | other services must check tokens without being able to mint them |

A second service with `RS256` needs only the JWK Set URL:

```java
JwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri("https://api.example.com/.well-known/jwks.json").build();
```

Generate a key with `openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072`. With `HS256` the JWK Set is
empty: a shared secret is never published.

### Key rotation

Both settings take a comma-separated list. The **first** key signs new tokens; **every** key verifies them. Each token
names its key in the `kid` header (the key's RFC 7638 thumbprint). To rotate without logging anyone out:

1. Deploy with the new key first and the old one second: `JWT_SECRET=<new>,<old>`.
2. After one `JWT_ACCESS_TOKEN_TTL` (15 minutes), every token signed with the old key has expired. Deploy with
   `JWT_SECRET=<new>` alone.

`JWT_PRIVATE_KEY` works the same way, and both public keys appear in the JWK Set during the overlap. Refresh tokens
are random values stored as hashes, not signed, so they are not affected. If a key leaked, skip the overlap: deploy
the new key alone, which ends every access token at once (refresh tokens keep working).

## Roles

| Role | Can |
|------|-----|
| `USER` | Manage their own account (`/api/v1/users/me`) |
| `ADMIN` | Everything a user can, plus list, read, update and delete **regular users** |
| `OWNER` | Everything an admin can, plus change roles and update or delete administrators |

The owner is the account `AdminBootstrap` creates from `ADMIN_USERNAME` / `ADMIN_EMAIL` / `ADMIN_PASSWORD`, which must
follow the registration rules (otherwise the application does not start); there is at most one (a partial unique
index enforces it). `OWNER` implies `ADMIN`, which implies `USER` (a `RoleHierarchy` bean), so `hasRole('ADMIN')`
lets the owner through. The owner-only rules live in `UserService`, which reads the caller's
role from the database, so they apply even while an older access token still carries a previous role.

- **Only the owner** changes roles (`PATCH /api/v1/users/{id}` with `role`) and updates or deletes administrators.
  Anyone else gets `403` with `urn:problem:owner-required`.
- **Sensitive changes are confirmed with a password**: a role change needs the owner's `currentPassword` in the
  same body, and deleting an administrator needs a `{"password": "…"}` body, like `DELETE /api/v1/users/me`. A missing
  or wrong password is `400` with `urn:problem:wrong-password`, so a stolen access token alone cannot hand out
  admin rights or remove administrators.
- **Nobody removes the owner through the API**: the owner cannot be demoted or deleted, not even by themselves, and
  `OWNER` cannot be assigned (`409`, `urn:problem:owner-protected`). Transferring ownership is a database operation.

Migration `V5__owner_role.sql` turns the oldest administrator of an existing installation, the one `AdminBootstrap`
created, into the owner.

### Rotation and reuse detection

Each refresh token can be exchanged **once**. Every token issued from one login belongs to the same family (the
session). If an already exchanged token is presented again, someone has copied it: the whole session is revoked,
including its access tokens, both parties must log in again, and an `auth.refresh_token_reused` audit event is written.

Two requests refreshing the same token at the same time (for example two browser tabs) look the same as reuse. Let
one tab refresh and share the result, for example with a `BroadcastChannel` or a lock.

## Revocation

| Event | Effect |
|-------|--------|
| Logout | The session of the presented access token and of the refresh token ends: its refresh tokens are deleted and its `sid` is denylisted, so older access tokens of that session stop working too |
| Ending a session (`DELETE /api/v1/users/me/sessions/{id}`), reused refresh token | The session's refresh tokens are deleted and its `sid` is denylisted |
| Password change, password reset | Every session of the user ends: refresh tokens are deleted and all access tokens issued so far are rejected |
| Admin changes a user's password, or the owner changes a role | Same, so a demoted administrator loses access immediately |
| User deleted (by an admin, the owner or themselves) | Same; the refresh tokens are deleted with the user |

Access-token revocation lives in Redis: denylisted session ids and per-user cut-off times are kept only until the
affected tokens would have expired, and the JWT decoder checks both in one round trip on every request, on every
instance. After a password change the client must log in again.

<figure>
  <img class="dark:sl-hidden" src="../../diagrams/session-invalidation-light.svg" width="720" height="624" alt="A password or role change raises the user's session version and deletes the refresh tokens in the same transaction; after the commit a cut-off in Redis rejects older access JWTs with 401, and a refresh with an old token gets 401." />
  <img class="light:sl-hidden" src="../../diagrams/session-invalidation-dark.svg" width="720" height="624" alt="A password or role change raises the user's session version and deletes the refresh tokens in the same transaction; after the commit a cut-off in Redis rejects older access JWTs with 401, and a refresh with an old token gets 401." />
</figure>

## Passwords

New passwords follow NIST SP 800-63B-4:

- **Length, no composition rules.** 15 to 128 characters, counted as Unicode code points; 8 are enough for a user
  with two-factor authentication. A shorter one is refused with `urn:problem:password-too-short`.
  Two-factor authentication cannot be turned off while the password is shorter than 15 characters (also
  `password-too-short`): change the password first, so no account is left with a short password and one factor.
- **Blocklist.** Every new password (registration, change, reset, admin update) is checked, ignoring case, against
  the bundled `breached-passwords.txt`: the 46,000 entries of 8 or more characters from the NCSC top 100,000 list.
  A match is refused with `urn:problem:password-breached`. With `PWNED_CHECK_ENABLED=true` the password is also
  looked up in [Have I Been Pwned](https://haveibeenpwned.com/API/v3#PwnedPasswords): only the first 5 hex digits of
  its SHA-1 are sent, with padding. If the API is unreachable, only the bundled list applies.
- **Hashing.** Argon2id with 19 MiB of memory, 2 iterations and 1 lane (the OWASP minimum) and a random 16-byte salt
  per hash, stored with the algorithm id: `{argon2}$argon2id$...`. Hashes from 3.2.x and earlier
  (`{bcrypt}...`) still work and are rewritten as Argon2id at the user's next successful login. Argon2id costs
  more memory per login than BCrypt: size pods for about 20 MiB per concurrent login.

3.2.x and earlier cannot check `{argon2}` hashes. After a rollback to them, users who registered, changed their
password or logged in on a newer version cannot log in until they reset their password.

## Account flows

| Endpoint | Purpose |
|----------|---------|
| `POST /api/v1/auth/verify-email` | Confirm an address with the token from the email |
| `POST /api/v1/auth/verify-email/resend` | Send a new verification email (`202`) |
| `POST /api/v1/auth/password-reset` | Send a reset email (`202`) |
| `POST /api/v1/auth/password-reset/confirm` | Set a new password with the reset token |
| `DELETE /api/v1/users/me` | Delete the own account; body `{"password": "…"}` |
| `GET /api/v1/users/me/export` | Download everything stored about the user as JSON |

- Tokens are 256-bit random values. Only their SHA-256 hash is stored.
- Tokens are single-use and expire after 24 hours (verification) or 1 hour (reset). Requesting a new one invalidates
  the previous one.
- The `202` endpoints answer the same way for unknown addresses, so they cannot be used to find accounts. They send at
  most one email per account per minute, so nobody can flood an inbox.
- Sign-up is email-first. `POST /api/v1/auth/register` takes only the address and always answers `202` with no
  body: a new address gets a link to finish the sign-up, an address that already has an account gets a notice
  without a link (and `user.registration_repeated` is audited). Nothing is created or stored, so registration
  cannot be used to find out which addresses are registered.
- The link's page chooses the username and password and calls `POST /api/v1/auth/register/complete`, which
  creates the account with the address already verified (`201`). Whoever opens the link sets the credentials, so
  nobody can prepare an account on someone else's address. The token is a JWT of its own type (`sign-up+jwt`)
  holding the address, signed with the JWT keys and valid for 24 hours. Nothing is stored in the database; Redis
  remembers a used link until it expires, so a link used twice answers `400 invalid-token`. `409 email-taken` means
  the
  address got an account in the meantime (another sign-up link was used, or Redis was down when this one was). A taken
  username answers `409 username-taken` (usernames are public).
- Changing an email (`PATCH /api/v1/users/me`) needs `currentPassword`, marks the new address unverified, sends a new
  verification email and revokes every reset and verification link sent to the old address.
- Changing a password, or an administrator changing the password or role, ends every session and revokes outstanding
  reset links. A login or refresh running at the same moment cannot keep a session alive.
- With `REQUIRE_VERIFIED_EMAIL=true` (the prod default), users who changed their address and have not confirmed
  the new one get `403` at login. Sign-ups are verified when the account is created, so this applies only after
  an email change.
- Emails go through an outbox with retries, see [Observability](../observability/#email-outbox).
- Email templates print values with `th:text` and `th:href`, which escape them; `th:utext` and `[(...)]` are
  banned in HTML templates and a test enforces it. See [Email templates](../email-templates/).

<figure>
  <img class="dark:sl-hidden" src="../../diagrams/sign-up-light.svg" width="720" height="684" alt="POST /auth/register claims a Redis cooldown, looks up the address and emails either an already-registered notice or a sign-up link, answering 202 either way; the link's page posts to /register/complete, which checks the link is unused, inserts a verified user, marks the link used and answers 201." />
  <img class="light:sl-hidden" src="../../diagrams/sign-up-dark.svg" width="720" height="684" alt="POST /auth/register claims a Redis cooldown, looks up the address and emails either an already-registered notice or a sign-up link, answering 202 either way; the link's page posts to /register/complete, which checks the link is unused, inserts a verified user, marks the link used and answers 201." />
</figure>

## Two-factor authentication

Users can protect their account with an authenticator app (TOTP, RFC 6238: Google Authenticator, 1Password, Aegis…).

| Endpoint | Purpose |
|----------|---------|
| `POST /api/v1/users/me/mfa/totp` | New secret and its `otpauth://` URI; show the URI as a QR code |
| `POST /api/v1/users/me/mfa/totp/confirm` | Password and the first code from the app: turns 2FA on and returns ten recovery codes, once |
| `DELETE /api/v1/users/me/mfa/totp` | Turns 2FA off; needs the password **and** a code or recovery code |
| `POST /api/v1/auth/login/mfa` | Second login step: `{"mfaToken": "…", "code": "123456"}` |

Once it is on, a correct password (or GitHub/Google login) answers `202` with an `mfaToken` instead of tokens; the
client sends it with a code to `/login/mfa` within `MFA_CHALLENGE_TTL` (5 minutes) and gets the usual token response.

<figure>
  <img class="dark:sl-hidden" src="../../diagrams/totp-states-light.svg" width="720" height="496" alt="Two-factor states: POST /totp moves Off to Pending, where it can be repeated for a new secret; POST /confirm with password and code turns it on and returns 10 recovery codes; DELETE /totp with password and code turns it back off." />
  <img class="light:sl-hidden" src="../../diagrams/totp-states-dark.svg" width="720" height="496" alt="Two-factor states: POST /totp moves Off to Pending, where it can be repeated for a new secret; POST /confirm with password and code turns it on and returns 10 recovery codes; DELETE /totp with password and code turns it back off." />
</figure>

- Secrets are encrypted with AES-256-GCM under a key derived from `MFA_ENCRYPTION_KEY` with HKDF-SHA256 (RFC 5869),
  bound to the user id, so a database dump alone does not reveal them. Stored values carry a version prefix (`v1:`),
  so a later key or scheme can coexist with this one; values from before 4.0 (no prefix, key = SHA-256 of the
  setting) still decrypt. Keep the key out of database backups. Changing it breaks every enrolled authenticator; the
  affected users log in with a recovery code and enroll again.
- A code is accepted within one 30-second step of the server clock, and only once: the step of the last accepted code
  is stored. Recovery codes are stored as SHA-256 hashes and work once.
- Wrong codes count as failed logins of the IP and the account ([brute-force protection](#brute-force-protection)),
  and a correct password does not reset the account counter while the second factor is pending, so codes cannot be
  guessed by logging in again and again.
- The MFA token lives in Redis; without Redis, users with 2FA cannot log in (`503`), as with the failed-login counters.
- Nobody can turn off another user's 2FA. A user who lost both the app and the recovery codes needs an administrator
  to act in the database; decide and document that procedure.

## Login with GitHub and Google

Set `GITHUB_CLIENT_ID`/`GITHUB_CLIENT_SECRET` and/or `GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET`, and register
`OAUTH2_REDIRECT_URI` (a page of your frontend) as the callback URL at the provider. The API stays stateless: the
frontend runs the redirect and hands the code to the API.

1. `GET /api/v1/auth/oauth2/providers` lists the configured providers with an `authorizationUri`.
2. The frontend adds a random `state` and a PKCE `code_challenge` (`S256`), keeps `state` and the `code_verifier` in
   `sessionStorage`, and sends the user there.
3. The provider redirects to `OAUTH2_REDIRECT_URI?code=…&state=…`. The frontend checks `state`, then calls
   `POST /api/v1/auth/oauth2/{provider}` with `{"code": "…", "codeVerifier": "…"}`. The verifier is required
   (RFC 9700): a request without one gets `400` and the code is never sent to the provider.
4. The API exchanges the code (with the client secret, which never leaves the server), reads the profile and answers
   like `/login`: `200` with tokens and cookies, or `202` when the user has 2FA.

<figure>
  <img class="dark:sl-hidden" src="../../diagrams/social-login-light.svg" width="720" height="612" alt="The frontend gets the providers' authorization URIs, keeps state and a PKCE code_verifier and redirects to GitHub or Google; back on its redirect page it checks state and posts the code and verifier to the API, which exchanges them with the client secret, links or creates the user and answers 200 with tokens or 202 with an mfaToken." />
  <img class="light:sl-hidden" src="../../diagrams/social-login-dark.svg" width="720" height="612" alt="The frontend gets the providers' authorization URIs, keeps state and a PKCE code_verifier and redirects to GitHub or Google; back on its redirect page it checks state and posts the code and verifier to the API, which exchanges them with the client secret, links or creates the user and answers 200 with tokens or 202 with an mfaToken." />
</figure>

```ts
// Step 2, in the browser
const verifier = base64url(crypto.getRandomValues(new Uint8Array(32)));
const challenge = base64url(new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier))));
const state = base64url(crypto.getRandomValues(new Uint8Array(16)));
sessionStorage.setItem("oauth", JSON.stringify({ state, verifier }));
location.href = `${authorizationUri}&state=${state}&code_challenge=${challenge}&code_challenge_method=S256`;
// base64url(bytes) = btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")
```

Who logs in:

- A provider account used before logs in as the user it is linked to, matched by the provider's user id, not the email.
- Otherwise the provider must report a **verified** email (`403 social-email-unverified` if not). A user with that
  email who has verified it here gets the provider account linked. A user who has not verified it gets nothing linked
  (`409 social-account-conflict`): whoever registered the address may not own it.
- Otherwise a new `USER` is created with the email marked verified and a random password; it can set a password
  through the password reset, which it needs to delete the account, change its email or turn on 2FA.

<figure>
  <img class="dark:sl-hidden" src="../../diagrams/social-account-resolution-light.svg" width="712" height="728" alt="A provider account linked before logs in as its user; otherwise an unverified provider email gets 403, an email whose local account is unverified gets 409, an email with a verified account is linked to it, and an unknown email creates a new user; then the login proceeds." />
  <img class="light:sl-hidden" src="../../diagrams/social-account-resolution-dark.svg" width="712" height="728" alt="A provider account linked before logs in as its user; otherwise an unverified provider email gets 403, an email whose local account is unverified gets 409, an email with a verified account is linked to it, and an unknown email creates a new user; then the login proceeds." />
</figure>

## Personal data (GDPR / KVKK)

`DELETE /api/v1/users/me` implements the right to erasure: it needs the current password (a stolen token alone is not
enough), deletes the user and everything that references it with `ON DELETE CASCADE`, and ends its sessions. The owner
cannot delete their own account (`409 owner-protected`).

`GET /api/v1/users/me/export` implements the rights of access and portability. It returns the profile plus one section
per `PersonalDataProvider` bean, as a `personal-data.json` attachment; the template contributes the active sessions
(with their `User-Agent` and IP), the linked GitHub and Google accounts, and the sample notes. A session's `User-Agent`
and IP are deleted with the session: at logout, when it is ended, or when it expires.
When you add a feature that stores user data, implement `PersonalDataProvider` and reference `users(id)` with
`ON DELETE CASCADE`, see [Using the template](../new-project/).

Audit log entries keep the user id after deletion. Decide how long you retain them and state it in your privacy policy.

## Brute-force protection

Failed logins are counted per client IP and per account in Redis. An IP is blocked after `LOGIN_MAX_ATTEMPTS`
failures (default 10), an account after `LOGIN_ACCOUNT_MAX_ATTEMPTS` (default 100). While either is blocked, the login
is rejected with `429` and `Retry-After`, even with the right password, until `LOGIN_LOCK_DURATION` has passed without
new failures. A successful login only resets the account counter, not the IP counter.

An IPv6 client counts as its whole `/64` network, here and in the rate limit below: one host usually gets a `/64`
and could otherwise use a new address for every attempt.

The account limit is the higher one so that a single client cannot lock a real user out by typing wrong passwords for
their username: its IP is blocked long before the account. Locking an account takes failures from at least
`LOGIN_ACCOUNT_MAX_ATTEMPTS / LOGIN_MAX_ATTEMPTS` IPs within the lock duration, and the account limit still caps
distributed guessing against one account. Lower `LOGIN_ACCOUNT_MAX_ATTEMPTS` if you prefer stricter protection over
availability for targeted users.

## Rate limiting

Every client IP gets `RATE_LIMIT_PER_MINUTE` requests per sliding minute across all instances, so no burst of twice
the limit is possible around the turn of a minute. Responses carry
`RateLimit-Limit`, `RateLimit-Remaining` and `RateLimit-Reset`. Over the limit, the API answers `429` with
`Retry-After`. Actuator is exempt.

The endpoints attackers hammer have stricter limits per IP (`app.rate-limit.routes`), counted on top of the global
one; the headers then describe whichever limit is closest to running out:

| Path | Limit |
|------|-------|
| `/api/v1/auth/register/**` (start and finish a sign-up) | 5 per hour |
| `/api/v1/auth/login/**` (password and second factor) | 30 per minute |
| `/api/v1/auth/refresh` | 60 per minute |
| `/api/v1/auth/password-reset/**` | 5 per hour |

Sign-up emails are also limited to one per address per minute, whatever the client IP.

:::caution[Behind a proxy]
Set `FORWARD_HEADERS_STRATEGY=native` and let only the proxy reach the API port. Tomcat then takes the client IP from
the rightmost `X-Forwarded-For` entry that is not an internal proxy, so clients cannot pick their own IP. Without it,
every client appears to have the proxy's IP, and one client can lock out everyone.
:::

## Security headers

Every response carries, on top of Spring Security's defaults (`X-Content-Type-Options: nosniff`,
`X-Frame-Options: DENY`, `Cache-Control: no-store`, and `Strict-Transport-Security` on HTTPS requests):

| Header | Value |
|--------|-------|
| `Content-Security-Policy` | `default-src 'none'; frame-ancestors 'none'; …` (with the sources Swagger UI needs) |
| `Referrer-Policy` | `no-referrer` |
| `Permissions-Policy` | camera, microphone, geolocation, payment and USB disabled |

They are set in `SecurityConfig`. If you serve HTML from the app, extend the policy there.

## Audit log

Security events are written to the `audit` logger and counted in the `security_audit_events_total` metric:

| Event | When |
|-------|------|
| `auth.login_succeeded`, `auth.login_failed`, `auth.login_blocked`, `auth.login_unverified` | Login attempts |
| `auth.mfa_failed`, `auth.recovery_code_used`, `auth.social_account_linked` | Second factor and social login |
| `auth.logout`, `auth.session_revoked`, `auth.sessions_revoked`, `auth.refresh_token_reused`, `auth.session_expired_absolute` | Session changes |
| `user.mfa_enabled`, `user.mfa_disabled` | Two-factor authentication turned on or off |
| `user.registered`, `user.registration_repeated`, `user.email_verified`, `user.password_changed` | Account changes |
| `user.password_reset_requested`, `user.password_reset_completed` | Password reset |
| `user.account_deleted`, `user.data_exported` | Personal data requests |
| `admin.user_updated`, `admin.role_changed`, `admin.user_deleted` | Administrator actions |

Each entry has the affected `userId`, the authenticated `actorId`, the `clientIp`, and event-specific fields. Failed
and
blocked logins include `identifierHash`, the first 12 hex characters of the SHA-256 of the lower-cased identifier,
never the identifier itself (users sometimes type their password into it). With the prod profile these are separate
JSON fields, for example:

```json
{"log":{"level":"WARN","logger":"audit"},"message":"event=auth.login_failed clientIp=203.0.113.7 identifierHash=2bd806c97f0e",
 "event":"auth.login_failed","clientIp":"203.0.113.7","identifierHash":"2bd806c97f0e","traceId":"…"}
```

Entries are written after the transaction commits, so rolled-back actions never appear. Add an `AuditEvent` and call
`AuditLog.record(...)` for your own sensitive actions, and ship the `audit` logger to long-term storage.

Every entry is also stored in the `audit_events` table (columns for the event, user, actor and IP; the other fields as
JSON in `details`). The table is append-only: a trigger refuses changes and removals from every role but the table
owner, so the app's role (see [Production](../production/#database-roles)) cannot alter the trail even if the app is
compromised. Rows older than `AUDIT_RETENTION` (365 days) are removed daily through the database function
`purge_audit_events`, which keeps the last 90 days whoever calls it. If storing a row fails, the error is logged and
the request goes on; the log line still has the event.

## Redis outages

The rate limit and revocation checks fail open: if Redis is unreachable, requests are allowed and a warning is
logged. That keeps the API available, at the cost of briefly disabling these protections. Login fails closed instead:
it answers `503` (`urn:problem:login-unavailable`) until Redis is back, so an outage is never a window for unlimited
password guessing; the second login step of users with 2FA also needs Redis. Signed-in clients are unaffected, and refresh tokens live in PostgreSQL and keep working. See
[ADR 2](../../adr/0002-redis-fail-open/) and [Production](../production/).
