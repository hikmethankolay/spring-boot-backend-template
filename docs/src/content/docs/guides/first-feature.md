---
title: Your first feature
description: The sample notes feature, file by file, and how to copy or delete it.
---

The `note/` package is a complete, tested feature small enough to read in a few minutes: users create, list, edit and
delete their own notes at `/api/v1/notes`. Copy it as the starting point of your first feature, then delete it.

## The files

| File | What it shows |
|------|---------------|
| `db/migration/V8__notes.sql` | A table owned by a user: `user_id` references `users(id)` with `ON DELETE CASCADE`, so deleting an account deletes its rows; an index on the foreign key; a `version` column |
| `note/Note.java` | The entity: owner stored as a plain id, timestamps set in `@PrePersist`/`@PreUpdate`, `@Version` for optimistic locking |
| `note/NoteRepository.java` | Every query takes the owner's id (`findByIdAndUserId`, `findAllByUserId`), so a forgotten check cannot leak another user's row |
| `note/dto/NoteRequest.java`, `NoteResponse.java` | Validated request body; response mapped from the entity with `from(...)` (entities are never serialized) |
| `note/NoteService.java` | Transactions (`readOnly` by default), the sort allow-list, and a `404` for missing *and* foreign notes |
| `note/NoteController.java` | Thin controller: caller id from the token (`TokenService.userId(jwt)`), `201` + `Location`, OpenAPI annotations with an `operationId` per operation |
| `note/NoteDataProvider.java` | Adds the notes to the personal data export (`GET /api/v1/users/me/export`) |
| `test/.../note/NoteIntegrationTest.java` | End-to-end tests against PostgreSQL, including the "other user gets 404" case |

## Decisions worth copying

- **Ownership lives in the query, not in an `if`.** The service never loads a note by id alone. A note of someone else
  is answered exactly like a missing one (`404 note-not-found`), so ids cannot be probed. Use `@PreAuthorize` on top
  when a role is needed, as in `UserController`.
- **Sorting is allow-listed.** `NoteService.SORTABLE_PROPERTIES` names the fields clients may sort by;
  `@SortableBy` documents it in the OpenAPI document and `SortAllowList` enforces it.
- **Simultaneous writes fail loudly.** When two `PUT`s on the same note run at the same moment, `@Version` makes the
  second one fail with `409 conflict` instead of silently winning. It does not stop a client from overwriting a
  change it never saw; for that, return the version to clients and require it back (`If-Match`).
- **No new error handling.** `ApiException.notFound(ProblemType.NOTE_NOT_FOUND, …)` becomes a problem detail through
  the global handler; add your own `ProblemType` constants the same way.

## Turning it into your feature

1. Copy `note/` to your package (say `order/`) and rename the classes.
2. Add a migration with the next free number (`V13__orders.sql`); never edit an applied one.
3. Add your problem types to `ProblemType` and to the table in [API](../api/#errors).
4. Copy `NoteIntegrationTest` and adapt it.
5. Regenerate the OpenAPI document and check the diff:

   ```bash
   scripts/test.sh -Dit.test=OpenApiSpecExportIntegrationTest -Dopenapi.update=true
   git diff api/openapi.json
   ```

## Deleting the sample

Before your first deployment, remove `src/main/java/.../note/`, `src/test/java/.../note/`,
`src/main/resources/db/migration/V8__notes.sql` and the `NOTE_NOT_FOUND` constant, then regenerate the OpenAPI
document as above. Once `V8` has run against a database you keep, leave the file in place and drop the table with a
new migration (`DROP TABLE notes;`) instead.
