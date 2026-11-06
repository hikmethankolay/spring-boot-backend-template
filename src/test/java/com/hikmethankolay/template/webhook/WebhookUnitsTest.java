package com.hikmethankolay.template.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hikmethankolay.template.audit.AuditLog;
import com.hikmethankolay.template.common.ApiException;
import com.hikmethankolay.template.mail.SecurityMailer;
import com.hikmethankolay.template.user.Role;
import com.hikmethankolay.template.user.User;
import com.hikmethankolay.template.user.UserRepository;
import com.hikmethankolay.template.webhook.dto.WebhookRequest;
import com.hikmethankolay.template.webhook.dto.WebhookUpdateRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/** The webhook building blocks on their own: entities, signer, sender, publisher and the service's guards. */
class WebhookUnitsTest {

    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();

    @BeforeEach
    void startScheduler() {
        scheduler.initialize();
    }

    @AfterEach
    void stopScheduler() {
        scheduler.shutdown();
    }

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
    void aReceiverThatAnswersAByteAtATimeIsCutOffAtTheDeadline() throws Exception {
        // 1s connect + 1s response timeout: a 2s deadline, though each byte arrives well within the 1s read timeout.
        WebhookProperties properties = properties(true, true, Duration.ofSeconds(1));
        WebhookSender sender = new WebhookSender(properties, new WebhookAddressPolicy(properties));
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().start(() -> dribble(server));
            Instant start = Instant.now();

            WebhookSender.Result result =
                    sender.send("http://127.0.0.1:" + server.getLocalPort() + "/hook", "msg_1", 1L, "v1,x", "{}");

            assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(5));
            assertThat(result.status()).isNull();
            assertThat(result.error()).isEqualTo("No answer within 2 seconds");
        } finally {
            sender.destroy();
        }
    }

    @Test
    void theDeadlineFiresWhenTheCallerHoldsTheOnlySchedulerThread() throws Exception {
        // As in production with virtual threads off: @Scheduled runs send() on Boot's one-thread scheduler.
        WebhookProperties properties = properties(true, true, Duration.ofSeconds(1));
        WebhookSender sender = new WebhookSender(properties, new WebhookAddressPolicy(properties));
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().start(() -> dribble(server));
            String url = "http://127.0.0.1:" + server.getLocalPort() + "/hook";

            WebhookSender.Result result = scheduler
                    .submit(() -> sender.send(url, "msg_1", 1L, "v1,x", "{}"))
                    .get(5, java.util.concurrent.TimeUnit.SECONDS);

            assertThat(result.error()).isEqualTo("No answer within 2 seconds");
        } finally {
            sender.destroy();
        }
    }

    /** Sends a status line and then one header byte every 300 ms, for 12 seconds. */
    private static void dribble(ServerSocket server) {
        try (Socket socket = server.accept()) {
            OutputStream out = socket.getOutputStream();
            out.write("HTTP/1.1 200 OK\r\nX-Slow: ".getBytes(StandardCharsets.US_ASCII));
            for (int i = 0; i < 40; i++) {
                out.write('a');
                out.flush();
                Thread.sleep(300);
            }
        } catch (IOException | InterruptedException ex) {
            // The sender hung up.
        }
    }

    @Test
    void aHostThatResolvesToAPrivateAddressIsRefusedWhenConnecting() throws Exception {
        // As in production, minus HTTPS: the URL passed the save-time check, and localhost resolves to loopback.
        WebhookProperties properties = properties(true, false, Duration.ofSeconds(2));
        WebhookSender sender = new WebhookSender(properties, new WebhookAddressPolicy(properties));
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(200);

            WebhookSender.Result result =
                    sender.send("http://localhost:" + server.getLocalPort() + "/hook", "msg_1", 1L, "v1,x", "{}");

            assertThat(result.status()).isNull();
            assertThat(result.error()).contains("non-public");
            assertThatThrownBy(server::accept).isInstanceOf(SocketTimeoutException.class);
        } finally {
            sender.destroy();
        }
    }

    @Test
    void aSlowSendBacksOffFromWhenItEndedNotFromWhenTheBatchStarted() {
        WebhookDeliveryRepository deliveries = mock(WebhookDeliveryRepository.class);
        WebhookEndpointRepository endpoints = mock(WebhookEndpointRepository.class);
        UserRepository users = mock(UserRepository.class);
        WebhookSecrets secrets = mock(WebhookSecrets.class);
        WebhookSender sender = mock(WebhookSender.class);
        WebhookDelivery delivery = new WebhookDelivery(1L, "msg_1", "note.created", "{}");
        ReflectionTestUtils.setField(delivery, "id", 7L);
        WebhookEndpoint endpoint = new WebhookEndpoint(1L, "https://example.com", null, List.of("note.created"));
        when(deliveries.lockDue(any(), any())).thenReturn(Optional.of(delivery), Optional.empty());
        when(deliveries.findById(7L)).thenReturn(Optional.of(delivery));
        when(endpoints.findById(any())).thenReturn(Optional.of(endpoint));
        when(endpoints.lockById(anyLong())).thenReturn(Optional.of(endpoint));
        when(users.findById(any())).thenReturn(Optional.of(new User("u", "u@example.com", "x", Role.USER)));
        when(secrets.decrypt(any(), anyLong())).thenReturn(Optional.of(WebhookSigner.newSecret()));
        when(sender.send(any(), any(), anyLong(), any(), any())).thenAnswer(call -> {
            Thread.sleep(300);
            return new WebhookSender.Result(500, null);
        });
        WebhookDispatcher dispatcher = new WebhookDispatcher(
                deliveries,
                endpoints,
                users,
                secrets,
                sender,
                properties(true),
                mock(SecurityMailer.class),
                mock(AuditLog.class),
                new SimpleMeterRegistry(),
                mock(PlatformTransactionManager.class));
        Instant now = Instant.now().plus(Duration.ofHours(1));

        dispatcher.deliverDue(now);

        assertThat(delivery.getNextAttemptAt())
                .isAfterOrEqualTo(now.plusSeconds(30).plusMillis(300));
    }

    @Test
    void endpointUrlsAreResolvedBeforeATransactionHoldsAConnection() {
        WebhookEndpointRepository endpoints = mock(WebhookEndpointRepository.class);
        WebhookEndpoint saved = new WebhookEndpoint(1L, "https://example.com", null, List.of("note.created"));
        ReflectionTestUtils.setField(saved, "id", 2L);
        when(endpoints.save(any())).thenAnswer(call -> {
            WebhookEndpoint endpoint = call.getArgument(0);
            ReflectionTestUtils.setField(endpoint, "id", 2L);
            return endpoint;
        });
        when(endpoints.lockByIdAndUserId(anyLong(), anyLong())).thenReturn(Optional.of(saved));
        WebhookAddressPolicy addresses = mock(WebhookAddressPolicy.class);
        when(addresses.validate(anyString())).thenAnswer(call -> call.getArgument(0));
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        WebhookService service = service(properties(true), endpoints, addresses, transactions);

        service.create(1L, new WebhookRequest("https://example.com", null, List.of("note.created")));
        service.update(1L, 2L, new WebhookUpdateRequest("https://example.org", null, null, null));

        InOrder order = inOrder(addresses, transactions);
        order.verify(addresses).validate("https://example.com");
        order.verify(transactions).getTransaction(any());
        order.verify(addresses).validate("https://example.org");
        order.verify(transactions).getTransaction(any());
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
        return service(properties, endpoints, addresses, mock(PlatformTransactionManager.class));
    }

    private static WebhookService service(
            WebhookProperties properties,
            WebhookEndpointRepository endpoints,
            WebhookAddressPolicy addresses,
            PlatformTransactionManager transactions) {
        return new WebhookService(
                endpoints,
                mock(WebhookDeliveryRepository.class),
                mock(WebhookEvents.class),
                addresses,
                mock(UserRepository.class),
                mock(WebhookSecrets.class),
                mock(AuditLog.class),
                properties,
                transactions);
    }

    private static WebhookProperties properties(boolean enabled) {
        return properties(enabled, true, Duration.ofSeconds(2));
    }

    private static WebhookProperties properties(boolean enabled, boolean allowPrivate, Duration timeout) {
        return new WebhookProperties(
                enabled,
                List.of("webhook-key-abcdefghijklmnopqrstuvwxyz-0123456789"),
                Role.ADMIN,
                false,
                allowPrivate,
                10,
                timeout,
                timeout,
                3,
                5,
                Duration.ofSeconds(5),
                Duration.ofDays(7));
    }
}
