---
title: Using the template
description: Turn the template into your own project.
---

1. Create your repository with *Use this template* on GitHub, and clone it.
2. Rename it:

   ```bash
   scripts/rename-project.sh com.acme order-service com.acme.orders
   scripts/test.sh
   ```

   The script changes the Maven `groupId` and `artifactId`, the application name and the base Java package, and moves
   the source files. If you leave out the package, it is derived from the other two (`com.acme.orderservice`).
   It also points the repository links to your GitHub repository, which it reads from the `origin` remote: the
   image in `deploy/kubernetes`, `.github/CODEOWNERS`, the links in `SECURITY.md`, the issue templates and the
   guides, and the documentation site's address. If `origin` is missing or still the template, it stops before
   changing anything; pass your repository with `--repo owner/name`. It refuses to run twice.
3. Add your tables as new Flyway migrations in `src/main/resources/db/migration`, numbered after the last one
   (`V10__create_orders.sql`, ...). Never edit a migration that has already been applied.
4. Add each feature as its own package next to `user/`, with its own controller, service, repository and `dto/`.
   The sample `note/` package shows all of it; see [Your first feature](../first-feature/), which also says how to
   delete it.
5. After changing an endpoint, regenerate the committed OpenAPI document with
   `scripts/test.sh -Dit.test=OpenApiSpecExportIntegrationTest -Dopenapi.update=true`, give new operations an `operationId`, and
   declare responses the shared customizers cannot know about (for example a 409 on a uniqueness conflict).
6. For every list endpoint, allow sorting only by fields the response already shows: check the `Pageable` with a
   `SortAllowList` in the service (see `UserService.SORTABLE_PROPERTIES`) and name the properties in the `sort`
   parameter description.
7. Throw domain errors with `ApiException.notFound(...)`, `.conflict(...)`, `.badRequest(...)`. They become
   RFC 9457 problem details automatically.
8. Protect endpoints with `@PreAuthorize`. Everything outside the public paths in `SecurityConfig` needs a valid token.
9. If the feature stores data about a user, reference `users(id)` with `ON DELETE CASCADE` and implement
   `PersonalDataProvider`, so account deletion and the data export (`GET /api/v1/users/me/export`) stay complete.
