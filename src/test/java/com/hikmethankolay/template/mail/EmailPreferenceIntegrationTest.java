package com.hikmethankolay.template.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hikmethankolay.template.IntegrationTest;
import com.hikmethankolay.template.TestMailConfiguration.CapturingMailSender;
import jakarta.mail.internet.MimeMessage;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

class EmailPreferenceIntegrationTest extends IntegrationTest {

    private static final String PREFERENCES = "/api/v1/users/me/email-preferences";

    @Autowired
    private EmailOutbox outbox;

    @Autowired
    private EmailPreferenceService preferences;

    @Autowired
    private CapturingMailSender mail;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcTemplate jdbc;

    private boolean sendNews(long userId, String to) {
        return Boolean.TRUE.equals(transaction.execute(status -> outbox.enqueue(
                userId,
                to,
                MailCategory.PRODUCT_UPDATES,
                "broadcast/announcement",
                "News for " + to,
                Map.of("title", "What's new", "paragraphs", List.of("<b>Faster</b> logins")))));
    }

    /** Sends a news email and returns the token of its unsubscribe link. */
    private String newsToken(long userId, String to) {
        assertThat(sendNews(userId, to)).isTrue();
        return CapturingMailSender.token(mail.latest(to, "News for").orElseThrow());
    }

    /** A request as a mail client sends it (RFC 8058): no cookie, Origin, CSRF header or Authorization. */
    private static MockHttpServletRequestBuilder oneClick(String token) {
        return post("/api/v1/mail/unsubscribe")
                .queryParam("token", token)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content("List-Unsubscribe=One-Click");
    }

    private String source(long userId) {
        return jdbc.queryForObject("SELECT source FROM email_preferences WHERE user_id = ?", String.class, userId);
    }

    @Test
    void optionalEmailCarriesTheUnsubscribeFooterAndHeaders() throws Exception {
        String username = uniqueUsername();
        String address = username + "@example.com";
        long id = register(username);

        String token = newsToken(id, address);

        SimpleMailMessage sent = mail.latest(address, "News for").orElseThrow();
        assertThat(sent.getText())
                .contains("Unsubscribe from Product updates: http://localhost:3000/unsubscribe?token=");
        assertThat(mail.html(sent))
                .hasValueSatisfying(html -> assertThat(html)
                        .contains("http://localhost:3000/unsubscribe?token=" + token, "&lt;b&gt;Faster")
                        .doesNotContain("<b>Faster"));
        MimeMessage mime = mail.mime(sent).orElseThrow();
        assertThat(mime.getHeader("List-Unsubscribe"))
                .containsExactly("<http://localhost:8080/api/v1/mail/unsubscribe?token=" + token + ">");
        assertThat(mime.getHeader("List-Unsubscribe-Post")).containsExactly("List-Unsubscribe=One-Click");
    }

    @Test
    void transactionalEmailHasNoUnsubscribeFooterOrHeader() throws Exception {
        String username = uniqueUsername();
        String address = username + "@example.com";
        long id = register(username);

        transaction.executeWithoutResult(status -> outbox.enqueue(
                id,
                address,
                MailCategory.SECURITY,
                "verify-email",
                "Security test",
                Map.of("link", "https://app.test/x", "ttl", "1 hour", "unsubscribeUrl", "https://evil.test")));

        SimpleMailMessage sent = mail.latest(address, "Security test").orElseThrow();
        assertThat(sent.getText()).doesNotContain("Unsubscribe");
        assertThat(mail.html(sent)).hasValueSatisfying(html -> assertThat(html).doesNotContain("Unsubscribe", "evil"));
        assertThat(mail.mime(sent).orElseThrow().getHeader("List-Unsubscribe")).isNull();
    }

    @Test
    void oneClickWithOnlyTheRfc8058BodyTurnsTheCategoryOff() throws Exception {
        String username = uniqueUsername();
        String address = username + "@example.com";
        long id = register(username);
        String token = newsToken(id, address);

        mockMvc.perform(oneClick(token)).andExpect(status().isNoContent());
        // Using the link again is harmless.
        mockMvc.perform(oneClick(token)).andExpect(status().isNoContent());

        assertThat(source(id)).isEqualTo("one-click");
        assertThat(sendNews(id, address)).isFalse();
        mockMvc.perform(get("/api/v1/mail/unsubscribe").queryParam("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("product-updates"))
                .andExpect(jsonPath("$.categoryName").value("Product updates"))
                .andExpect(jsonPath("$.enabled").value(false));
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM audit_events WHERE user_id = ? AND action = ?",
                        Integer.class,
                        id,
                        "user.email_preferences_changed"))
                .isEqualTo(1);
    }

