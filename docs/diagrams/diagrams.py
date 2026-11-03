"""Draws every documentation diagram into docs/public/diagrams/, in light and dark.

Run after changing a diagram: python3 docs/diagrams/diagrams.py
"""

import data
import flows
import ops
from lib import Diagram, Sequence


def architecture():
    d = Diagram("architecture", 720, 580, "Spring Boot Backend Template architecture",
                "Clients call the Spring Boot app through its security filters, with a JWT or a personal access "
                "token; controllers hand requests to services, which use PostgreSQL and Redis, send email over "
                "SMTP and call GitHub or Google and webhook receivers over HTTPS; Prometheus scrapes the app.")
    d.zone(24, 128, 672, 256, "SPRING BOOT APP")
    d.wire((190, 96), (190, 168), accent=True)
    d.label(200, 116, "HTTPS", anchor="start")
    d.wire((540, 96), (540, 168), dashed=True)
    d.label(550, 116, "SCRAPE", anchor="start")
    d.wire((328, 204), (400, 204))
    d.wire((540, 248), (540, 288))
    for x, dashed in ((102, False), (274, False), (446, True), (618, False)):
        d.wire((x, 360), (x, 424), dashed=dashed)
    d.node(40, 24, 300, 72, "Browser, mobile app or service", "Bearer header or HttpOnly cookie", tag="CLIENT")
    d.node(400, 24, 280, 72, "Prometheus and Grafana", "scrapes /actuator/prometheus", tag="MONITORING",
           kind="box-ext")
    d.node(48, 168, 280, 72, "Security filters", ["trace id · rate limit · CSRF", "JWT or pat_ access token"], tag="FILTERS",
           kind="box-focal")
    d.node(400, 168, 272, 80, "REST controllers", ["/api/v1 · /actuator", "/.well-known/jwks.json"], tag="WEB")
    d.node(48, 288, 624, 72, "Services and transactions",
           "auth · account · mfa · social · user · token · note · webhook · audit · mail outbox", tag="DOMAIN")
    d.node(24, 424, 156, 96, "PostgreSQL 18", ["Flyway schema", "access tokens"], tag="STORE")
    d.node(196, 424, 156, 96, "Redis 8", ["limits · revocation", "MFA · idempotency", "cache"], tag="STORE")
    d.node(368, 424, 156, 96, "SMTP server", ["via the outbox", "Mailpit in dev"], tag="EXTERNAL", kind="box-ext")
    d.node(540, 424, 156, 96, "Outbound HTTPS", ["GitHub/Google OAuth", "webhook receivers", "optional"],
           tag="EXTERNAL", kind="box-ext")
    d.legend(564, [("box", "runs with the app"), ("box-ext", "external or optional"), ("box-focal", "entry point"),
                   ("dash", "asynchronous")])
    d.save()


def request_pipeline():
    d = Diagram("request-pipeline", 720, 944, "How a request is checked",
                "Every request passes the trace, rate-limit and CSRF filters; public paths go straight to the "
                "controller, other requests need a valid, unrevoked JWT or a personal access token and the right "
                "role or token scope; a signed-in POST with an Idempotency-Key replays the stored response of a "
                "repeated key; every rejection and exception becomes an RFC 9457 problem detail.")
    cx, steps = 280, (148, 276, 404, 532, 660, 788)
    d.wire((cx, 64), (cx, steps[0] - 44))
    for i, (a, b) in enumerate(zip(steps, steps[1:])):
        d.wire((cx, a + 44), (cx, b - 44))
        d.label(cx + 10, (a + b) / 2 + 3, "NO" if i == 2 else "PASS", anchor="start")
    d.wire((cx, steps[-1] + 44), (cx, 872), accent=True)
    d.label(cx + 10, 855, "NO KEY · NEW KEY", anchor="start", accent=True)
    for y, status in ((148, "429"), (276, "403"), (532, "401"), (660, "403"), (788, "409 · 422")):
        d.wire((396, y), (520, y))
        d.label(458, y - 9, status)
    d.wire((164, 404), (40, 404), (40, 896), (200, 896))
    d.label(48, 590, "YES · NO TOKEN", anchor="start")
    d.wire((164, 788), (118, 788), (118, 832))
    d.label(110, 813, "REPLAY", anchor="end")
    d.wire((360, 896), (608, 896), (608, 816), dashed=True)
    d.label(480, 887, "EXCEPTION")
    d.node(180, 24, 200, 40, "HTTP request", shape="oval")
    d.node(164, 104, 232, 88, "Rate limit", "RateLimitFilter", shape="diamond")
    d.node(164, 232, 232, 88, "CSRF origin", "CookieCsrfFilter", shape="diamond")
    d.node(164, 360, 232, 88, "Public path?", "SecurityFilterChain", shape="diamond")
    d.node(164, 488, 232, 88, "JWT or pat_ token", "Redis · PostgreSQL", shape="diamond", kind="box-focal")
    d.node(164, 616, 232, 88, "Role, token scope", "@PreAuthorize", shape="diamond")
    d.node(164, 744, 232, 88, "Idempotency-Key?", "IdempotencyFilter", shape="diamond")
    d.node(52, 832, 132, 48, "Replay", "stored response", shape="oval")
    d.node(200, 872, 160, 48, "Controller", shape="oval")
    d.node(520, 120, 176, 696, "Problem detail",
           ["RFC 9457 JSON", "SecurityProblemHandler", "GlobalExceptionHandler", "JSON over 1 MiB: 400"])
    d.save()