10. Record sensitive actions with `AuditLog.record(AuditEvent.…, userId, ...)` and send emails with `EmailOutbox.enqueue`.
11. Delete the features you don't need (for example `account/`), along with their tests. Two-factor authentication
    and social login are wired into the login flow; see [Removing them](#removing-two-factor-authentication-or-social-login).
12. Rewrite the README for your project, set the copyright holder in `LICENSE`, and enable *Private vulnerability
    reporting* in the repository's security settings. With your first release, set `newTag` in
    `deploy/kubernetes/kustomization.yaml` to its version; `KubernetesManifestTest` fails while it lags behind the
    latest version in `CHANGELOG.md`.
13. Turn on the workflows that need repository settings. The documentation site deploys only once *Settings → Pages →
    Source* is *GitHub Actions*; until then `pages.yml` is skipped. CodeQL and OpenSSF Scorecard are skipped for pushes
    and pull requests in a private repository, where code scanning needs GitHub Advanced Security; delete
    `codeql.yml` and `scorecard.yml` there, or their weekly scheduled runs fail.

## Removing two-factor authentication or social login

`note/` and `account/` are self-contained: delete the package and its tests. Two-factor authentication (`mfa/`) and
login with GitHub and Google (`social/`) are also called from `auth/`, so they take a few more steps. Either can be
removed on its own. Both change the API, so clients and `scripts/client.sh` output must follow.

Leave `V7__two_factor_and_social_login.sql` as it is; it has run in every existing database. Drop the tables in a new
migration instead (`V10`, or `V11` for the second feature you remove). Back up first: for two-factor authentication,
this turns it off for every enrolled user for good.

### Social login

1. Delete `social/` in `src/main` and `src/test`, and `auth/dto/SocialLoginRequest` and `SocialProviderResponse`.
2. `AuthService`: delete the fields `socialProviders`, `socialProfiles` and `socialAccounts` (with their constructor
   parameters) and the methods `socialLogin` and `socialProviders`.
3. `AuthController`: delete `loginProviders` and `loginWithProvider`.
4. `AppProperties`: delete the `socialLogin` component and the `SocialLogin` record. In `ProductionSettingsCheck`,
   delete the `OAUTH2_REDIRECT_URI` check, and drop the `SocialLogin` argument in `TestProperties`,
   `ProductionSettingsCheckTest`, `AdminBootstrapTest` and `SecretCipherTest`.
5. Delete `PROVIDER_NOT_CONFIGURED`, `SOCIAL_EMAIL_UNVERIFIED` and `SOCIAL_ACCOUNT_CONFLICT` from `ProblemType`,
   `SOCIAL_ACCOUNT_LINKED` from `AuditEvent`, and `/api/v1/auth/oauth2/*` from `SecurityConfig.PUBLIC_PATHS`.
6. Delete `app.social-login` from `application.yml` and `application-test.yml`, the GitHub/Google block from
   `.env.example`, `OAUTH2_REDIRECT_URI` from `deploy/kubernetes/configmap.yaml`, the
   `spring-security-oauth2-client` dependency from `pom.xml`, and `SocialProfileClient` from `spotbugs-exclude.xml`.
7. Add the migration:

   ```sql
   DROP TABLE IF EXISTS social_identities;
   ```

Accounts created through GitHub or Google stay; their users set a password through the password reset.

### Two-factor authentication

1. Delete `mfa/` in `src/main` and `src/test`, and `auth/MfaChallengeService`, `auth/LoginResult`,
   `auth/dto/MfaLoginRequest` and `MfaChallengeResponse`. Remove `implements LoginResult` from `AuthTokens`.
2. `AuthService`: delete the fields `mfa` and `challenges` and the methods `loginWithMfa`, `startSession` and
   `mfaChallengeExpired`. `login` and `socialLogin` return `AuthTokens` from `issueTokens(...)`, and `login` always
   resets the account's failed-login counter after a correct password.
3. `AuthController`: delete `loginWithMfa` and `loginResponse`; `login` and `loginWithProvider` return
   `tokenResponse(...)`, and lose their `202` responses.
4. `User`: delete the `totp*` fields and methods. `UserResponse`: delete `mfaEnabled`.
5. `AppProperties`: delete the `mfa` component and the `Mfa` record, and drop the argument in `TestProperties`,
   `ProductionSettingsCheckTest` and `AdminBootstrapTest`.
6. Delete `INVALID_MFA_CODE`, `MFA_CHALLENGE_EXPIRED`, `TOTP_ALREADY_ENABLED` and `TOTP_NOT_ENABLED` from
   `ProblemType`, the four `MFA_*`/`RECOVERY_CODE_USED` events from `AuditEvent`, `/api/v1/auth/login/mfa` from
   `SecurityConfig.PUBLIC_PATHS`, and `MfaChallengeService` from `spotbugs-exclude.xml`.
7. Delete `app.mfa` from `application.yml` and `application-test.yml`, `MFA_ENCRYPTION_KEY` from `.env.example`,
   `scripts/setup.sh`, `scripts/setup.bat` and the secret in `deploy/kubernetes/README.md`.
8. Add the migration:

   ```sql
   DROP TABLE IF EXISTS recovery_codes;
   ALTER TABLE users
       DROP COLUMN IF EXISTS totp_secret,
       DROP COLUMN IF EXISTS totp_enabled,
       DROP COLUMN IF EXISTS totp_last_step;
   ```

### Then

Regenerate the OpenAPI document right away; until then, every integration test that returns a user fails the
contract check (removing `mfaEnabled` alone breaks about 60):

```bash
scripts/test.sh -Dit.test=OpenApiSpecExportIntegrationTest -Dopenapi.update=true
scripts/test.sh
```

Finally remove the feature's sections from the guides (`security.md`, `api.md`, `configuration.md`, `production.md`)
and from the README.

## Getting fixes from the template later

A project made from the template does not share its git history, so updates are not merged automatically. Watch the
repository's releases (*Watch → Custom → Releases*); every release lists its changes in the
[changelog](https://github.com/hikmethankolay/spring-boot-backend-template/blob/main/CHANGELOG.md), and security fixes
are under a **Security** heading. To bring a change over, fetch the template as a second remote and cherry-pick the
commits named in the release notes:

```bash
git remote add template https://github.com/hikmethankolay/spring-boot-backend-template.git
git fetch template --tags
git log --oneline v4.0.2..template/main        # what changed since the version you started from
git cherry-pick -x <commit>                      # -x records where the change came from
```

`rename-project` moved the Java files and rewrote their package lines, so git usually follows the move but reports
conflicts on the `package` and `import` lines: keep your package names and the template's change. For a commit that
touches many files, `git diff <old-tag> <new-tag> -- src/main/resources` and similar per-directory diffs are often
easier to apply by hand. Note the template version you started from (`git describe --tags` in the template, or the
first entry of its changelog) in your own README.

## Rules the build enforces

`ArchitectureTest` (ArchUnit) fails the build if:

- a controller uses a repository or a JPA entity directly
- anything depends on a controller
- a class uses field injection, `System.out` or `java.util.logging`

`scripts/docs.sh` fails when a public, protected or package-private class, method or field has no Javadoc comment.

## Project structure

```
src/main/java/com/hikmethankolay/template/
├── auth/        login (with the two-factor step), refresh-token rotation, logout, session revocation
├── account/     email verification and password reset: tokens, mailer, endpoints
├── mfa/         two-factor authentication: TOTP enrollment, encrypted secrets, recovery codes
├── social/      login with GitHub and Google: profile client, linked identities
├── user/        User entity, service, controller, account deletion, data export, admin bootstrap
├── note/        sample feature: notes owned by a user (copy it, then delete it)
├── security/    filter chains, headers, JWT, token revocation, login lockout, rate limiting
├── audit/       AuditLog and AuditEvent: the security audit trail
├── mail/        EmailOutbox: transactional outbox with retries
├── common/      ApiException, RFC 9457 error handler, X-Trace-Id filter, SecureTokens
└── config/      AppProperties (typed app.* config), Redis cache, OpenAPI
src/main/resources/
├── application.yml        all configuration, overridable via env/.env
├── application-prod.yml   production overrides
└── db/migration/          Flyway migrations
deploy/kubernetes/   Kustomize manifests
ops/                 Prometheus alert rules, Grafana dashboard
docs/                Astro Starlight documentation site
load-tests/          k6 scripts
scripts/             *.sh and *.bat for every task
```
