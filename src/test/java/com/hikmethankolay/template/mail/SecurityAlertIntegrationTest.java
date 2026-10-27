package com.hikmethankolay.template.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hikmethankolay.template.IntegrationTest;
import com.hikmethankolay.template.TestMailConfiguration.CapturingMailSender;
import com.hikmethankolay.template.auth.KnownDevices;
import com.hikmethankolay.template.mfa.Totp;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Security alerts are off in the other tests; this class turns them on. */
@TestPropertySource(properties = "app.mail.security-alerts.enabled=true")
class SecurityAlertIntegrationTest extends IntegrationTest {

    private static final String CHROME_ON_WINDOWS = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";

    private static final String FIREFOX_ON_LINUX =
            "Mozilla/5.0 (X11; Linux x86_64; rv:131.0) Gecko/20100101 Firefox/131.0";

    private static final String TOTP = "/api/v1/users/me/mfa/totp";

    @Autowired
    private CapturingMailSender mail;

    @Autowired
    private KnownDevices knownDevices;

    @Autowired
    private JdbcTemplate jdbc;

    private MockHttpServletResponse loginFrom(String username, String userAgent, String ip, Cookie device)
            throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/auth/login")
                .with(fromIp(ip))
                .header(HttpHeaders.USER_AGENT, userAgent)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("""
                        {"identifier":"%s","password":"%s"}""", username, PASSWORD));
        if (device != null) {
            request.cookie(device);
        }
        return mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse();
    }

    private Optional<SimpleMailMessage> alert(String address, String subject) {
        return mail.latest(address, subject);
    }

    @Test
    void signInFromANewDeviceIsReported() throws Exception {
        String username = uniqueUsername();
        String address = username + "@example.com";
        register(username);

        // The first device of an account is not reported.
        Cookie device =
                loginFrom(username, CHROME_ON_WINDOWS, "198.51.100.7", null).getCookie("device");
        assertThat(device).isNotNull();
        assertThat(device.isHttpOnly()).isTrue();
        assertThat(device.getPath()).isEqualTo("/api/v1/auth");
        // The same browser, from anywhere, keeps its cookie.
        assertThat(loginFrom(username, CHROME_ON_WINDOWS, "192.0.2.50", device)
                        .getCookie("device")
                        .getValue())
                .isEqualTo(device.getValue());
        // A client without cookies on the same device and network (/24) is recognized and gets no new cookie.
        assertThat(loginFrom(username, CHROME_ON_WINDOWS, "198.51.100.99", null).getCookie("device"))
                .isNull();
        assertThat(alert(address, "New sign-in")).isEmpty();

        loginFrom(username, FIREFOX_ON_LINUX, "203.0.113.5", null);

        SimpleMailMessage sent = alert(address, "New sign-in").orElseThrow();
        assertThat(sent.getText())
                .contains("Firefox on Linux", "203.0.113.5", "http://localhost:3000/settings/sessions")
                .doesNotContain("Unsubscribe");
        assertThat(mail.mime(sent).orElseThrow().getHeader("List-Unsubscribe")).isNull();
    }

    @Test
    void behindAPrivateAddressOnlyTheCookieRecognizesADevice() throws Exception {
        String username = uniqueUsername();
        String address = username + "@example.com";
        register(username);

        // A proxy that does not forward the client's address makes every client 10.0.0.5: no network fallback.
        loginFrom(username, CHROME_ON_WINDOWS, "10.0.0.5", null);
        assertThat(alert(address, "New sign-in")).isEmpty();
        loginFrom(username, CHROME_ON_WINDOWS, "10.0.0.5", null);

        assertThat(alert(address, "New sign-in")).isPresent();
    }

    @Test
    void anAccountIdleForLongerThanTheLookbackIsStillAlerted() throws Exception {
        String username = uniqueUsername();
        String address = username + "@example.com";
        long id = register(username);
        loginFrom(username, CHROME_ON_WINDOWS, "198.51.100.7", null);
        loginFrom(username, FIREFOX_ON_LINUX, "198.51.100.7", null);
        jdbc.update("UPDATE known_devices SET last_seen_at = now() - interval '200 days' WHERE user_id = ?", id);
        jdbc.update(
                "UPDATE known_devices SET last_seen_at = now() - interval '199 days' WHERE user_id = ? AND device = ?",
                id,
                "Firefox on Linux");

        knownDevices.forgetUnusedDevices();

        // Only the latest device is kept, so the account does not look new.
        assertThat(jdbc.queryForList("SELECT device FROM known_devices WHERE user_id = ?", String.class, id))
                .containsExactly("Firefox on Linux");
        loginFrom(username, CHROME_ON_WINDOWS, "198.51.100.7", null);
        assertThat(alert(address, "New sign-in").orElseThrow().getText()).contains("Chrome on Windows");
    }

    @Test
    void passwordChangeIsReportedButAFailedAttemptIsNot() throws Exception {
        String username = uniqueUsername();
        String address = username + "@example.com";
        register(username);
        String token = login(username, PASSWORD);

        mockMvc.perform(put("/api/v1/users/me/password")
                        .with(bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"currentPassword":"wrong-password-here","newPassword":"Changed#4567-passphrase"}""")))
                .andExpect(status().isBadRequest());
        assertThat(alert(address, "Your password was changed")).isEmpty();

        mockMvc.perform(put("/api/v1/users/me/password")
                        .with(bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"currentPassword":"%s","newPassword":"Changed#4567-passphrase"}""", PASSWORD)))
                .andExpect(status().isNoContent());
        assertThat(alert(address, "Your password was changed")).isPresent();
    }

    @Test
    void emailChangeIsReportedToTheOldAddressWithoutTheNewOne() throws Exception {
        String username = uniqueUsername();
        String address = username + "@example.com";
        String newAddress = "moved-" + address;
        register(username);
        String token = login(username, PASSWORD);

        mockMvc.perform(patch("/api/v1/users/me")
                        .with(bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"email":"%s","currentPassword":"%s"}""", newAddress, PASSWORD)))
                .andExpect(status().isOk());

        SimpleMailMessage sent =
                alert(address, "email address of your account was changed").orElseThrow();
        assertThat(sent.getText()).doesNotContain(newAddress);
        assertThat(mail.html(sent)).hasValueSatisfying(html -> assertThat(html).doesNotContain(newAddress));
        assertThat(alert(newAddress, "email address of your account was changed"))
                .isEmpty();
    }

    @Test
    void twoFactorChangesAreReported() throws Exception {
        String username = uniqueUsername();
        String address = username + "@example.com";
        register(username);
        String token = login(username, PASSWORD);
        String body = mockMvc.perform(post(TOTP).with(bearer(token)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        byte[] secret = base32Decode(JsonPath.read(body, "$.secret"));
        long step = Totp.step(Instant.now().getEpochSecond());

        mockMvc.perform(post(TOTP + "/confirm")
                        .with(bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"currentPassword":"%s","code":"%s"}""", PASSWORD, Totp.code(secret, step))))
                .andExpect(status().isOk());
        assertThat(alert(address, "Two-factor authentication was turned on")).isPresent();

        mockMvc.perform(delete(TOTP)
                        .with(bearer(token))
                        .with(fromIp("10.0.19.5"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"currentPassword":"%s","code":"%s"}""", PASSWORD, Totp.code(secret, step + 1))))
                .andExpect(status().isNoContent());
        assertThat(alert(address, "Two-factor authentication was turned off")).isPresent();
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
