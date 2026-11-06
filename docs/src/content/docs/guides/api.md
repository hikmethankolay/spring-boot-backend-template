---
title: API overview
description: Endpoints at a glance, error format, pagination and response headers.
---

The full, generated reference with request and response schemas is the [REST API reference](../../api/). This page
is the short version.

## OpenAPI document

The application generates its OpenAPI 3.1 document from the controllers (springdoc) and serves it at `/v3/api-docs`
(Swagger UI at `/swagger-ui.html`, both off in the prod profile). The same document is committed as
[`api/openapi.json`](https://github.com/hikmethankolay/spring-boot-backend-template/blob/main/api/openapi.json), so
API changes show up as diffs in pull requests and client generators can use it without running the app.

The document describes the behaviour, not just the shapes:

- every operation has a specific `operationId` (`registerUser`, `listUsers`, `getCurrentUser`, …), so generated
  clients get usable method names;
- error responses are `application/problem+json` with the `Problem` schema: 400 on operations with input, 401 on
  protected ones, 403 where a role is required, 404 on `/{id}`, 409 where a username or email can conflict, and 429
  and 500 everywhere. The shared ones are declared once under `components.responses` and referenced, and each
  operation that can fail in a specific way describes when;
- public operations have an empty `security` list; protected ones accept the `bearerAuth` header **or** the
  `cookieAuth` cookie (`auth_token`); refresh and logout also document the `refresh_token` cookie;
- responses declare `X-Trace-Id` and `RateLimit-*` (plus `Retry-After` on 429, `WWW-Authenticate` on 401, `Location`
  on 201 and `Set-Cookie` on token responses); the reusable ones are defined once under `components.headers`;
- page responses mark every field as required, and password fields are `writeOnly` with `format: password`;
- pagination is three query parameters, `page`, `size` (at most 100) and `sort`.

Three checks keep it honest. `OpenApiContractIntegrationTest` checks these rules on the generated document,
`OpenApiSpecExportIntegrationTest` fails when `api/openapi.json` differs from it, and every response of the integration tests is
validated against `api/openapi.json` (`OpenApiConformance`): an undocumented status, an undocumented content type or a
body that does not match its schema fails the test that produced it. After an intended API change, regenerate the
file and commit it with the code:

```bash
scripts/test.sh -Dit.test=OpenApiSpecExportIntegrationTest -Dopenapi.update=true
git diff api/openapi.json
```

### TypeScript client

`scripts/client.sh` turns `api/openapi.json` into a TypeScript client with
[OpenAPI Generator](https://openapi-generator.tech/docs/generators/typescript-fetch) (`typescript-fetch`): one class
per tag, one method per operation, on the browser's `fetch` and without runtime dependencies. It runs in the
generator's container image, so it needs Docker or Podman but neither Java nor Node.js:

```bash
scripts/client.sh ../web/src/api     # default output: target/client/typescript-fetch
```

```ts
import { AuthenticationApi, Configuration, UsersApi } from "./api";

// credentials: "include" sends the HttpOnly auth cookies; add the origin to CORS_ALLOWED_ORIGINS.
const config = new Configuration({ basePath: "http://localhost:8080", credentials: "include" });

await new AuthenticationApi(config).login({ loginRequest: { identifier: "admin", password: "…" } });
const me = await new UsersApi(config).getCurrentUser();
```

Method names are the `operationId`s. A response other than 2xx throws a `ResponseError`, whose `response` holds the
problem. Login can answer `202` for the second login step, which `login()` does not type; see
[Logging in](../frontend/#logging-in). Run the script again whenever `api/openapi.json` changes.

### Clients in other languages

For a mobile app or another service, `--generator` picks another of OpenAPI Generator's
[generators](https://openapi-generator.tech/docs/generators) (Kotlin, Swift, Dart, Java, Python and more):

```bash
scripts/client.sh --generator kotlin ../android/api     # default output: target/client/<generator>
scripts/client.sh --generator swift5 ../ios/Api
```

OpenAPI Generator's support for OpenAPI 3.1 documents is still in beta. CI generates the TypeScript client on every
push and type-checks it, but check the generator you choose against your own build too.

## Endpoints

| Method | Path | Access | Success |
|--------|------|--------|---------|
| `POST` | `/api/v1/auth/register` | public | `202`, no body; emails a sign-up link, or a notice to an already registered address ([why](../security/#account-flows)) |
| `POST` | `/api/v1/auth/register/complete` | sign-up link | `201` + `Location`; creates the account from the link's token, username and password |
| `POST` | `/api/v1/auth/login` | public | `200` access + refresh token, cookies; `202` + `mfaToken` with 2FA |
| `POST` | `/api/v1/auth/login/mfa` | MFA token | `200` access + refresh token, cookies |
| `GET` | `/api/v1/auth/oauth2/providers` | public | `200` configured providers (GitHub, Google) |
| `POST` | `/api/v1/auth/oauth2/{provider}` | public | `200` tokens, or `202` + `mfaToken` with 2FA |
| `POST` | `/api/v1/auth/refresh` | refresh token | `200` new tokens; the refresh token is single-use |
| `POST` | `/api/v1/auth/logout` | public | `204`; revokes the tokens, clears the cookies |
| `POST` | `/api/v1/auth/verify-email` | public | `204`; confirms an address, or replaces the email with the pending one |
| `POST` | `/api/v1/auth/verify-email/resend` | public | `202` |
| `POST` | `/api/v1/auth/password-reset` | public | `202` |
| `POST` | `/api/v1/auth/password-reset/confirm` | public | `204` |
| `GET` / `PATCH` | `/api/v1/users/me` | authenticated (+ password to change the email) | `200` + `ETag`; a new email is `pendingEmail` until its link is opened; `PATCH` takes `If-Match` ([concurrent updates](#concurrent-updates)) |
| `GET` | `/api/v1/users/me/security-events` | authenticated | `200` page of the user's audit events, newest first |
| `PUT` | `/api/v1/users/me/password` | authenticated | `204`; ends every session |
| `DELETE` | `/api/v1/users/me` | authenticated + password | `204` |
| `GET` | `/api/v1/users/me/export` | authenticated | `200` JSON attachment |
| `POST` | `/api/v1/users/me/mfa/totp` | authenticated | `200` new secret and `otpauth://` URI |
| `POST` | `/api/v1/users/me/mfa/totp/confirm` | authenticated + password + code | `200` recovery codes, shown once |
| `POST` | `/api/v1/users/me/mfa/totp/recovery-codes` | authenticated + password + authenticator code | `200` new recovery codes, shown once; the old ones stop working |
| `DELETE` | `/api/v1/users/me/mfa/totp` | authenticated + password + code | `204` |
| `GET` | `/api/v1/users/me/sessions` | authenticated | `200` active sessions, `current` marks this one |
| `DELETE` | `/api/v1/users/me/sessions` | authenticated | `204`; every other session is logged out at once, this one stays |
| `DELETE` | `/api/v1/users/me/sessions/{id}` | authenticated | `204`; that device is logged out at once |
| `GET` | `/api/v1/users/me/access-tokens` | authenticated | `200` unexpired [personal access tokens](../security/#personal-access-tokens), never their values |
| `POST` | `/api/v1/users/me/access-tokens` | authenticated + password (+ `code` when 2FA is on) | `201` `{token, details}`; the `pat_…` token is shown only this once |
| `DELETE` | `/api/v1/users/me/access-tokens/{id}` | authenticated | `204`; the token stops working at once |
| `GET` | `/api/v1/users/me/social-accounts` | authenticated | `200` linked GitHub/Google accounts: `id`, `provider`, `linkedAt` |
| `DELETE` | `/api/v1/users/me/social-accounts/{id}` | authenticated + password | `204`; that provider account can no longer sign in |
| `GET` | `/api/v1/users?q=alice&role=USER&suspended=false&page=0&size=20&sort=createdAt,desc` | admin | `200` page; every filter is optional ([filters](#pagination)) |
| `GET` | `/api/v1/users/{id}` | admin or that user | `200` |
| `PATCH` / `DELETE` | `/api/v1/users/{id}` | admin; administrators: owner; role changes and deleting an administrator: owner + password | `200` / `204` |
| `PUT` / `DELETE` | `/api/v1/users/{id}/suspension` | admin; administrators: owner | `200`; suspends (ends the sessions, refuses logins) / reactivates |
| `GET` | `/api/v1/users/{id}/security-events` | admin or that user | `200` page of the user's audit events, also of deleted users |
| `GET` / `POST` | `/api/v1/webhooks` | authenticated (also access tokens) | `200` own [webhook endpoints](../webhooks/) / `201` `{secret, endpoint}`, the `whsec_` secret shown once |
| `GET` / `PATCH` / `DELETE` | `/api/v1/webhooks/{id}` | the endpoint's owner | `200` / `200` / `204`; `PATCH {"enabled": true}` turns it back on |
| `POST` | `/api/v1/webhooks/{id}/secret` | the endpoint's owner | `200` new secret; the old one signs for 24 more hours |
| `POST` | `/api/v1/webhooks/{id}/test` | the endpoint's owner | `202` queues a `webhook.test` event |
| `GET` | `/api/v1/webhooks/{id}/deliveries` | the endpoint's owner | `200` page of deliveries with attempts and last answer |
| `POST` | `/api/v1/webhooks/{id}/deliveries/{deliveryId}/retry` | the endpoint's owner | `202`; sent again with the same `webhook-id` |
| `GET` / `POST` | `/api/v1/notes` | authenticated (own notes only) | `200` page / `201` + `Location` |
| `GET` / `PUT` / `DELETE` | `/api/v1/notes/{id}` | the note's owner | `200` + `ETag` / `200` + `ETag` / `204`; `PUT` takes `If-Match` |
| `GET` / `PUT` | `/api/v1/users/me/email-preferences` | authenticated | `200` optional email categories; `PUT` takes `{"product-updates": false}` |
| `GET` | `/api/v1/mail/unsubscribe?token=…` | unsubscribe link | `200` `{category, categoryName, enabled}`; changes nothing |
| `POST` | `/api/v1/mail/unsubscribe?token=…` | unsubscribe link | `204`; turns the link's category off. RFC 8058 one-click target: works without cookies or headers |
| `POST` | `/api/v1/mail/unsubscribe/all?token=…` | unsubscribe link | `204`; turns every optional category off |
| `POST` | `/api/v1/admin/mail/broadcasts/preview` | owner | `200` expected recipients and the rendered text; sends nothing |
| `POST` | `/api/v1/admin/mail/broadcasts` | owner + password | `202` broadcast id and expected recipients; queued in the background |
| `GET` | `/api/v1/admin/mail/broadcasts` | owner | `200` the latest 100 broadcasts, with final counts once `finishedAt` is set |
| `GET` | `/.well-known/jwks.json` | public | `200` JWK Set with the public signing key (empty with HS256) |

The notes endpoints belong to the sample feature (`note/`); see [Your first feature](../first-feature/).

Every email field (`register`, `PATCH /users/me`, the admin update and the email endpoints) uses the same rule,
`@ValidEmail`: a well-formed address of 3 to 254 characters. Tokens must not be empty. New passwords
have 15 to 128 characters (8 for users with two-factor authentication) and no composition rules; common and breached
passwords are refused (see [Security](../security/#passwords)). See [Security](../security/#roles) for what admins and the owner may do.

## Errors

Every error, including `401`, `403` and `429`, is an [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem
detail with the content type `application/problem+json`:

```json
{
  "type": "urn:problem:validation-failed",
  "title": "Bad Request",
  "status": 400,
  "detail": "Request validation failed.",
  "instance": "/api/v1/auth/register",
  "errors": [{ "field": "email", "message": "must be a well-formed email address" }]
}
```

`type` tells errors apart without parsing `detail`. Errors raised by Spring MVC itself (malformed JSON, unsupported
media type, ...) use `about:blank`; everything else uses one of these (the list is also in the description of
`Problem.type` in the OpenAPI document, generated from `ProblemType`):

| `type` | Status | When |
|--------|--------|------|
| `urn:problem:validation-failed` | 400 | The body or a query or path parameter is invalid; the fields are listed in `errors`, with an empty `field` for a rule about the whole body (e.g. two fields that must match) |
| `urn:problem:invalid-sort` | 400 | `sort` names a property that cannot be sorted by |
| `urn:problem:page-too-large` | 400 | `page` times `size` starts past the last row the database can skip |
| `urn:problem:wrong-password` | 400 | The caller's current password, required to confirm a change, is missing or wrong |
| `urn:problem:invalid-token` | 400 | An email verification or password reset token is unknown, used or expired |
| `urn:problem:unsubscribe-token-invalid` | 400 | An unsubscribe link's token is malformed or was not issued by this server |
| `urn:problem:broadcast-in-progress` | 409 | Another broadcast is still being queued or started less than a minute ago |
| `urn:problem:own-account` | 400 | An administrator tried to delete or suspend themselves through `/users/{id}` or `/users/{id}/suspension` |
| `urn:problem:invalid-mfa-code` | 400 / 401 | The authenticator or recovery code is wrong, expired or used (401 at login) |
| `urn:problem:mfa-challenge-expired` | 401 | The `mfaToken` is unknown, used or expired; log in again |
| `urn:problem:social-email-unverified` | 403 | The GitHub/Google account has no verified email |
| `urn:problem:provider-not-configured` | 404 | Login with this provider is not configured |
| `urn:problem:totp-already-enabled` / `totp-not-enabled` | 409 | 2FA is already on, or off / not being enrolled |
| `urn:problem:social-account-conflict` | 409 | The provider's email belongs to an account that has not verified it |
| `urn:problem:invalid-credentials` | 401 | Wrong login, or a refresh token that is missing, invalid, expired or used |
| `urn:problem:unauthenticated` | 401 | The access token is missing, invalid, expired or revoked |
| `urn:problem:forbidden` | 403 | The caller lacks the required role |
| `urn:problem:cross-site-request` | 403 | A state-changing request authenticated by cookie came without an allowed `Origin` or `X-Requested-With: XMLHttpRequest` |
| `urn:problem:owner-required` | 403 | Only the owner can change roles and manage administrators |
| `urn:problem:email-not-verified` | 403 | Login before the email address is verified (when required) |
| `urn:problem:account-suspended` | 403 | Login to an account an administrator suspended |
| `urn:problem:user-not-found` | 404 | No user has this id |
| `urn:problem:note-not-found` | 404 | The caller has no note with this id (other users' notes look missing too) |
| `urn:problem:session-not-found` | 404 | The caller has no session with this id (other users' sessions look missing too) |
| `urn:problem:social-account-not-found` | 404 | The caller has no linked account with this id (other users' links look missing too) |
| `urn:problem:note-limit-reached` | 409 | The caller already has `app.notes.max-per-user` notes |
| `urn:problem:access-token-not-found` | 404 | The caller has no access token with this id (other users' tokens look missing too) |
| `urn:problem:access-token-lifetime-too-long` | 400 | A new access token would outlive `app.access-tokens.max-lifetime` |
| `urn:problem:access-token-limit-reached` | 409 | The caller already has `app.access-tokens.max-per-user` unexpired tokens |
| `urn:problem:access-tokens-disabled` / `webhooks-disabled` | 403 | `app.access-tokens.enabled` / `app.webhooks.enabled` is `false` |
| `urn:problem:webhook-not-found` / `webhook-delivery-not-found` | 404 | The caller has no webhook endpoint, or the endpoint no delivery, with this id |
| `urn:problem:webhook-url-invalid` | 400 | The URL is malformed, not HTTPS where required, or not a public address |
| `urn:problem:webhook-event-type-invalid` | 400 | There is no webhook event type with this name |
| `urn:problem:webhook-event-not-allowed` | 403 | Application-wide events need `app.webhooks.admin-events-min-role` |
| `urn:problem:webhook-limit-reached` | 409 | The caller already has `app.webhooks.max-endpoints-per-user` endpoints |
| `urn:problem:webhook-endpoint-disabled` | 409 | A test or retry was sent to an endpoint that is turned off |
| `urn:problem:webhook-delivery-pending` | 409 | Only sent and failed deliveries can be retried; this one is still queued or being sent |
| `urn:problem:username-taken` / `email-taken` | 409 | The username or email belongs to another account (`POST /register` never answers `email-taken`) |
| `urn:problem:password-too-short` | 400 | The new password has fewer than 15 characters (8 with two-factor authentication) |
| `urn:problem:password-breached` | 400 | The new password is common or appeared in a data breach |
| `urn:problem:owner-protected` | 409 | The owner cannot be deleted or demoted, and `OWNER` cannot be assigned |
| `urn:problem:conflict` | 409 | A concurrent change got there first; retry |
| `urn:problem:precondition-failed` | 412 | `If-Match` does not match the current `ETag`: the resource changed since it was read |
| `urn:problem:idempotency-key-invalid` | 400 | The `Idempotency-Key` header is empty or longer than 255 characters |
| `urn:problem:idempotency-key-in-use` | 409 | A request with the same `Idempotency-Key` is still running; retry later |
| `urn:problem:idempotency-key-reused` | 422 | The `Idempotency-Key` was used for a request with another path or body |
| `urn:problem:login-locked` / `rate-limited` | 429 | Too many failed logins / requests; wait `Retry-After` seconds |
| `urn:problem:internal-error` | 500 | Unexpected error; quote `X-Trace-Id` when reporting it |
| `urn:problem:login-unavailable` | 503 | Login cannot check the failed-login counters (Redis is down); retry later |

## Pagination

List endpoints take Spring Data's `page`, `size` (at most 100) and `sort` parameters. `sort` only accepts the
properties listed in its parameter description; for `/api/v1/users` that is `id`, `username`, `email`, `role`,
`emailVerified`, `createdAt` and `updatedAt`. Anything else, including real but hidden columns such as the password
hash, is rejected with `400`, because sorting by a hidden column reveals information about its values. They return:

```json
{ "content": [ ... ], "page": { "size": 20, "number": 0, "totalElements": 42, "totalPages": 3 } }
```

`/api/v1/users` also takes optional filters, combined with AND:

| Parameter | Matches |
|-----------|---------|
| `q` | Users whose username or email contains the text, ignoring case; `%` and `_` are plain characters. At most 254 characters (`400` otherwise) |
| `role` | `USER`, `ADMIN` or `OWNER` |
| `suspended` | `true`: suspended users only; `false`: active users only |

The filters do not use an index: `q` matches anywhere in the text, which a B-tree index cannot serve. That is fine for
an admin screen over the users table; a very large one would need a `pg_trgm` index.

## Concurrent updates

`GET /api/v1/notes/{id}` and `GET /api/v1/users/me` return a strong `ETag` (for example `"3"`): the row's version,
which changes on every write. Send it back as `If-Match` with `PUT /api/v1/notes/{id}` or `PATCH /api/v1/users/me`, and
the update is refused with `412` and `urn:problem:precondition-failed` if someone changed the resource since you read
it, instead of overwriting their change. Reload it and retry. The responses of those updates carry the new `ETag`.

- Without `If-Match` an update applies to whatever the current version is, as before. `If-Match: *` does the same.
- Weak tags (`W/"3"`) never match `If-Match` (RFC 9110).
- The user's tag also changes on writes that do not show in the body, such as logging out every session, so a `412`
  does not always mean a visible change.
- `If-None-Match` with the current tag on those `GET`s answers `304` without a body.
- Two updates that pass the check at the same time are still told apart by the database: the second gets `409`.

## Retrying POST requests

A client that retries a `POST` after a timeout cannot know whether the first attempt ran. Send an
`Idempotency-Key` header (any unique value, for example a UUID, up to 255 characters) and send the same key with the
retry: within 24 hours a retry gets the first response again, with `Idempotent-Replayed: true`, instead of creating a
second note or queuing a second broadcast. This follows the IETF `Idempotency-Key` header draft.

- Keys belong to the signed-in caller. Anonymous `POST`s (login, sign-up, password reset) ignore the header: replaying
  a login would hand its tokens to anyone who knows the key. So do the two-factor enrollment endpoints
  (`/api/v1/users/me/mfa/**`), `POST /api/v1/users/me/access-tokens`, `POST /api/v1/webhooks` and
  `POST /api/v1/webhooks/{id}/secret`: their answers hold secrets, recovery codes or tokens, which are never copied to
  Redis.
- A body over 1 MiB is not buffered for the comparison, so such a request runs without the key.
- The same key with another path or body is refused with `422`; a retry while the first request still runs gets `409`.
- Only final answers are kept: `2xx`, `3xx` and every `4xx` but `408`, `409` and `429`. A `5xx`, `408 Request
  Timeout`, `409 Conflict` or `429 Too Many Requests` is not kept, so a retry with the same key runs the request again
  (for example once a rate limit has passed); a `400` or `404` is replayed, as running it again would give the same answer.
- A key is held for a running request for at most one minute, so that a request whose instance stops does not block
  its key for a day. A request that takes longer and is retried after that minute runs a second time; none of the
  template's endpoints come close.
- Without Redis the header is ignored and every request runs (ADR 2).

`PUT`, `PATCH` and `DELETE` are idempotent by design and do not need the header.

## Response headers

| Header | Meaning |
|--------|---------|
| `X-Trace-Id` | Trace id of the request, also present in the server logs |
| `RateLimit-Limit` / `RateLimit-Remaining` / `RateLimit-Reset` | Rate limit state (seconds until reset) |
| `Retry-After` | On `429`: seconds to wait |
| `Location` | On `201`: URL of the created resource |
| `ETag` | On a note and on `/users/me`: the version to send as `If-Match` ([concurrent updates](#concurrent-updates)) |
| `Idempotent-Replayed: true` | On a `POST` repeated with the same `Idempotency-Key`: the stored first response |
| `Cache-Control: no-store` | On every response: exactly `no-store` on token responses, Spring Security's `no-cache, no-store, max-age=0, must-revalidate` elsewhere |
| `Content-Security-Policy`, `Referrer-Policy`, `Permissions-Policy` | Browser hardening, see [Security](../security/#security-headers) |

## Token response

```json
{ "accessToken": "eyJ…", "tokenType": "Bearer", "expiresIn": 900, "refreshToken": "q3V…" }
```

`expiresIn` is the access token lifetime in seconds. Exchange the refresh token at `/api/v1/auth/refresh` before or
after the access token expires; each refresh token works once.
