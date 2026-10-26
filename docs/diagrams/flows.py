"""Account flows: social login, sign-up, social account resolution and ending every session."""

from lib import Diagram, Sequence


def social_login():
    d = Diagram("social-login", 720, 612, "Login with GitHub or Google",
                "The frontend sends the user to the provider with a PKCE challenge, receives the code on its "
                "redirect page and hands it with the verifier to the API, which exchanges it server-side and "
                "answers like a password login.")
    s = Sequence(d, [(110, "Frontend", "browser, sessionStorage", "box"),
                     (370, "Spring Boot API", "AuthController", "box"),
                     (610, "GitHub or Google", "OAuth 2 provider", "box-ext")], box_w=200, bottom=556)
    s.msg(0, 1, 108, "GET /auth/oauth2/providers")
    s.msg(1, 0, 144, "authorizationUri per provider", kind="return")
    s.self_msg(0, 180, "keep state + code_verifier")
    s.msg(0, 2, 236, "redirect + code_challenge", at=490)
    s.msg(2, 0, 272, "OAUTH2_REDIRECT_URI?code&state", kind="return", at=490)
    s.self_msg(0, 308, "check state")
    s.msg(0, 1, 364, "POST /oauth2/{provider}")
    s.msg(1, 2, 400, "code + secret + verifier")
    s.msg(2, 1, 436, "profile, verified email", kind="return")
    s.self_msg(1, 472, "link or create the user")
    s.msg(1, 0, 528, "200 tokens, or 202 mfaToken", kind="accent")
    d.text(360, 592, "The client secret never leaves the server; a request without codeVerifier gets 400.",
           anchor="middle")
    d.save()


def sign_up():
    d = Diagram("sign-up", 720, 684, "Email-first sign-up",
                "Registering only emails the address, a sign-up link or a notice that it has an account, with the "
                "same 202 answer either way; the link's page completes the sign-up once and creates a verified "
                "user.")
    s = Sequence(d, [(72, "Frontend", "browser", "box"), (216, "API", "AccountService", "box"),
                     (360, "Redis", "cooldown, links", "box"), (504, "PostgreSQL", "users", "box"),
                     (648, "Email", "outbox, SMTP", "box-ext")], box_w=128, bottom=600)
    s.msg(0, 1, 108, "POST /register")
    s.msg(1, 2, 144, "claim cooldown")
    s.msg(1, 3, 180, "find user", at=432)
    d.frame(24, 200, 688, 112, "ALT", "[address has an account]")
    s.msg(1, 4, 240, "already-registered", at=576)
    d.line(32, 256, 704, 256)
    d.label(84, 272, "[else]", anchor="start")
    s.msg(1, 4, 296, "sign-up link", at=576)
    s.msg(1, 0, 340, "202 either way", kind="return")
    s.msg(4, 0, 376, "/sign-up?token", kind="async", at=144)
    s.msg(0, 1, 424, "register/complete")
    s.msg(1, 2, 460, "link unused?")
    s.msg(1, 3, 496, "INSERT user", at=432)
    s.msg(1, 2, 532, "mark link used")
    s.msg(1, 0, 568, "201 + Location", kind="accent")
    d.text(360, 628, "The same 202 and at most one email per cooldown either way, so the form", anchor="middle")
    d.text(360, 646, "does not reveal who has an account.", anchor="middle")
    d.text(360, 670, "A link works once: used again it gets 400 invalid-token.", anchor="middle")
    d.save()


def social_account_resolution():
    d = Diagram("social-account-resolution", 712, 728, "Which user a GitHub or Google login becomes",
                "A provider account already linked logs in as its user; otherwise the provider must report a "
                "verified email, which links the account to the user who verified that email here or creates a new "
                "user, and an unverified local account gets a conflict.")
    d.wire((300, 16), (300, 56))
    d.label(310, 40, "PROVIDER PROFILE", anchor="start")
    d.wire((300, 144), (300, 184))
    d.label(310, 167, "NO", anchor="start")
    d.wire((300, 272), (300, 312))
    d.label(310, 295, "YES", anchor="start")
    d.wire((300, 400), (300, 440))
    d.label(310, 423, "YES", anchor="start")
    d.wire((300, 528), (300, 568))
    d.label(310, 551, "YES", anchor="start")
    d.wire((300, 624), (300, 664), accent=True)
    d.wire((416, 100), (688, 100), (688, 686), (380, 686))
    d.label(552, 91, "YES")
    d.wire((416, 228), (472, 228))
    d.label(444, 219, "NO")
    d.wire((416, 484), (472, 484))
    d.label(444, 475, "NO")
    d.wire((184, 356), (152, 356))
    d.label(168, 347, "NO")
    d.wire((84, 380), (84, 596), (184, 596))
    d.node(184, 56, 232, 88, "Linked before?", "provider + subject", shape="diamond")
    d.node(184, 184, 232, 88, "Verified email?", "from the provider", shape="diamond")
    d.node(184, 312, 232, 88, "Email has a user?", "users.email", shape="diamond")
    d.node(184, 440, 232, 88, "User verified it?", "email_verified", shape="diamond")
    d.node(16, 328, 136, 52, "Create USER", "random password")
    d.node(184, 568, 232, 56, "Link the account", "SocialIdentity, audit")
    d.node(220, 664, 160, 44, "Log in", kind="box-focal", shape="oval")
    d.node(472, 200, 184, 56, "403", "social-email-unverified")
    d.node(472, 456, 184, 56, "409", "social-account-conflict")
    d.save()


def session_invalidation():
    d = Diagram("session-invalidation", 720, 624, "Ending every session of a user",
                "A password or role change, or deleting the user, deletes the user's refresh tokens in the same "
                "transaction and, once it commits, sets a cut-off in Redis that rejects every access token issued "
                "before it.")
    s = Sequence(d, [(96, "Client", "any device", "box"), (280, "Spring Boot API", "UserService", "box"),
                     (464, "PostgreSQL", "refresh_tokens", "box"), (636, "Redis", "revocation", "box")],
                 box_w=164, bottom=556)
    s.msg(0, 1, 108, "change password/role")
    s.msg(1, 2, 144, "UPDATE user, version+1")
    s.self_msg(1, 180, "SessionsInvalidated")
    s.msg(1, 2, 236, "DELETE refresh tokens")
    s.msg(1, 3, 272, "after commit: cut-off", at=550)
    s.msg(1, 0, 308, "204", kind="return")
    s.divider(348, "OTHER DEVICES, LATER", at=372)
    s.msg(0, 1, 388, "request, old JWT")
    s.msg(1, 3, 424, "issued before cut-off?", at=550)
    s.msg(1, 0, 460, "401", kind="accent")
    s.msg(0, 1, 496, "POST /auth/refresh")
    s.msg(1, 0, 532, "401, token gone", kind="return")
    d.text(360, 588, "Refresh tokens are deleted in the same transaction as the change;", anchor="middle")
    d.text(360, 608, "access tokens are cut off in Redis once it commits.", anchor="middle")
    d.save()


ALL = [social_login, sign_up, social_account_resolution, session_invalidation]
