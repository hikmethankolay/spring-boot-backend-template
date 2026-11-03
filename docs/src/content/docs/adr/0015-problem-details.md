---
title: 'ADR 15: Problem details for errors'
description: Why every error is an RFC 9457 problem detail with a stable urn:problem type, and how a feature adds one.
---

**Status:** accepted

## Context

Clients need to tell errors apart: a `409` can mean a taken username, a note limit or a concurrent write, and a `429`
can be the rate limit or a login lockout. Matching on the `detail` text breaks whenever a message is reworded. Errors
also come from three places with different defaults: controllers and services, Spring MVC itself (malformed JSON, an
unsupported media type) and the security filter chain (`401`, `403`, `429`).

## Decision

**RFC 9457 everywhere.** Every error is a problem detail with the content type `application/problem+json`.
`GlobalExceptionHandler` extends Spring's `ResponseEntityExceptionHandler`, so Spring MVC's own exceptions get the same
shape. Errors raised before a controller are routed to it too: `SecurityProblemHandler` (authentication entry point
and access-denied handler) and `RateLimitFilter` pass the exception to the `handlerExceptionResolver` bean instead of
writing a response themselves.

**A stable type per error.** `ProblemType` is an enum of codes; each becomes the `type` URN `urn:problem:<code>`, e.g.
`urn:problem:username-taken`. URNs promise nothing to dereference, so there is no documentation page to host per
type. Spring MVC's own errors keep `about:blank`: `GlobalExceptionHandler.createResponseEntity` sets it where Spring 7
leaves `type` out. Each constant carries a one-line description, and `OpenApiConfig` lists them all in the description
of `Problem.type`, so the OpenAPI document cannot drift from the code.

**One exception for services.** `ApiException` extends Spring's `ErrorResponseException` and is built from a status,
a `ProblemType` and a `detail`, with factories `badRequest`, `notFound`, `conflict`, `preconditionFailed` and
`tooManyRequests`; the last sets `Retry-After`. Services throw it and the base handler renders it, so no controller
catches anything. `GlobalExceptionHandler` maps the rest: `AuthenticationException` to `401` (`invalid-credentials` or
`unauthenticated`, with `WWW-Authenticate: Bearer`), `AccessDeniedException` to `403` `forbidden`, constraint
violations and optimistic locking failures to `409` `conflict`, an unknown sort property to `400` `invalid-sort`, and
anything else to `500` `internal-error`, logged with its stack trace.

**Few extra members.** Validation failures, of a `@Valid` body or of `@RequestParam` and `@PathVariable` constraints,
add `errors`: a list of `{field, message}`, with an empty `field` for an object-level rule. The trace id is not a
member; it is the `X-Trace-Id` header on every response, which the `internal-error` description tells clients to
quote.

**Adding a type.** A feature adds a constant to `ProblemType`, throws it with an `ApiException` factory and adds a row
to the [Errors table](../../guides/api/#errors), as "Add an error type" in `ARCHITECTURE.md` describes.

## Consequences

- The type list is part of the API contract: renaming a code breaks clients that match on it. Add a new type rather
  than change one.
- The Errors table in the API guide is written by hand; only the OpenAPI description is generated from the enum.
- Spring MVC's errors share `about:blank`, so clients must use the status for them. Give one a type of its own when a
  client needs to react to it specifically.
