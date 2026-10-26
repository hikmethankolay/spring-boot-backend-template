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
  on 201 and `Set-Cookie` on token responses), defined once under `components.headers`;
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
| `POST` | `/api/v1/auth/register` | public | `202`, no body; sends a verification email, or tells the owner of an already registered address ([why](../security/#account-flows)) |
| `POST` | `/api/v1/auth/login` | public | `200` access + refresh token, cookies; `202` + `mfaToken` with 2FA |
| `POST` | `/api/v1/auth/login/mfa` | MFA token | `200` access + refresh token, cookies |
| `GET` | `/api/v1/auth/oauth2/providers` | public | `200` configured providers (GitHub, Google) |
| `POST` | `/api/v1/auth/oauth2/{provider}` | public | `200` tokens, or `202` + `mfaToken` with 2FA |
| `POST` | `/api/v1/auth/refresh` | refresh token | `200` new tokens; the refresh token is single-use |
| `POST` | `/api/v1/auth/logout` | public | `204`; revokes the tokens, clears the cookies |
| `POST` | `/api/v1/auth/verify-email` | public | `204` |
| `POST` | `/api/v1/auth/verify-email/resend` | public | `202` |
| `POST` | `/api/v1/auth/password-reset` | public | `202` |
| `POST` | `/api/v1/auth/password-reset/confirm` | public | `204` |
| `GET` / `PATCH` | `/api/v1/users/me` | authenticated (+ password to change the email) | `200` |
| `PUT` | `/api/v1/users/me/password` | authenticated | `204`; ends every session |
| `DELETE` | `/api/v1/users/me` | authenticated + password | `204` |
| `GET` | `/api/v1/users/me/export` | authenticated | `200` JSON attachment |
| `POST` | `/api/v1/users/me/mfa/totp` | authenticated | `200` new secret and `otpauth://` URI |
| `POST` | `/api/v1/users/me/mfa/totp/confirm` | authenticated + password + code | `200` recovery codes, shown once |
| `DELETE` | `/api/v1/users/me/mfa/totp` | authenticated + password + code | `204` |
| `GET` | `/api/v1/users/me/sessions` | authenticated | `200` active sessions, `current` marks this one |
| `DELETE` | `/api/v1/users/me/sessions/{id}` | authenticated | `204`; that device is logged out at once |
| `GET` | `/api/v1/users?page=0&size=20&sort=createdAt,desc` | admin | `200` page |
| `GET` | `/api/v1/users/{id}` | admin or that user | `200` |
| `PATCH` / `DELETE` | `/api/v1/users/{id}` | admin; role changes and administrators: owner + password | `200` / `204` |
| `GET` / `POST` | `/api/v1/notes` | authenticated (own notes only) | `200` page / `201` + `Location` |
| `GET` / `PUT` / `DELETE` | `/api/v1/notes/{id}` | the note's owner | `200` / `200` / `204` |

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
| `urn:problem:validation-failed` | 400 | The body is invalid; the fields are listed in `errors` |
| `urn:problem:invalid-sort` | 400 | `sort` names a property that cannot be sorted by |
| `urn:problem:wrong-password` | 400 | The caller's current password, required to confirm a change, is missing or wrong |
| `urn:problem:invalid-token` | 400 | An email verification or password reset token is unknown, used or expired |
| `urn:problem:own-account` | 400 | An administrator tried to delete themselves through `/users/{id}` |
| `urn:problem:invalid-mfa-code` | 400 / 401 | The authenticator or recovery code is wrong, expired or used (401 at login) |
| `urn:problem:mfa-challenge-expired` | 401 | The `mfaToken` is unknown, used or expired; log in again |
| `urn:problem:social-email-unverified` | 403 | The GitHub/Google account has no verified email |
| `urn:problem:provider-not-configured` | 404 | Login with this provider is not configured |
| `urn:problem:totp-already-enabled` / `totp-not-enabled` | 409 | 2FA is already on, or off / not being enrolled |
| `urn:problem:social-account-conflict` | 409 | The provider's email belongs to an account that has not verified it |
| `urn:problem:invalid-credentials` | 401 | Wrong login, or a refresh token that is missing, invalid, expired or used |
| `urn:problem:unauthenticated` | 401 | The access token is missing, invalid, expired or revoked |
| `urn:problem:forbidden` | 403 | The caller lacks the required role |
| `urn:problem:owner-required` | 403 | Only the owner can change roles and manage administrators |
| `urn:problem:email-not-verified` | 403 | Login before the email address is verified (when required) |
| `urn:problem:user-not-found` | 404 | No user has this id |
| `urn:problem:note-not-found` | 404 | The caller has no note with this id (other users' notes look missing too) |
| `urn:problem:session-not-found` | 404 | The caller has no session with this id (other users' sessions look missing too) |
| `urn:problem:username-taken` / `email-taken` | 409 | The username or email belongs to another account (registration never answers `email-taken`) |
| `urn:problem:password-too-short` | 400 | The new password has fewer than 15 characters (8 with two-factor authentication) |
| `urn:problem:password-breached` | 400 | The new password is common or appeared in a data breach |
| `urn:problem:owner-protected` | 409 | The owner cannot be deleted or demoted, and `OWNER` cannot be assigned |
| `urn:problem:conflict` | 409 | A concurrent change got there first; retry |
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

## Response headers

| Header | Meaning |
|--------|---------|
| `X-Trace-Id` | Trace id of the request, also present in the server logs |
| `RateLimit-Limit` / `RateLimit-Remaining` / `RateLimit-Reset` | Rate limit state (seconds until reset) |
| `Retry-After` | On `429`: seconds to wait |
| `Location` | On `201`: URL of the created resource |
| `Cache-Control: no-store` | On every response, including token responses |
| `Content-Security-Policy`, `Referrer-Policy`, `Permissions-Policy` | Browser hardening, see [Security](../security/#security-headers) |

## Token response

```json
{ "accessToken": "eyJ…", "tokenType": "Bearer", "expiresIn": 900, "refreshToken": "q3V…" }
```

`expiresIn` is the access token lifetime in seconds. Exchange the refresh token at `/api/v1/auth/refresh` before or
after the access token expires; each refresh token works once.
