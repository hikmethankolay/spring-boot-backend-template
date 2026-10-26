package com.hikmethankolay.template.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hikmethankolay.template.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.RequestBuilder;

class MfaIntegrationTest extends IntegrationTest {

    private static final String TOTP = "/api/v1/users/me/mfa/totp";

    @Test
    void loginNeedsTheSecondFactorOnceEnabled() throws Exception {
        String username = uniqueUsername();
        String token = registerAndLogin(username);
        byte[] secret = enroll(token);
        long step = Totp.step(Instant.now().getEpochSecond());
        List<String> recoveryCodes = confirm(token, Totp.code(secret, step));

        assertThat(recoveryCodes).hasSize(10).doesNotHaveDuplicates();
        mockMvc.perform(get("/api/v1/users/me").with(bearer(token)))
                .andExpect(jsonPath("$.mfaEnabled").value(true));

        // The code of the next step is valid now (one step of drift) and was not used for the confirmation.
        String code = Totp.code(secret, step + 1);
        String challenge = passwordStep(username, "10.0.9.1");
        mockMvc.perform(mfaLogin(challenge, code, "10.0.9.1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(header().exists(HttpHeaders.SET_COOKIE));

        // An MFA token works once, even with another valid code.
        mockMvc.perform(mfaLogin(challenge, recoveryCodes.get(1), "10.0.9.1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:problem:mfa-challenge-expired"));

        // A code works once.
        mockMvc.perform(mfaLogin(passwordStep(username, "10.0.9.1"), code, "10.0.9.1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:problem:invalid-mfa-code"));

        // So does a recovery code, typed in any case and without the dash.
        String recovery = recoveryCodes.getFirst().replace("-", "").toUpperCase(Locale.ROOT);
        mockMvc.perform(mfaLogin(passwordStep(username, "10.0.9.2"), recovery, "10.0.9.2"))
                .andExpect(status().isOk());
        mockMvc.perform(mfaLogin(passwordStep(username, "10.0.9.3"), recovery, "10.0.9.3"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void passwordLoginGivesNoTokensWhileTheSecondFactorIsMissing() throws Exception {
        String username = uniqueUsername();
        String token = registerAndLogin(username);
        byte[] secret = enroll(token);
        confirm(token, Totp.code(secret, Totp.step(Instant.now().getEpochSecond())));

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(fromIp("10.0.9.4"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"identifier":"%s","password":"%s"}""", username, PASSWORD)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.mfaToken").exists())
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        mockMvc.perform(mfaLogin("not-a-real-token", "123456", "10.0.9.4"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:problem:mfa-challenge-expired"));
    }

    @Test
    void enrollmentNeedsAValidCodeAndCanBeTurnedOffWithBothFactors() throws Exception {
        String username = uniqueUsername();
        String token = registerAndLogin(username);
        mockMvc.perform(confirmRequest(token, PASSWORD, "123456"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem:totp-not-enabled"));
        mockMvc.perform(disable(token, PASSWORD, "123456", "10.0.9.5"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem:totp-not-enabled"));

        byte[] secret = enroll(token);
        long step = Totp.step(Instant.now().getEpochSecond());

        // A stolen token alone cannot put an authenticator on the account.
        mockMvc.perform(confirmRequest(token, "Wrong#123", Totp.code(secret, step)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:wrong-password"));
        mockMvc.perform(confirmRequest(token, PASSWORD, wrongCode(secret, step)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:invalid-mfa-code"));
        confirm(token, Totp.code(secret, step));
        mockMvc.perform(post(TOTP).with(bearer(token)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem:totp-already-enabled"));

        mockMvc.perform(disable(token, "wrong-password-here", Totp.code(secret, step + 1), "10.0.9.5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:wrong-password"));
        mockMvc.perform(disable(token, PASSWORD, Totp.code(secret, step + 1), "10.0.9.5"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/users/me").with(bearer(token)))
                .andExpect(jsonPath("$.mfaEnabled").value(false));
        login(username, PASSWORD);
    }

    @Test
    void aShortPasswordAllowedByTwoFactorKeepsTwoFactorOn() throws Exception {
        String username = uniqueUsername();
        String token = registerAndLogin(username);
        byte[] secret = enroll(token);
        long step = Totp.step(Instant.now().getEpochSecond());
        confirm(token, Totp.code(secret, step));
        // With a second factor, 8 characters are enough.
        String shortPassword = "tq8-vbmz";
        mockMvc.perform(put("/api/v1/users/me/password")
                        .with(bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"currentPassword":"%s","newPassword":"%s"}""", PASSWORD, shortPassword)))
                .andExpect(status().isNoContent());
        String mfaToken = JsonPath.read(
                mockMvc.perform(post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json("""
                                        {"identifier":"%s","password":"%s"}""", username, shortPassword)))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.mfaToken");
        String fresh = JsonPath.read(
                mockMvc.perform(mfaLogin(mfaToken, Totp.code(secret, step + 1), "10.0.9.7"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.accessToken");

        // Without the second factor the password would be too short, so it stays on.
        mockMvc.perform(disable(fresh, shortPassword, Totp.code(secret, step + 1), "10.0.9.7"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:password-too-short"));
        mockMvc.perform(get("/api/v1/users/me").with(bearer(fresh)))
                .andExpect(jsonPath("$.mfaEnabled").value(true));
    }

    @Test
    void guessingTheCodeToTurnTwoFactorOffRunsIntoTheLockout() throws Exception {
        String token = registerAndLogin(uniqueUsername());
        byte[] secret = enroll(token);
        long step = Totp.step(Instant.now().getEpochSecond());
        confirm(token, Totp.code(secret, step));
        String wrong = wrongCode(secret, step);

        // The test profile blocks an IP after 3 failures.
        for (int attempt = 0; attempt < 3; attempt++) {
            mockMvc.perform(disable(token, PASSWORD, wrong, "10.0.9.6")).andExpect(status().isBadRequest());
        }
        mockMvc.perform(disable(token, PASSWORD, Totp.code(secret, step + 1), "10.0.9.6"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.type").value("urn:problem:login-locked"));
        mockMvc.perform(get("/api/v1/users/me").with(bearer(token)))
                .andExpect(jsonPath("$.mfaEnabled").value(true));
    }

    private byte[] enroll(String token) throws Exception {
        String body = mockMvc.perform(post(TOTP).with(bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.otpauthUri").value(Matchers.startsWith("otpauth://totp/")))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return base32Decode(JsonPath.read(body, "$.secret"));
    }

    private List<String> confirm(String token, String code) throws Exception {
        String body = mockMvc.perform(confirmRequest(token, PASSWORD, code))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.recoveryCodes");
    }

    /** Logs in with the password from its own IP (wrong codes count against it) and returns the MFA token. */
    private String passwordStep(String username, String ip) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .with(fromIp(ip))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"identifier":"%s","password":"%s"}""", username, PASSWORD)))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.mfaToken");
    }

    private static RequestBuilder mfaLogin(String mfaToken, String code, String ip) {
        return post("/api/v1/auth/login/mfa")
                .with(fromIp(ip))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("""
                        {"mfaToken":"%s","code":"%s"}""", mfaToken, code));
    }

    private static RequestBuilder confirmRequest(String token, String password, String code) {
        return post(TOTP + "/confirm")
                .with(bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("""
                        {"currentPassword":"%s","code":"%s"}""", password, code));
    }

    /** Turns 2FA off from its own IP: wrong passwords and codes count against it. */
    private static RequestBuilder disable(String token, String password, String code, String ip) {
        return delete(TOTP)
                .with(bearer(token))
                .with(fromIp(ip))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("""
                        {"currentPassword":"%s","code":"%s"}""", password, code));
    }

    /** A 6-digit code that matches none of the steps the server could accept. */
    private static String wrongCode(byte[] secret, long step) {
        for (int candidate = 0; ; candidate++) {
            String code = "%06d".formatted(candidate);
            boolean valid = false;
            for (long s = step - 2; s <= step + 2; s++) {
                valid |= code.equals(Totp.code(secret, s));
            }
            if (!valid) {
                return code;
            }
        }
    }

    private static byte[] base32Decode(String text) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : text.toCharArray()) {
            buffer = (buffer << 5) | alphabet.indexOf(c);
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