def auth_sequence():
    d = Diagram("auth-sequence", 720, 628, "Login, two-factor step and refresh",
                "The client logs in with a password, answers the two-factor challenge if it is on, receives an "
                "access JWT and a refresh token, and later exchanges the refresh token once for a new pair.")
    s = Sequence(d, [(96, "Client", "browser or app", "box"), (280, "Spring Boot API", "AuthService", "box"),
                     (464, "PostgreSQL", "refresh_tokens", "box"), (636, "Redis", "MFA challenges", "box")],
                 box_w=164, bottom=576)
    s.msg(0, 1, 108, "POST /auth/login")
    s.msg(1, 2, 144, "check password hash")
    d.frame(24, 164, 688, 160, "OPT", "[two-factor authentication is on]")
    s.msg(1, 3, 200, "store challenge", at=550)
    s.msg(1, 0, 236, "202  mfaToken", kind="return")
    s.msg(0, 1, 272, "POST /login/mfa")
    s.msg(1, 3, 308, "consume challenge", at=550)
    s.msg(1, 2, 360, "save refresh-token hash")
    s.msg(1, 0, 396, "200  access + refresh", kind="accent")
    s.divider(436, "15 MIN LATER: 401", at=372)
    s.msg(0, 1, 476, "POST /auth/refresh")
    s.msg(1, 2, 512, "mark used, new token")
    s.msg(1, 0, 548, "200  new pair", kind="return")
    d.text(360, 608, "Each refresh token works once: presenting a used one again revokes the whole session.",
           anchor="middle")
    d.save()


def email_outbox():
    d = Diagram("email-outbox", 720, 612, "Transactional email outbox",
                "An email is stored in the same transaction as the change that triggers it, sent right after "
                "the commit, and retried by a scheduled dispatcher with backoff until it is sent or, after max-attempts, "
                "kept for a while as a dead letter.")
    s = Sequence(d, [(96, "AccountService", "@Transactional", "box"), (280, "EmailOutbox", "mail package", "box"),
                     (464, "PostgreSQL", "email_outbox", "box"), (636, "SMTP server", "Mailpit in dev", "box-ext")],
                 box_w=164, bottom=508)
    s.msg(0, 1, 108, "enqueue(rendered email)")
    s.msg(1, 2, 144, "INSERT email_outbox")
    s.msg(0, 2, 180, "COMMIT change + email", kind="accent", at=372)
    s.msg(1, 2, 216, "lock row, SKIP LOCKED")
    s.msg(1, 3, 252, "send", at=550)
    s.msg(3, 1, 288, "accepted or error", kind="return", at=550)
    s.msg(1, 2, 324, "DELETE, or retry later")
    d.frame(200, 356, 512, 132, "LOOP", "[every outbox-poll-interval]")
    s.msg(1, 2, 396, "lock due, SKIP LOCKED")
    s.msg(1, 3, 432, "send", at=550)
    s.msg(1, 2, 468, "delete, retry, or fail")
    d.text(360, 544, "Sent emails are deleted; after max-attempts one stays a dead letter, 7 days by default.",
           anchor="middle")
    d.text(360, 568, "Dead letters can be re-queued; transactional ones lost their one-time link and are dropped.",
           anchor="middle")
    d.text(360, 592, "Retries wait retry-delay × 2ⁿ, at most an hour; each poll sends due emails for one interval.",
           anchor="middle")
    d.save()


if __name__ == "__main__":
    for draw in [architecture, request_pipeline, auth_sequence, email_outbox, *flows.ALL, *data.ALL, *ops.ALL]:
        draw()
