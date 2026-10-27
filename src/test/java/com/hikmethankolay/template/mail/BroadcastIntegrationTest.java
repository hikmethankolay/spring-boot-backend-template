package com.hikmethankolay.template.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hikmethankolay.template.IntegrationTest;
import com.hikmethankolay.template.TestMailConfiguration.CapturingMailSender;
import com.jayway.jsonpath.JsonPath;
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

class BroadcastIntegrationTest extends IntegrationTest {

    private static final String BROADCASTS = "/api/v1/admin/mail/broadcasts";

    private static final String SUBJECT = "Broadcast test";

    @Autowired
    private CapturingMailSender mail;

    @Autowired
    private EmailOutbox outbox;

    @Autowired
    private EmailPreferenceService preferences;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transaction;

    private static MockHttpServletRequestBuilder broadcast(
            String path, String token, String category, String password) {
        // Its own IP: the wrong password below counts as a failed login of that IP.
        return post(path)
                .with(bearer(token))
                .with(fromIp("10.0.31.7"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("""
                        {"category":"%s","subject":"%s","title":"What's new","body":"<b>Faster</b> logins.\\n\\nAnd more.",
                         "roles":["USER"],"verifiedOnly":true,"currentPassword":"%s"}""", category, SUBJECT, password));
    }

    private Object finishedAt(long broadcastId) {
        return jdbc.queryForObject("SELECT finished_at FROM mail_broadcasts WHERE id = ?", Object.class, broadcastId);
    }

    private int pending(long broadcastId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM email_outbox WHERE broadcast_id = ?", Integer.class, broadcastId);
    }

    @Test
    void onlyTheOwnerCanBroadcast() throws Exception {
        String username = uniqueUsername();
        long id = register(username);
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", id);
        String admin = login(username, PASSWORD);

        mockMvc.perform(broadcast(BROADCASTS + "/preview", admin, "product-updates", PASSWORD))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(BROADCASTS).with(bearer(admin))).andExpect(status().isForbidden());
    }

    @Test
    void previewSendsNothingAndShowsMarkupAsText() throws Exception {
        register(uniqueUsername());
        String owner = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        Integer before = jdbc.queryForObject("SELECT count(*) FROM mail_broadcasts", Integer.class);

        mockMvc.perform(broadcast(BROADCASTS + "/preview", owner, "product-updates", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipients").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.subject").value(SUBJECT))
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString("<b>Faster</b> logins.")))
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString("unsubscribe?token=preview")));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM mail_broadcasts", Integer.class))
                .isEqualTo(before);
    }

    @Test
    void transactionalOrUnknownCategoriesCannotBeBroadcast() throws Exception {
        String owner = login(ADMIN_USERNAME, ADMIN_PASSWORD);

        for (String category : List.of("account", "security", "unknown")) {
            mockMvc.perform(broadcast(BROADCASTS + "/preview", owner, category, ADMIN_PASSWORD))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:problem:validation-failed"));
        }
    }

    /** The only test that sends: broadcasts are limited to one a minute. */
    @Test
    void broadcastReachesOptedInUsersAfterTheTransactionalEmailsWaiting() throws Exception {
        String optedInName = uniqueUsername();
        String optedIn = optedInName + "@example.com";
        register(optedInName);
        String optedOut = uniqueUsername();
        long optedOutId = register(optedOut);
        preferences.update(optedOutId, Map.of("product-updates", false));
        String owner = login(ADMIN_USERNAME, ADMIN_PASSWORD);

        mockMvc.perform(broadcast(BROADCASTS, owner, "product-updates", "wrong-password-here"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:wrong-password"));
        String body = mockMvc.perform(broadcast(BROADCASTS, owner, "product-updates", ADMIN_PASSWORD))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long id = ((Number) JsonPath.read(body, "$.id")).longValue();
        int recipients = JsonPath.read(body, "$.recipients");
        assertThat(recipients).isPositive();
        mockMvc.perform(broadcast(BROADCASTS, owner, "product-updates", ADMIN_PASSWORD))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem:broadcast-in-progress"));

        // The recipients are queued in the background; finished_at says when that is done.
        for (int wait = 0; wait < 200 && finishedAt(id) == null; wait++) {
            Thread.sleep(50);
        }
        assertThat(finishedAt(id)).isNotNull();
        Map<String, Object> counts =
                jdbc.queryForMap("SELECT queued, suppressed, partial FROM mail_broadcasts WHERE id = ?", id);
        assertThat(counts)
                .containsEntry("queued", recipients)
                .containsEntry("partial", false)
                .hasEntrySatisfying(
                        "suppressed", suppressed -> assertThat((int) suppressed).isPositive());
        // Nothing is sent by queuing; the opted-out user gets no email at all.
        assertThat(mail.latest(optedIn, SUBJECT)).isEmpty();
        assertThat(pending(id)).isEqualTo(recipients);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM email_outbox WHERE broadcast_id = ? AND recipient = ?",
                        Integer.class,
                        id,
                        optedOut + "@example.com"))
                .isZero();
        mockMvc.perform(get(BROADCASTS).with(bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].queued").value(recipients))
                .andExpect(jsonPath("$[0].finishedAt").exists());

        // An email queued after the broadcast, here one whose immediate send failed, still goes out before it.
        String transactional = "after-broadcast-" + optedIn;
        mail.failNext(1);
        transaction.executeWithoutResult(status -> outbox.enqueue(transactional, "Transactional test", "Hello"));
        int sentBefore = mail.sent().size();
        outbox.sendDue(Instant.now().plus(1, ChronoUnit.HOURS));
        List<SimpleMailMessage> run = mail.sent();
        List<String> subjects = run.subList(sentBefore, run.size()).stream()
                .map(SimpleMailMessage::getSubject)
                .toList();
        // Every broadcast row is older than this email, yet it is sent before any of them.
        assertThat(subjects).contains("Transactional test");
        if (subjects.contains(SUBJECT)) {
            assertThat(subjects.indexOf("Transactional test")).isLessThan(subjects.indexOf(SUBJECT));
        }

        for (int pass = 0; pending(id) > 0 && pass < 100; pass++) {
            outbox.sendDue(Instant.now().plus(1, ChronoUnit.HOURS));
        }
        SimpleMailMessage sent = mail.latest(optedIn, SUBJECT).orElseThrow();
        assertThat(sent.getText()).contains("<b>Faster</b> logins.", "And more.", "Unsubscribe from Product updates");
        assertThat(mail.html(sent)).hasValueSatisfying(html -> assertThat(html).contains("&lt;b&gt;Faster"));
        assertThat(mail.mime(sent).orElseThrow().getHeader("List-Unsubscribe")).isNotNull();
        assertThat(mail.latest(optedOut + "@example.com", SUBJECT)).isEmpty();
    }
}
