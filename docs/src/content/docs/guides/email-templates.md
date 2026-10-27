---
title: Email templates
description: The emails the app sends, how they are rendered, and how to add one.
---

Every email is rendered from a template pair in `src/main/resources/mail/` and sent through the
[outbox](../observability/#email-outbox) as `multipart/alternative`: clients show the HTML part and fall back to the
text part. [ADR 7](../../adr/0007-email-templates/) explains the choice.

## What gets sent

| Template | Subject | Sent when | Variables |
| --- | --- | --- | --- |
| `sign-up-link` | Finish creating your account | Someone starts a sign-up with a new address | `link`, `ttl` |
| `already-registered` | You already have an account | Someone starts a sign-up with a registered address | — |
| `verify-email` | Confirm your email address | An address changes and must be confirmed | `link`, `ttl` |
| `password-reset` | Reset your password | Someone asks for a password reset | `link`, `ttl` |

Every template also gets `subject` and `appName` (`spring.application.name`).

## Adding an email

1. Write `src/main/resources/mail/<name>.html`. It replaces its `<html>` with the layout and supplies only the body:

   ```html
   <!DOCTYPE html>
   <html xmlns:th="http://www.thymeleaf.org" th:replace="~{layout.html :: layout(~{::content})}">
   <body>
   <th:block th:fragment="content">
       <p>Your export is ready.</p>
       <th:block th:replace="~{button.html :: button(${link}, 'Download')}"></th:block>
       <p>The link expires in <span th:text="${ttl}">24 hours</span>.</p>
   </th:block>
   </body>
   </html>
   ```

2. Write `<name>.txt` with the same variables, printed with `[(${link})]`. Keep lines under 78 characters.
3. Render and queue it inside the transaction of the change it reports:

   ```java
   outbox.enqueue(to, renderer.render("export-ready", "Your export is ready", Map.of("link", link, "ttl", ttl)));
   ```

4. Add the template to the lists in `MailRendererTest`, run it once, check `target/mail-preview/<name>.html` in a
   browser and copy it to `src/test/resources/mail-snapshots/`.

## Categories and unsubscribe

Every email belongs to a `MailCategory`. `ACCOUNT` and `SECURITY` are transactional: always sent, never with an
unsubscribe link, and they cannot be turned off through any API. `PRODUCT_UPDATES` is optional: on by default, and
users turn it off on the preferences page or with the link in each such email ([ADR 8](../../adr/0008-unsubscribe/)).

Send an optional email with the category-aware `enqueue`, which skips users who turned the category off and adds the
footer and the one-click `List-Unsubscribe` headers itself:

```java
outbox.enqueue(userId, to, MailCategory.PRODUCT_UPDATES, "broadcast/announcement", "What's new",
        Map.of("title", "What's new", "paragraphs", List.of("…")));
```

To add an optional category, add a constant with `optional = true` and a description users understand: the
preferences endpoint, the unsubscribe links and the data export pick it up.

## Layout and CSS rules

`layout.html` holds the 600 px table container, the app name and the footer. `button.html` is the call-to-action: a
link in a padded cell, which renders in Outlook too, with the raw URL printed below it. Every email with a link says
how long it is valid and that it can be ignored if it was not requested.

- Styles are inline `style` attributes in `layout.html` and `button.html` only. Many clients drop `<style>` blocks,
  so there are none, and page templates need no styles: they inherit the font and colour of the content cell.
- Use tables for layout, not flexbox or grid. The properties that work everywhere are listed at the top of
  `layout.html`.
- Print values with `th:text` or `th:href`, which escape them. Never `th:utext` or `[(...)]` in an HTML template:
  `MailRendererTest` fails the build if one appears.

## Previewing

- **In a browser**: every test run writes the HTML of each template, rendered with sample data, to
  `target/mail-preview/`.
- **As received**: with `scripts/dev.sh`, every email lands in Mailpit at <http://localhost:8025>. Its HTML, Text and
  Raw tabs show both parts and the `multipart/alternative` structure.

## Snapshot tests

`MailRendererTest` compares each rendering with `src/test/resources/mail-snapshots/<name>.html`. After an intended
change it fails and names the preview to accept: copy `target/mail-preview/<name>.html` over the snapshot and commit
both, so the diff of the rendered HTML is part of the review.

## What not to do

- Do not build an email body as a string in Java; add a template.
- Do not send from a request thread or after the commit; queue with `EmailOutbox.enqueue` in the transaction.
- Do not put tokens anywhere but the link the email exists for, and never log them.
