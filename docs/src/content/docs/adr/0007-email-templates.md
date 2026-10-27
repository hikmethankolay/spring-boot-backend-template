---
title: 'ADR 7: Emails are Thymeleaf templates with a text twin'
description: Why emails are rendered from Thymeleaf templates in the application instead of MJML or provider templates.
---

**Status:** accepted

## Context

The account emails were plain-text strings in `AccountMailer`. Users expect HTML email, but HTML email is hostile
territory: many clients drop `<style>` blocks, Outlook renders with Word, and an unescaped username in a template is
an injection. The template must keep working with any SMTP server (Mailpit, SES, a company relay), so the email must
be complete when it leaves the application. The build has no Node toolchain.

Options considered:

- **Provider-hosted templates** (SendGrid, Postmark, SES templates): tie the template to one provider's API and
  leave nothing to test locally.
- **MJML**: produces robust markup, but needs a Node build step or a second runtime.
- **Thymeleaf in the application**: one Java dependency, escapes values by default, works with any SMTP server.

## Decision

Each email is a Thymeleaf template pair in `src/main/resources/mail/`: `<name>.html`, wrapped in a shared
`layout.html`, and `<name>.txt` with the same variables. `MailRenderer` renders both with its own `TemplateEngine`
(Thymeleaf core, not the Spring Boot starter, so no web view resolver is registered), and the outbox sends them as
`multipart/alternative`. Styles are inline attributes in the layout and the button fragment only; page templates
contain no styles and inherit fonts and colours from the layout's content cell, so no CSS inliner is needed.

See [ADR 3](../0003-email-outbox/) for how the rendered email is queued and sent.

## Consequences

- Values are escaped (`th:text`, `th:href`); `th:utext` and `[(...)]` are banned in HTML templates, which a unit
  test checks.
- CSS is limited to what survives email clients; the allowed properties are listed at the top of `layout.html`.
- Every HTML template has a snapshot under `src/test/resources/mail-snapshots/`; a change to a template fails the
  build until the new rendering is accepted.
- `email_outbox.html_body` is nullable, so plain-text emails and rows queued before the change still send.
