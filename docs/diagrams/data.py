"""Data diagrams: the database tables, two-factor enrollment states and session lifetimes."""

from html import escape

from lib import Diagram, width


def table(d, x, y, w, name, rows, kind="box", note=None):
    """An ER table: bold name over (column, key) rows; registered as a box so the geometry checks apply."""
    h = 36 + 18 * len(rows)
    out = d.layers["nodes"]
    out.append(f'<rect class="mask" x="{x}" y="{y}" width="{w}" height="{h}" rx="6"/>'
               f'<rect class="{kind}" x="{x}" y="{y}" width="{w}" height="{h}" rx="6"/>'
               f'<line class="sep" x1="{x}" y1="{y + 28}" x2="{x + w}" y2="{y + 28}"/>'
               f'<text class="name" x="{x + 10}" y="{y + 19}">{escape(name)}</text>')
    if note:
        out.append(f'<text class="tag mono" x="{x + w - 10}" y="{y + 18}" text-anchor="end">{escape(note)}</text>')
        d._fit(name + note, "sub", w - 28, f"header of {name}")
    for i, (column, key) in enumerate(rows):
        base = y + 46 + 18 * i
        out.append(f'<text class="sub mono" x="{x + 10}" y="{base}">{escape(column)}</text>')
        if key:
            out.append(f'<text class="tag mono" x="{x + w - 10}" y="{base}" text-anchor="end">{escape(key)}</text>')
        d._fit(column, "sub", w - 28 - width(key, "tag"), f"table {name}")
    d.boxes.append((x, y, w, h, name))
    d._bound(x, y, w, h, f"table {name}")
    return lambda i: y + 42 + 18 * i  # vertical middle of row i, where a foreign key leaves the table


def data_model():
    d = Diagram("data-model", 720, 900, "Database tables",
                "Eight tables belong to a user through user_id and are deleted with it; broadcasts keep their sender "
                "as a nullable reference; the email outbox and the append-only audit log have no foreign key to "
                "users.")
    d.zone(14, 592, 692, 268, "NO FOREIGN KEY TO users")
    account = table(d, 14, 40, 190, "account_tokens", [
        ("id", "PK"), ("user_id", "FK"), ("type", "VERIFY · RESET"), ("token_hash", "UQ"), ("expires_at", "")])
    recovery = table(d, 14, 190, 190, "recovery_codes", [
        ("id", "PK"), ("user_id", "FK"), ("code_hash", "SHA-256"), ("used_at", "")])
    notes = table(d, 14, 322, 190, "notes", [
        ("id", "PK"), ("user_id", "FK"), ("title, body", ""), ("version", "OPTIMISTIC")])
    refresh = table(d, 516, 40, 190, "refresh_tokens", [
        ("id", "PK"), ("user_id", "FK"), ("family_id", "SESSION ID"), ("token_hash", "UQ"), ("used_at", "REUSE"),
        ("session_version", ""), ("session_started_at", "")])
    social = table(d, 516, 226, 190, "social_identities", [
        ("id", "PK"), ("user_id", "FK"), ("provider", "UQ PAIR"), ("subject", "UQ PAIR")])
    preferences = table(d, 516, 352, 190, "email_preferences", [
        ("user_id", "PK · FK"), ("category", "PK"), ("enabled, source", "")])
    # Foreign keys enter users from the side; the lower tables climb through the gaps between the columns.
    d.wire((204, account(1)), (252, account(1)))
    d.wire((204, recovery(1)), (222, recovery(1)), (222, 160), (252, 160))
    d.wire((204, notes(1)), (236, notes(1)), (236, 200), (252, 200))
    d.wire((516, refresh(1)), (468, refresh(1)))
    d.wire((516, social(1)), (492, social(1)), (492, 180), (468, 180))
    d.wire((516, preferences(0)), (504, preferences(0)), (504, 220), (468, 220))
    # known_devices hangs straight below users.
    d.wire((360, 300), (360, 238))
    table(d, 252, 40, 216, "users", [
        ("id", "PK"), ("username, email", "UQ"), ("password_hash", ""), ("role", "USER·ADMIN·OWNER"),
        ("email_verified", ""), ("session_version", ""), ("totp_secret", "AES-GCM"), ("totp_enabled", ""),
        ("totp_last_step", "")], kind="box-focal")
    table(d, 252, 300, 216, "known_devices", [
        ("user_id", "FK"), ("token_hash", "SHA-256 · UQ"), ("device, network", "FALLBACK"),
        ("last_seen_at", "LOOKBACK")])
    table(d, 252, 440, 216, "mail_broadcasts", [
        ("id", "PK"), ("category, subject, body", ""), ("queued, suppressed", ""), ("created_by", "SET NULL")])
    table(d, 34, 626, 310, "email_outbox", [
        ("id", "PK"), ("recipient, subject", ""), ("body, html_body", "HTML OPTIONAL"),
        ("attempts, next_attempt_at", "INDEX"), ("last_error, unsubscribe_token", ""),
        ("broadcast_id", "BROADCASTS LAST")])
    table(d, 376, 626, 310, "audit_events", [
        ("id", "PK"), ("occurred_at, action", ""), ("user_id", "NO FK"), ("actor_id, client_ip", ""),
        ("details", "JSONB")], note="APPEND-ONLY")
    for i, line in enumerate(["audit_events refuses UPDATE, DELETE and TRUNCATE through a trigger;",
                              "only purge_audit_events() removes rows older than 90 days.",
                              "With no foreign key, the trail outlives a deleted user."]):
        d.text(360, 792 + 20 * i, line, anchor="middle")
    d.legend(884, [("wire", "user_id → users.id, ON DELETE CASCADE"), ("box-focal", "parent of eight tables")])
    d.save()


