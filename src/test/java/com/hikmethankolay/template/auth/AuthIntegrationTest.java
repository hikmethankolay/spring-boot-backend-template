package com.hikmethankolay.template.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hikmethankolay.template.IntegrationTest;
import com.hikmethankolay.template.audit.AuditEvent;
import com.hikmethankolay.template.audit.AuditLog;
import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

class AuthIntegrationTest extends IntegrationTest {

    @Autowired
    private MeterRegistry meters;

    @Test
    void signUpStartsWithTheAddressAndCreatesTheUserWhenCompleted() throws Exception {
        String username = uniqueUsername();

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"email":"%s@EXAMPLE.com"}""", username)))
                .andExpect(status().isAccepted())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(content().string(""));
        mockMvc.perform(post("/api/v1/auth/register/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"token":"%s","username":"%s","password":"%s"}""", signUpToken(username + "@example.com"), username, PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, matchesPattern("http://localhost/api/v1/users/\\d+")))
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.email").value(username + "@example.com"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.emailVerified").value(true))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        login(username, PASSWORD);
    }

    @Test
    void signUpRejectsInvalidInputWithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"not-an-email"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'email')]").exists());
        mockMvc.perform(post("/api/v1/auth/register/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"","username":"a b","password":"weak"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[?(@.field == 'token')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'username')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'password')]").exists());
    }

    @Test
    void signUpRejectsDuplicateUsername() throws Exception {
        String username = uniqueUsername();
        register(username);

        completeSignUp("other-" + username + "@example.com", username, PASSWORD)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Username is already taken."));
    }

    @Test
    void loginWithEmailReturnsTokenAndSessionCookie() throws Exception {
        String username = uniqueUsername();
        register(username);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"identifier":"%s@Example.com","password":"%s"}""", username, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(header().stringValues(
                                HttpHeaders.SET_COOKIE,
                                hasItems(
                                        allOf(
                                                containsString("auth_token="),
                                                containsString("Path=/;"),
                                                containsString("HttpOnly"),
                                                containsString("SameSite=Strict"),
                                                not(containsString("Max-Age"))),
                                        allOf(
                                                containsString("refresh_token="),
                                                containsString("Path=/api/v1/auth"),
                                                containsString("HttpOnly"),
                                                not(containsString("Max-Age"))))));
    }

    @Test
    void rememberMeLoginReturnsPersistentCookies() throws Exception {
        String username = uniqueUsername();
        register(username);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"identifier":"%s","password":"%s","rememberMe":true}""", username, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(header().stringValues(
                                HttpHeaders.SET_COOKIE,
                                hasItems(
                                        allOf(containsString("auth_token="), containsString("Max-Age=900")),
                                        allOf(containsString("refresh_token="), containsString("Max-Age=2592000")))));
    }

    @Test
    void loginWithWrongPasswordIsUnauthorized() throws Exception {
        String username = uniqueUsername();
        register(username);

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(fromIp("10.0.0.1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"identifier":"%s","password":"Wrong#123"}""", username)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.type").value("urn:problem:invalid-credentials"))
                .andExpect(jsonPath("$.detail").value("Invalid username or password."));
    }

    @Test
    void passwordOver128CharactersIsRejected() throws Exception {
        String username = uniqueUsername();

        completeSignUp(username + "@example.com", username, "a".repeat(129))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:validation-failed"))
                .andExpect(jsonPath("$.errors[0].field").value("password"));
    }

    @Test
    void longMultiByteLowerCasePasswordIsAccepted() throws Exception {
        // No composition rules, and 124 bytes in UTF-8: over BCrypt's old 72-byte limit, fine for Argon2.
        String password = "ğ".repeat(62);
        String username = uniqueUsername();

        completeSignUp(username + "@example.com", username, password).andExpect(status().isCreated());
        login(username, password);
    }

    @Test
    void passwordUnder15CharactersIsRejectedWithoutTwoFactor() throws Exception {
        String username = uniqueUsername();

        completeSignUp(username + "@example.com", username, "fourteen-chars")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:password-too-short"));
    }

    @Test
    void breachedPasswordIsRejected() throws Exception {
        String username = uniqueUsername();

        // On the bundled list; compared in lower case.
        completeSignUp(username + "@example.com", username, "1Q2W3E4R5T6Y7U8I9O0P")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:password-breached"));
    }

    @Test
    void missingAccessTokenIsUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.type").value("urn:problem:unauthenticated"));
    }

    @Test
    void corsExposesTheDocumentedResponseHeaders() throws Exception {
        mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.ORIGIN, "http://localhost:3000"))
                .andExpect(header().string(
                                HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                                allOf(
                                        containsString("X-Trace-Id"),
                                        containsString("Retry-After"),
                                        containsString("Content-Disposition"),
                                        containsString("RateLimit-Limit"),
                                        containsString("RateLimit-Remaining"),
                                        containsString("RateLimit-Reset"))));
    }

    @Test
    void repeatedFailedLoginsBlockTheIp() throws Exception {
        String username = uniqueUsername();
        register(username);

        for (int attempt = 0; attempt < 3; attempt++) {
            failLogin(username, "10.0.0.2");
        }

        // Even the correct password is rejected from the blocked IP...
        mockMvc.perform(post("/api/v1/auth/login")
                        .with(fromIp("10.0.0.2"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"identifier":"%s","password":"%s"}""", username, PASSWORD)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "3600"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:problem:login-locked"));

        // ...but one client cannot lock the real user out.
        mockMvc.perform(post("/api/v1/auth/login")
                        .with(fromIp("10.0.0.3"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"identifier":"%s","password":"%s"}""", username, PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    void failedLoginsFromManyIpsBlockTheAccount() throws Exception {
        String username = uniqueUsername();
        register(username);

        for (int attempt = 1; attempt <= 5; attempt++) {
            failLogin(username, "10.0.4." + attempt);
        }

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(fromIp("10.0.4.100"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"identifier":"%s","password":"%s"}""", username, PASSWORD)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "3600"));
    }

    /** One IPv6 host usually has a whole /64, so a new address per attempt must not reset the count. */
    @Test
    void failedLoginsFromOneIpv6NetworkBlockTheWholeNetwork() throws Exception {
        String username = uniqueUsername();
        register(username);

        for (int host = 1; host <= 3; host++) {
            failLogin(username, "2001:db8:0:4::" + host);
        }

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(fromIp("2001:db8:0:4::99"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"identifier":"%s","password":"%s"}""", username, PASSWORD)))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void usernameAndEmailShareOneLockout() throws Exception {
        String username = uniqueUsername();
        register(username);

        for (int attempt = 1; attempt <= 5; attempt++) {
            failLogin(attempt % 2 == 0 ? username : username + "@example.com", "10.0.8." + attempt);
        }

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(fromIp("10.0.8.100"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"identifier":"%s@example.com","password":"%s"}""", username, PASSWORD)))
                .andExpect(status().isTooManyRequests());
    }

    /** Logout audit events recorded so far. */
    private double logouts() {
        return meters.counter(AuditLog.METRIC_NAME, "event", AuditEvent.LOGOUT.action())
                .count();
    }

    private void failLogin(String identifier, String ip) throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .with(fromIp(ip))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"identifier":"%s","password":"Wrong#123"}""", identifier)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpointWithoutTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void tamperedTokenIsUnauthorized() throws Exception {
        String token = registerAndLogin(uniqueUsername());

        mockMvc.perform(get("/api/v1/users/me").with(bearer(token + "x"))).andExpect(status().isUnauthorized());
    }

    @Test
    void authCookieAuthenticatesRequests() throws Exception {
        String username = uniqueUsername();
        String token = registerAndLogin(username);

        mockMvc.perform(get("/api/v1/users/me").cookie(new Cookie("auth_token", token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username));
    }

    @Test
    void staleCookieDoesNotBlockLogin() throws Exception {
        String username = uniqueUsername();
        register(username);

        mockMvc.perform(post("/api/v1/auth/login")
                        .cookie(new Cookie("auth_token", "expired-or-garbage"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"identifier":"%s","password":"%s"}""", username, PASSWORD)))
                .andExpect(status().isOk());
    }

    private Tokens refresh(String refreshToken) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"refreshToken":"%s"}""", refreshToken)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return new Tokens(JsonPath.read(body, "$.accessToken"), JsonPath.read(body, "$.refreshToken"));
    }

    @Test
    void refreshIssuesNewTokens() throws Exception {
        String username = uniqueUsername();
        register(username);
        Tokens login = loginTokens(username, PASSWORD);

        Tokens refreshed = refresh(login.refreshToken());

        assertThat(refreshed.refreshToken()).isNotEqualTo(login.refreshToken());
        mockMvc.perform(get("/api/v1/users/me").with(bearer(refreshed.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username));
    }

    @Test
    void refreshTokenCookieWorksWithoutBody() throws Exception {
        String username = uniqueUsername();
        register(username);
        Tokens login = loginTokens(username, PASSWORD);

        mockMvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("refresh_token", login.refreshToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(header().stringValues(
                                HttpHeaders.SET_COOKIE,
                                hasItems(containsString("auth_token="), containsString("refresh_token="))));
    }

    @Test
    void refreshWorksWithAnExpiredAccessToken() throws Exception {
        String username = uniqueUsername();
        register(username);
        Tokens login = loginTokens(username, PASSWORD);

        // /refresh is public: the (possibly expired) access token is not even read.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .with(bearer("expired-or-garbage"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"refreshToken":"%s"}""", login.refreshToken())))
                .andExpect(status().isOk());
    }

    @Test
    void reusedRefreshTokenRevokesTheSession() throws Exception {
        String username = uniqueUsername();
        register(username);
        Tokens login = loginTokens(username, PASSWORD);
        Tokens refreshed = refresh(login.refreshToken());

        // The first token is replayed (e.g. stolen): rejected, and the whole session ends.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"refreshToken":"%s"}""", login.refreshToken())))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"refreshToken":"%s"}""", refreshed.refreshToken())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void onlyTheNewestUsedTokenIsKeptForReuseDetection() throws Exception {
        String username = uniqueUsername();
        register(username);
        Tokens login = loginTokens(username, PASSWORD);
        Tokens first = refresh(login.refreshToken());
        Tokens second = refresh(first.refreshToken());

        // The login token was cleaned up: replaying it is rejected but does not end the session.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"refreshToken":"%s"}""", login.refreshToken())))
                .andExpect(status().isUnauthorized());
        Tokens third = refresh(second.refreshToken());

        // Replaying the newest used token is still detected and ends the session.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"refreshToken":"%s"}""", second.refreshToken())))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"refreshToken":"%s"}""", third.refreshToken())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void sessionsAreIndependent() throws Exception {
        String username = uniqueUsername();
        register(username);
        Tokens laptop = loginTokens(username, PASSWORD);
        Tokens phone = loginTokens(username, PASSWORD);

        mockMvc.perform(post("/api/v1/auth/logout")
                        .with(bearer(laptop.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"refreshToken":"%s"}""", laptop.refreshToken())))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"refreshToken":"%s"}""", laptop.refreshToken())))
                .andExpect(status().isUnauthorized());
        refresh(phone.refreshToken());
    }

    @Test
    void logoutRevokesTheToken() throws Exception {
        String token = registerAndLogin(uniqueUsername());
        mockMvc.perform(get("/api/v1/users/me").with(bearer(token))).andExpect(status().isOk());
        double logouts = logouts();

        mockMvc.perform(post("/api/v1/auth/logout").with(bearer(token))).andExpect(status().isNoContent());

        assertThat(logouts()).isEqualTo(logouts + 1);
        mockMvc.perform(get("/api/v1/users/me").with(bearer(token))).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/users/me").cookie(new Cookie("auth_token", token)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutWithOnlyTheAccessTokenEndsItsSession() throws Exception {
        String username = uniqueUsername();
        register(username);
        Tokens login = loginTokens(username, PASSWORD);

        mockMvc.perform(post("/api/v1/auth/logout").with(bearer(login.accessToken())))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"refreshToken":"%s"}""", login.refreshToken())))
                .andExpect(status().isUnauthorized());
    }

    /** A browser whose access cookie has expired logs out with the refresh cookie alone. */
    @Test
    void logoutWithOnlyTheRefreshTokenEndsItsSession() throws Exception {
        String username = uniqueUsername();
        register(username);
        Tokens login = loginTokens(username, PASSWORD);

        mockMvc.perform(post("/api/v1/auth/logout").cookie(new Cookie("refresh_token", login.refreshToken())))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/users/me").with(bearer(login.accessToken())))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"refreshToken":"%s"}""", login.refreshToken())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutAlsoEndsOlderAccessTokensOfTheSession() throws Exception {
        String username = uniqueUsername();
        register(username);
        Tokens login = loginTokens(username, PASSWORD);
        Tokens refreshed = refresh(login.refreshToken());

        mockMvc.perform(post("/api/v1/auth/logout")
                        .with(bearer(refreshed.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"refreshToken":"%s"}""", refreshed.refreshToken())))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/users/me").with(bearer(login.accessToken())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutWithInvalidTokenStillSucceeds() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isNoContent());
    }

    @Test
    void refreshWithoutTokenIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("The refresh token is missing, invalid or expired."));
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"not-a-real-token"}"""))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutClearsCookies() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isNoContent())
                .andExpect(header().stringValues(
                                HttpHeaders.SET_COOKIE,
                                hasItems(
                                        allOf(containsString("auth_token="), containsString("Max-Age=0")),
                                        allOf(containsString("refresh_token="), containsString("Max-Age=0")))));
    }

    @Test
    void responsesCarrySecurityHeaders() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'none'")))
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Permissions-Policy", containsString("camera=()")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    @Test
    void healthAndApiDocsArePublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("spring-boot-template"));
    }
}
