"""Draws every documentation diagram into docs/public/diagrams/, in light and dark.

Run after changing a diagram: python3 docs/diagrams/diagrams.py
"""

import data
import flows
import ops
from lib import Diagram, Sequence


def architecture():
    d = Diagram("architecture", 720, 568, "Spring Boot Backend Template architecture",
                "Clients call the Spring Boot app through its security filters; controllers hand requests to "
                "services, which use PostgreSQL and Redis and reach SMTP and GitHub or Google; Prometheus scrapes "
                "the app.")
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
    d.node(48, 168, 280, 72, "Security filters", "trace id · rate limit · CSRF · JWT", tag="FILTERS",
           kind="box-focal")
    d.node(400, 168, 272, 80, "REST controllers", ["/api/v1 · /actuator", "/.well-known/jwks.json"], tag="WEB")
    d.node(48, 288, 624, 72, "Services and transactions",
           "auth · account · mfa · social · user · note · audit · mail outbox", tag="DOMAIN")
    d.node(24, 424, 156, 84, "PostgreSQL 18", ["Flyway schema"], tag="STORE")
    d.node(196, 424, 156, 84, "Redis 8", ["limits · revocation", "MFA · cache"], tag="STORE")
    d.node(368, 424, 156, 84, "SMTP server", ["via the outbox", "Mailpit in dev"], tag="EXTERNAL", kind="box-ext")
    d.node(540, 424, 156, 84, "GitHub, Google", ["OAuth 2 + PKCE", "optional"], tag="EXTERNAL", kind="box-ext")
    d.legend(552, [("box", "runs with the app"), ("box-ext", "external or optional"), ("box-focal", "entry point"),
                   ("dash", "asynchronous")])
    d.save()


def request_pipeline():
    d = Diagram("request-pipeline", 720, 816, "How a request is checked",
                "Every request passes the trace, rate-limit and CSRF filters; public paths go straight to the "
                "controller, other requests need a valid, unrevoked JWT and the right role; every rejection and "
                "exception becomes an RFC 9457 problem detail.")
    cx, steps = 280, (148, 276, 404, 532, 660)
    d.wire((cx, 64), (cx, steps[0] - 44))
    for i, (a, b) in enumerate(zip(steps, steps[1:])):
        d.wire((cx, a + 44), (cx, b - 44))
        d.label(cx + 10, (a + b) / 2 + 3, "NO" if i == 2 else "PASS", anchor="start")
    d.wire((cx, steps[-1] + 44), (cx, 744), accent=True)
    d.label(cx + 10, 727, "PASS", anchor="start", accent=True)
    for y, status in ((148, "429"), (276, "403"), (532, "401"), (660, "403")):
        d.wire((396, y), (520, y))
        d.label(458, y - 9, status)
    d.wire((164, 404), (112, 404), (112, 768), (200, 768))
    d.label(104, 590, "YES · NO TOKEN", anchor="end")
    d.wire((360, 768), (608, 768), (608, 688), dashed=True)
    d.label(480, 759, "EXCEPTION")
    d.node(180, 24, 200, 40, "HTTP request", shape="oval")
    d.node(164, 104, 232, 88, "Rate limit", "RateLimitFilter", shape="diamond")
    d.node(164, 232, 232, 88, "CSRF origin", "CookieCsrfFilter", shape="diamond")
    d.node(164, 360, 232, 88, "Public path?", "SecurityFilterChain", shape="diamond")
    d.node(164, 488, 232, 88, "JWT + revocation", "JwtDecoder · Redis", shape="diamond", kind="box-focal")
    d.node(164, 616, 232, 88, "Role", "@PreAuthorize", shape="diamond")
    d.node(200, 744, 160, 48, "Controller", shape="oval")
    d.node(520, 120, 176, 568, "Problem detail",
           ["RFC 9457 JSON", "SecurityProblemHandler", "GlobalExceptionHandler"])
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
    d = Diagram("email-outbox", 720, 588, "Transactional email outbox",
                "An email is stored in the same transaction as the change that triggers it, sent right after "
                "the commit, and retried by a scheduled dispatcher with backoff until it is sent or dropped.")
    s = Sequence(d, [(96, "AccountService", "@Transactional", "box"), (280, "EmailOutbox", "mail package", "box"),
                     (464, "PostgreSQL", "email_outbox", "box"), (636, "SMTP server", "Mailpit in dev", "box-ext")],
                 box_w=164, bottom=508)
    s.msg(0, 1, 108, "enqueue(email)")
    s.msg(1, 2, 144, "INSERT email_outbox")
    s.msg(0, 2, 180, "COMMIT change + email", kind="accent", at=372)
    s.msg(1, 2, 216, "lock row, SKIP LOCKED")
    s.msg(1, 3, 252, "send", at=550)
    s.msg(3, 1, 288, "accepted or error", kind="return", at=550)
    s.msg(1, 2, 324, "DELETE, or retry later")
    d.frame(200, 356, 512, 132, "LOOP", "[every outbox-poll-interval]")
    s.msg(1, 2, 396, "lock due, SKIP LOCKED")
    s.msg(1, 3, 432, "send", at=550)
    s.msg(1, 2, 468, "delete, retry, or drop")
    d.text(360, 544, "Sent and dropped emails are deleted, so links with tokens do not linger in the database.",
           anchor="middle")
    d.text(360, 568, "Retries wait retry-delay × 2ⁿ, at most an hour, until max-attempts; then the email is dropped.",
           anchor="middle")
    d.save()


if __name__ == "__main__":
    for draw in [architecture, request_pipeline, auth_sequence, email_outbox, *flows.ALL, *data.ALL, *ops.ALL]:
        draw()
