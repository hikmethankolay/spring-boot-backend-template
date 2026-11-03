---
title: 'ADR 13: Optimistic locking with ETag and If-Match'
description: Why users and notes carry a JPA version that clients see as an ETag and send back as If-Match.
---

**Status:** accepted

## Context

Two writers of the same row overwrite each other: a profile edit racing a demotion could restore the old role, and two
tabs editing one note keep whichever saves last. Inside one request JPA can detect this; across requests the client
reads a resource, edits it and sends it back later, and the server cannot tell what that edit was based on. HTTP has
conditional requests for this (RFC 9110): the server sends an `ETag`, the client returns it as `If-Match`.

## Decision

**A version column per row.** `users.version` (`V6__user_versions.sql`) and `notes.version` (`V8__notes.sql`) are
`BIGINT NOT NULL DEFAULT 0`, mapped with `@Version` on `User` and `Note`. Hibernate bumps the version on every update
and fails a write based on a stale read with `OptimisticLockingFailureException`, which `GlobalExceptionHandler` maps
to `409` `urn:problem:conflict`. `UserRepository.upgradePasswordHash` skips the check on purpose: rehashing the same
password at login must not fail because another field changed.

**The version is the ETag.** Services return `Versioned<T>`, the body plus the entity's version, and
`Versioned.toResponse()` sends it as a strong `ETag` such as `"3"`; strong, because `If-Match` only matches strong tags.
`GET /api/v1/users/me` and `GET /api/v1/notes/{id}` carry it, and Spring answers `If-None-Match` with `304`. The user
is read from the database, not the user cache: the version also changes on writes that do not evict the cached
`UserResponse`, so a cached tag could make every `If-Match` fail.

**Checked before the change.** `PATCH /api/v1/users/me` and `PUT /api/v1/notes/{id}` pass `If-Match` to
`Versioned.checkIfMatch`, which refuses a tag other than the current one or `*` with `412`
`urn:problem:precondition-failed`; weak tags never match. The service then calls `saveAndFlush`, so the new `ETag` and
a race between two requests that both passed the check (`409`) are in this response.

**Optional.** Without `If-Match` the update applies to the current version, as before; there is no
`428 Precondition Required`, so existing clients keep working.

## Consequences

- A `412` does not always mean a visible change: the user's version also moves when, for example, every session ends.
- Only those two updates check `If-Match`. Deleting a note and the administrators' `PATCH /api/v1/users/{id}` still
  rely on `@Version` alone; add the header where lost updates by people matter.
- CORS in `SecurityConfig` exposes `ETag` and allows `If-Match` and `If-None-Match`, so a browser client on another
  origin can use them; a new conditional header has to be added there too.
- Make `If-Match` required (`428`) once every client sends it. See
  [Concurrent updates](../../guides/api/#concurrent-updates).
