package com.hikmethankolay.template.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hikmethankolay.template.audit.AuditLog;
import com.hikmethankolay.template.common.ApiException;
import com.hikmethankolay.template.user.Role;
import com.hikmethankolay.template.user.UserRepository;
import com.hikmethankolay.template.webhook.dto.WebhookRequest;
import com.hikmethankolay.template.webhook.dto.WebhookUpdateRequest;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The webhook building blocks on their own: entities, signer, sender, publisher and the service's guards. */
class WebhookUnitsTest {

    @Test
    void aRotationKeepsThePreviousSecretOnlyUntilItExpires() {
        WebhookEndpoint endpoint = new WebhookEndpoint(1L, "https://example.com", null, List.of("note.created"));
        Instant now = Instant.now();

        endpoint.rotateSecret("first", now.plusSeconds(60));
        assertThat(endpoint.getPreviousSecret(now))
                .as("nothing to keep on the first secret")
                .isNull();

        endpoint.rotateSecret("second", now.plusSeconds(60));
        assertThat(endpoint.getSecret()).isEqualTo("second");
        assertThat(endpoint.getPreviousSecret(now)).isEqualTo("first");
        assertThat(endpoint.getPreviousSecret(now.plusSeconds(61))).isNull();

        endpoint.rotateSecret("third", null);
        assertThat(endpoint.getSecret()).isEqualTo("third");
        assertThat(endpoint.getPreviousSecret(now)).isEqualTo("first");
    }

    @Test
    void turningAnEndpointOnClearsItsFailuresAndReason() {
        WebhookEndpoint endpoint = new WebhookEndpoint(1L, "https://example.com", null, List.of("note.created"));
        endpoint.recordFailure();
        endpoint.disable("broken");

        endpoint.setEnabled(false);
        assertThat(endpoint.isEnabled()).isFalse();
        assertThat(endpoint.getDisabledReason()).isNull();
        assertThat(endpoint.getConsecutiveFailures()).isEqualTo(1);

        endpoint.setEnabled(true);
        assertThat(endpoint.getConsecutiveFailures()).isZero();
    }

    @Test
    void longErrorsAreCutToTheColumnSize() {
        WebhookDelivery delivery = new WebhookDelivery(1L, "msg_1", "note.created", "{}");

        delivery.markRetry(null, "x".repeat(600), Instant.now());
        assertThat(delivery.getLastError()).hasSize(500);

        delivery.markFailed(500, null, Instant.now());
        assertThat(delivery.getLastError()).isNull();
        assertThat(delivery.getAttempts()).isEqualTo(2);
    }

    @Test
    void aSecretSignsTheSameWithOrWithoutItsPrefix() {
        String secret = WebhookSigner.newSecret();

        assertThat(WebhookSigner.sign(secret.substring("whsec_".length()), "msg_1", 1L, "{}"))
                .isEqualTo(WebhookSigner.sign(secret, "msg_1", 1L, "{}"));
    }

    @Test
    void onlyA2xxAnswerIsASuccess() {
        assertThat(new WebhookSender.Result(null, "refused").succeeded()).isFalse();
        assertThat(new WebhookSender.Result(199, null).succeeded()).isFalse();
        assertThat(new WebhookSender.Result(200, null).succeeded()).isTrue();
        assertThat(new WebhookSender.Result(299, null).succeeded()).isTrue();
        assertThat(new WebhookSender.Result(300, null).succeeded()).isFalse();
    }

    @Test
    void anUnreachableOrInvalidReceiverIsReportedNotThrown() throws Exception {
        WebhookProperties properties = properties(true);
        WebhookSender sender = new WebhookSender(properties, new WebhookAddressPolicy(properties));
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        WebhookSender.Result refused =
                sender.send("http://127.0.0.1:" + closedPort + "/hook", "msg_1", 1L, "v1,x", "{}");
        WebhookSender.Result invalid = sender.send("http://bad host/hook", "msg_1", 1L, "v1,x", "{}");
        sender.destroy();

        assertThat(refused.status()).isNull();
        assertThat(refused.error()).isNotBlank();
        assertThat(invalid.error()).startsWith("Invalid URL");
    }

    @Test
    void nothingIsQueuedWhileWebhooksAreOff() {
        WebhookEndpointRepository endpoints = mock(WebhookEndpointRepository.class);
        WebhookDeliveryRepository deliveries = mock(WebhookDeliveryRepository.class);
        WebhookEvents events = new WebhookEvents(
                endpoints, deliveries, properties(false), JsonMapper.builder().build());

        assertThat(events.publish(WebhookEventType.NOTE_CREATED, 1L, Map.of("id", 1)))
                .isZero();
        verifyNoInteractions(endpoints, deliveries);
        assertThat(WebhookEvents.rolesFrom(Role.ADMIN)).containsExactly("ADMIN", "OWNER");
    }

    @Test
    void theServiceRefusesChangesWhileWebhooksAreOff() {
        WebhookService service = service(properties(false), mock(WebhookEndpointRepository.class));
        WebhookRequest request = new WebhookRequest("https://example.com", null, List.of("note.created"));

        assertProblem(() -> service.create(1L, request), "webhooks-disabled");
        assertProblem(
                () -> service.update(1L, 2L, new WebhookUpdateRequest(null, null, null, true)), "webhooks-disabled");
        assertProblem(() -> service.sendTest(1L, 2L), "webhooks-disabled");
        assertProblem(() -> service.retry(1L, 2L, 3L), "webhooks-disabled");
    }

    @Test
    void theServiceEnforcesTheEndpointLimitAndTheTestEventIsNotSubscribable() {
        WebhookEndpointRepository endpoints = mock(WebhookEndpointRepository.class);
        when(endpoints.countByUserId(anyLong())).thenReturn(10L);
        WebhookService service = service(properties(true), endpoints);

        assertProblem(
                () -> service.create(1L, new WebhookRequest("https://example.com", " ", List.of("note.created"))),
                "webhook-limit-reached");
        assertProblem(
                () -> service.create(1L, new WebhookRequest("https://example.com", null, List.of("webhook.test"))),
                "webhook-event-type-invalid");
    }

    private static void assertProblem(Runnable call, String type) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        ApiException.class,
                        ex -> assertThat(ex.getBody().getType().toString()).isEqualTo("urn:problem:" + type));
    }

    private static WebhookService service(WebhookProperties properties, WebhookEndpointRepository endpoints) {
        WebhookAddressPolicy addresses = mock(WebhookAddressPolicy.class);
        when(addresses.validate(anyString())).thenAnswer(call -> call.getArgument(0));
        return new WebhookService(
                endpoints,
                mock(WebhookDeliveryRepository.class),
                mock(WebhookEvents.class),
                addresses,
                mock(UserRepository.class),
                mock(WebhookSecrets.class),
                mock(AuditLog.class),
                properties);
    }

    private static WebhookProperties properties(boolean enabled) {
        return new WebhookProperties(
                enabled,
                List.of("webhook-key-abcdefghijklmnopqrstuvwxyz-0123456789"),
                Role.ADMIN,
                false,
                true,
                10,
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                3,
                5,
                Duration.ofSeconds(5),
                Duration.ofDays(7));
    }
}
