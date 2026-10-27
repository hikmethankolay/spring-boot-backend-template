---
title: Compared with other starters
description: When to use this template, and when Spring Initializr, JHipster or Bootify fits better.
---

Each of these gets you a Spring Boot project. They differ in how much they decide for you and in what you get to
keep.

| | This template | [Spring Initializr](https://start.spring.io) | [JHipster](https://www.jhipster.tech) | [Bootify](https://bootify.io) |
|---|---|---|---|---|
| What you get | A working REST backend you copy and own | An empty project with the dependencies you picked | A generated full-stack application | A generated backend from entities you define in the browser |
| Frontend | None; [typed client and a guide](../frontend/) for yours, [clients in other languages](../api/#clients-in-other-languages) for mobile apps | None | Angular, React or Vue, generated | Optional server-side pages (Thymeleaf) |
| Login and accounts | JWT with refresh rotation, email verification, password reset, HTML email templates, email preferences with one-click unsubscribe, security alert emails, owner broadcasts, two-factor login, GitHub and Google login, session list, lockout, rate limiting | Add them yourself | Generated, several options | Spring Security in the paid plans |
| Production setup | `prod` profile with a startup check, metrics, alert rules, dashboard, tracing, signed images, Kubernetes manifests, runbooks | Add it yourself | Generated, several options | Varies by plan |
| Tests | 380+ tests, 170 of them against real PostgreSQL and Redis, architecture rules, mutation testing | One context test | Generated | Integration tests in the paid plans |
| Tooling to run it | JDK 25 and Docker, or only Docker, or a Codespace | A JDK | Node.js and a JDK | A browser, then a JDK |
| Updates later | [Cherry-pick from the template](../new-project/#getting-fixes-from-the-template-later) | Upgrade Spring Boot yourself | Regenerate and merge | Regenerate |

## Pick this template when

- you are building a **REST API** for your own web or mobile frontend, and want login, accounts and production
  plumbing done and tested from the first commit;
- you want **plain, readable code** you will change by hand, not a generator's output or its configuration language;
- one service with PostgreSQL and Redis fits your needs.

## Pick something else when

- **You want an empty project** and to choose everything yourself: Spring Initializr.
- **You want the frontend generated as well**, or a microservice setup with a gateway and service discovery:
  JHipster.
- **Your app is mostly CRUD screens over many entities**, and you would rather model them in a browser: Bootify.
- **You need SSO across many services**, or an identity provider your company already runs: keep only the
  resource-server part of this template and delegate login (see
  [Trade-offs to revisit](../production/#trade-offs-to-revisit)).
- **Your team uses Gradle or Kotlin**: the template uses Maven and Java, and converting it is work you would do once.