    @Test
    void emailWaitingInTheOutboxIsDroppedOnceItsCategoryIsTurnedOff() throws Exception {
        String username = uniqueUsername();
        String address = username + "@example.com";
        long id = register(username);
        mail.failNext(1);
        try {
            assertThat(sendNews(id, address)).isTrue();
        } finally {
            mail.failNext(0);
        }
        assertThat(mail.latest(address, "News for")).isEmpty();
        // The failed attempt left the email queued for a retry.
        String token = jdbc.queryForObject(
                "SELECT unsubscribe_token FROM email_outbox WHERE recipient = ?", String.class, address);

        mockMvc.perform(oneClick(token)).andExpect(status().isNoContent());
        outbox.sendDue(Instant.now().plus(1, ChronoUnit.HOURS));

        assertThat(mail.latest(address, "News for")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_outbox WHERE recipient = ?", Integer.class, address))
                .isZero();
    }

    @Test
    void openingTheLinkChangesNothing() throws Exception {
        String username = uniqueUsername();
        long id = register(username);
        String token = newsToken(id, username + "@example.com");

        mockMvc.perform(get("/api/v1/mail/unsubscribe").queryParam("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));

        assertThat(preferences.isEnabled(id, MailCategory.PRODUCT_UPDATES)).isTrue();
    }

    @Test
    void theUnsubscribePageConfirmsWithoutABody() throws Exception {
        String username = uniqueUsername();
        long id = register(username);
        String token = newsToken(id, username + "@example.com");

        mockMvc.perform(post("/api/v1/mail/unsubscribe/all").queryParam("token", token))
                .andExpect(status().isNoContent());

        assertThat(preferences.isEnabled(id, MailCategory.PRODUCT_UPDATES)).isFalse();
        assertThat(source(id)).isEqualTo("link");
    }

    @Test
    void forgedTokenIsRejected() throws Exception {
        mockMvc.perform(oneClick("v1.MTIzLnByb2R1Y3QtdXBkYXRlcw.c2lnbmF0dXJl"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:unsubscribe-token-invalid"));
        mockMvc.perform(get("/api/v1/mail/unsubscribe").queryParam("token", "garbage"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:unsubscribe-token-invalid"));
    }

    @Test
    void preferencesGoWithTheAccountAndItsOldLinksStillSucceed() throws Exception {
        String username = uniqueUsername();
        long id = register(username);
        String token = newsToken(id, username + "@example.com");
        mockMvc.perform(oneClick(token)).andExpect(status().isNoContent());

        jdbc.update("DELETE FROM users WHERE id = ?", id);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_preferences WHERE user_id = ?", Integer.class, id))
                .isZero();
        // Same answer as for a live account, so the link reveals nothing.
        mockMvc.perform(oneClick(token)).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_preferences WHERE user_id = ?", Integer.class, id))
                .isZero();
    }

    @Test
    void preferencesCanBeChangedButNotForTransactionalEmail() throws Exception {
        String username = uniqueUsername();
        long id = register(username);
        long other = register(uniqueUsername());
        String token = login(username, PASSWORD);

        mockMvc.perform(get(PREFERENCES).with(bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].category").value("product-updates"))
                .andExpect(jsonPath("$[0].enabled").value(true));
        mockMvc.perform(put(PREFERENCES)
                        .with(bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product-updates\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].enabled").value(false));

        assertThat(source(id)).isEqualTo("user");
        assertThat(preferences.isEnabled(other, MailCategory.PRODUCT_UPDATES)).isTrue();
        for (String key : List.of("account", "security", "unknown")) {
            mockMvc.perform(put(PREFERENCES)
                            .with(bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"" + key + "\": false}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:problem:validation-failed"));
        }
        mockMvc.perform(get("/api/v1/users/me/export").with(bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.related.emailPreferences[0].category").value("product-updates"))
                .andExpect(jsonPath("$.related.emailPreferences[0].enabled").value(false));
    }
}