def totp_states():
    d = Diagram("totp-states", 720, 496, "Two-factor enrollment states",
                "Requesting a secret moves two-factor authentication from off to pending, confirming it with the "
                "password and a first code turns it on, and turning it off needs the password and a code.")
    d.layers["wires"].append('<circle class="dot" cx="360" cy="16" r="5"/>')
    d.wire((360, 21), (360, 40))
    d.wire((360, 120), (360, 176))
    d.label(372, 144, "POST /totp", anchor="start")
    d.label(372, 157, "new secret", anchor="start")
    d.wire((480, 200), (524, 200), (524, 232), (480, 232))
    d.label(536, 212, "POST /totp", anchor="start")
    d.label(536, 225, "new secret", anchor="start")
    d.wire((360, 256), (360, 312), accent=True)
    d.label(372, 280, "POST /confirm", anchor="start", accent=True)
    d.label(372, 293, "password+code", anchor="start", accent=True)
    d.wire((240, 352), (80, 352), (80, 80), (240, 80))
    d.label(92, 210, "DELETE /totp", anchor="start")
    d.label(92, 223, "password+code", anchor="start")
    d.node(240, 40, 240, 80, "Off", ["totp_secret = null", "login: password only"], tag="STATE")
    d.node(240, 176, 240, 80, "Pending", ["secret stored, encrypted", "login: password only"], tag="STATE")
    d.node(240, 312, 240, 80, "Enabled", ["10 recovery codes, once", "login: password + code"], tag="STATE",
           kind="box-focal")
    for i, line in enumerate(["While pending, DELETE /totp answers 409 and login is unchanged;",
                              "only confirming turns it on. Turning it off deletes the secret and",
                              "every recovery code; POST /totp while enabled answers 409."]):
        d.text(360, 436 + 20 * i, line, anchor="middle")
    d.save()


def session_lifetime():
    d = Diagram("session-lifetime", 720, 364, "How long a session lasts",
                "Each refresh restarts a one-day idle limit, so a session that stops refreshing ends a day later, "
                "but no session lasts more than seven days after login, however often it refreshes.")
    x0, day = 40, 80

    def at(days):
        return x0 + day * days

    d.line(x0, 256, at(7.8), 256, cls="sep")
    for n in range(8):
        d.line(at(n), 252, at(n), 260, cls="sep")
        d.label(at(n), 276, "LOGIN" if n == 0 else f"{n} d")
    d.line(at(7), 52, at(7), 248, cls="w-accent")
    d.label(at(7), 40, "MAX SESSION 7 d", accent=True)

    def session(y, name, sub, refreshes, end):
        d.layers["labels"].append(f'<text class="name" x="{x0}" y="{y - 40}">{escape(name)}</text>'
                                  f'<text class="sub mono" x="{x0}" y="{y - 24}">{escape(sub)}</text>')
        out = d.layers["nodes"]
        out.append(f'<rect class="box" x="{x0}" y="{y - 7}" width="{at(end) - x0}" height="14" rx="3"/>'
                   f'<circle class="dot" cx="{x0}" cy="{y}" r="5"/>')
        out += [f'<circle class="dot" cx="{at(r)}" cy="{y}" r="3"/>' for r in refreshes]
        d.boxes.append((x0, y - 7, at(end) - x0, 14, name))

    session(124, "Stops refreshing", "idle limit ends it", [0.5, 1.2, 2.0], 3.0)
    d.line(at(2.0), 106, at(3.0), 106, cls="w")
    d.label(at(2.5), 97, "1 d IDLE")
    d.label(at(3.0) + 10, 128, "ENDS", anchor="start")

    session(212, "Refreshes all week", "absolute cap ends it", [0.7, 1.5, 2.2, 3.0, 3.8, 4.6, 5.3, 6.1, 6.8], 7.0)
    d.layers["nodes"].append(f'<rect class="box-ext" x="{at(7.0) + 4}" y="205" width="{day * 0.8 - 4}" '
                             f'height="14" rx="3"/>')
    d.label(at(7.45) + 2, 196, "CUT OFF")

    for i, line in enumerate(["Every refresh issues a new 15-minute access JWT, and the new refresh token",
                              "never outlives the cap. Remember-me: 30 days idle, at most 90 days.",
                              "Hitting the cap audits auth.session_expired_absolute."]):
        d.text(360, 304 + 20 * i, line, anchor="middle")
    d.save()


ALL = [data_model, totp_states, session_lifetime]
