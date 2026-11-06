package com.hikmethankolay.template.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hikmethankolay.template.IntegrationTest;
import com.hikmethankolay.template.common.ApiException;
import com.hikmethankolay.template.webhook.dto.WebhookRequest;
import com.hikmethankolay.template.webhook.dto.WebhookUpdateRequest;
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Events reach a real HTTP receiver, signed per Standard Webhooks, with retries and automatic turn-off. */
class WebhookIntegrationTest extends IntegrationTest {

    private static final String WEBHOOKS = "/api/v1/webhooks";

    @Autowired
    private WebhookDispatcher dispatcher;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WebhookService webhooks;

    @Autowired
    private WebhookProperties properties;

    @Autowired
    private PlatformTransactionManager transactions;

    private HttpServer receiver;

    private final BlockingQueue<Received> received = new LinkedBlockingQueue<>();

    private final AtomicInteger answer = new AtomicInteger(204);

    @BeforeEach
    void startReceiver() throws IOException {
        receiver = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        receiver.createContext("/hook", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Received(
                    exchange.getRequestHeaders().getFirst("webhook-id"),
                    exchange.getRequestHeaders().getFirst("webhook-timestamp"),
                    exchange.getRequestHeaders().getFirst("webhook-signature"),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    body));
            exchange.sendResponseHeaders(answer.get(), -1);
            exchange.close();
        });
        receiver.start();
    }

    @AfterEach
    void stopReceiver() {
        receiver.stop(0);
    }

    @Test
    void retriesRunOutAndRepeatedFailuresTurnTheEndpointOff() throws Exception {
        // The test profile allows 3 attempts per delivery and turns an endpoint off after 5 failures in a row.
        String session = registerAndLogin(uniqueUsername());
        long endpoint = endpointId(create(session, "note.created"));
        answer.set(500);

        createNote(session);
        Instant start = Instant.now();
        for (int hour = 0; hour < 3; hour++) {
            drain(start.plus(Duration.ofHours(hour)));
            next();
        }
        mockMvc.perform(get(WEBHOOKS + "/" + endpoint + "/deliveries").with(bearer(session)))
                .andExpect(jsonPath("$.content[0].status").value("FAILED"))
                .andExpect(jsonPath("$.content[0].attempts").value(3));

        createNote(session);
        for (int hour = 3; hour < 5; hour++) {
            drain(start.plus(Duration.ofHours(hour)));
            next();
        }
        mockMvc.perform(get(WEBHOOKS + "/" + endpoint).with(bearer(session)))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.disabledReason").value("5 failed attempts in a row."));
        mockMvc.perform(get(WEBHOOKS + "/" + endpoint + "/deliveries").with(bearer(session)))
                .andExpect(jsonPath("$.content[0].status").value("FAILED"));
    }

    @Test
    void anEndpointCanBeChangedAndTurningItOffGivesUpItsQueue() throws Exception {
        String session = registerAndLogin(uniqueUsername());
        long endpoint = endpointId(create(session, "note.created"));
        createNote(session);

        mockMvc.perform(patch(WEBHOOKS + "/" + endpoint)
                        .with(bearer(session))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(jsonPath("$.enabled").value(false));
        mockMvc.perform(get(WEBHOOKS + "/" + endpoint + "/deliveries").with(bearer(session)))
                .andExpect(jsonPath("$.content[0].status").value("FAILED"))
                .andExpect(jsonPath("$.content[0].lastError").value("Endpoint turned off"));

        mockMvc.perform(patch(WEBHOOKS + "/" + endpoint)
                        .with(bearer(session))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(
                                "{\"url\":\"http://127.0.0.1:%d/hook\",\"description\":\" \","
                                        + "\"eventTypes\":[\"note.deleted\",\"note.deleted\"]}",
                                receiver.getAddress().getPort())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").doesNotExist())
                .andExpect(jsonPath("$.eventTypes.length()").value(1))
                .andExpect(jsonPath("$.eventTypes[0]").value("note.deleted"));
    }

    @Test
    void oneOwnersBacklogDoesNotHoldUpAnotherOwnersDelivery() throws Exception {
        String busy = registerAndLogin(uniqueUsername());
        String other = registerAndLogin(uniqueUsername());
        create(busy, "note.created");
        create(other, "note.created");
        long first = createNote(busy);
        long second = createNote(busy);
        long third = createNote(busy);
        long others = createNote(other);

        // Queued last, the other owner's delivery still goes out in the first round, before the backlog's second.
        dispatcher.deliverDue(Instant.now());
        List<Long> order = new ArrayList<>();
        for (Received message = received.poll(2, TimeUnit.SECONDS);
                message != null;
                message = received.poll(200, TimeUnit.MILLISECONDS)) {
            order.add(((Number) JsonPath.read(message.body(), "$.data.id")).longValue());
        }
        assertThat(order).contains(first, second, third, others);
        assertThat(order.indexOf(others)).isLessThan(order.indexOf(second));
    }

    @Test
    void aSuspendedOwnersQueuedEventsAreNotSent() throws Exception {
        String username = uniqueUsername();
        long userId = register(username);
        String session = login(username, PASSWORD);
        long endpoint = endpointId(create(session, "note.created"));
        createNote(session);

        mockMvc.perform(put("/api/v1/users/{id}/suspension", userId)
                        .with(bearer(login(ADMIN_USERNAME, ADMIN_PASSWORD))))
                .andExpect(status().isOk());
        dispatcher.deliverDue(Instant.now());

        assertThat(received.poll(200, TimeUnit.MILLISECONDS)).isNull();
        assertThat(jdbc.queryForObject(
                        "select status from webhook_deliveries where endpoint_id = ?", String.class, endpoint))
                .isEqualTo("FAILED");
    }

    @Test
    void theScheduledJobsSendAndThenForgetDeliveries() throws Exception {
        String session = registerAndLogin(uniqueUsername());
        long endpoint = endpointId(create(session, "note.created"));
        createNote(session);

        dispatcher.deliverDue();
        next();
        jdbc.update(
                "update webhook_deliveries set finished_at = now() - interval '8 days' where endpoint_id = ?",
                endpoint);
        dispatcher.deleteOldDeliveries();

        mockMvc.perform(get(WEBHOOKS + "/" + endpoint + "/deliveries").with(bearer(session)))
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    @Test
    void anEventReachesTheReceiverSignedAndIsLogged() throws Exception {
        String session = registerAndLogin(uniqueUsername());
        ResultActions created = create(session, "note.created").andExpect(status().isCreated());
        String secret = JsonPath.read(body(created), "$.secret");
        long endpoint = endpointId(created);

        long note = createNote(session);
        dispatcher.deliverDue(Instant.now());

        Received message = next();
        assertThat(message.id()).startsWith("msg_");
        assertThat(Long.parseLong(message.timestamp())).isCloseTo(Instant.now().getEpochSecond(), Offset.offset(60L));
        assertThat(message.contentType()).startsWith("application/json");
        assertThat(message.signatures()).containsExactly(signature(secret, message));
        assertThat((String) JsonPath.read(message.body(), "$.type")).isEqualTo("note.created");
        assertThat(((Number) JsonPath.read(message.body(), "$.data.id")).longValue())
                .isEqualTo(note);
        mockMvc.perform(get(WEBHOOKS + "/" + endpoint + "/deliveries").with(bearer(session)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("SENT"))
                .andExpect(jsonPath("$.content[0].attempts").value(1))
                .andExpect(jsonPath("$.content[0].responseStatus").value(204))
                .andExpect(jsonPath("$.content[0].messageId").value(message.id()));
    }

    @Test
    void eventsTheEndpointDidNotSubscribeToAreNotSent() throws Exception {
        String session = registerAndLogin(uniqueUsername());
        create(session, "note.deleted").andExpect(status().isCreated());

        createNote(session);
        dispatcher.deliverDue(Instant.now());

        assertThat(received.poll(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void aFailedDeliveryIsRetriedAfterTheBackoff() throws Exception {
        String session = registerAndLogin(uniqueUsername());
        long endpoint = endpointId(create(session, "note.created"));
        answer.set(500);

        createNote(session);
        Instant now = Instant.now();
        dispatcher.deliverDue(now);
        Received first = next();
        mockMvc.perform(get(WEBHOOKS + "/" + endpoint + "/deliveries").with(bearer(session)))
                .andExpect(jsonPath("$.content[0].status").value("PENDING"))
                .andExpect(jsonPath("$.content[0].attempts").value(1))
                .andExpect(jsonPath("$.content[0].responseStatus").value(500))
                .andExpect(jsonPath("$.content[0].lastError").value("HTTP 500"))
                .andExpect(jsonPath("$.content[0].nextAttemptAt").exists());

        dispatcher.deliverDue(now);
        assertThat(received.poll(200, TimeUnit.MILLISECONDS)).isNull();

        answer.set(200);
        drain(now.plus(Duration.ofMinutes(1)));
        assertThat(next().id()).isEqualTo(first.id());
        mockMvc.perform(get(WEBHOOKS + "/" + endpoint).with(bearer(session)))
                .andExpect(jsonPath("$.consecutiveFailures").value(0));
    }

    @Test
    void aGoneReceiverTurnsTheEndpointOffUntilItsOwnerTurnsItOn() throws Exception {
        String session = registerAndLogin(uniqueUsername());
        long endpoint = endpointId(create(session, "note.created"));
        answer.set(410);

        createNote(session);
        dispatcher.deliverDue(Instant.now());
        next();

        mockMvc.perform(get(WEBHOOKS + "/" + endpoint).with(bearer(session)))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.disabledReason").value("The receiver answered 410 Gone."));
        mockMvc.perform(post(WEBHOOKS + "/" + endpoint + "/test").with(bearer(session)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem:webhook-endpoint-disabled"));
        createNote(session);
        mockMvc.perform(get(WEBHOOKS + "/" + endpoint + "/deliveries").with(bearer(session)))
                .andExpect(jsonPath("$.page.totalElements").value(1));

        mockMvc.perform(patch(WEBHOOKS + "/" + endpoint)
                        .with(bearer(session))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.disabledReason").doesNotExist());
    }

    @Test
    void afterARotationBothSecretsSignForADay() throws Exception {
        String session = registerAndLogin(uniqueUsername());
        ResultActions created = create(session, "note.created");
        String oldSecret = JsonPath.read(body(created), "$.secret");
        long endpoint = endpointId(created);

        String newSecret = JsonPath.read(
                body(mockMvc.perform(post(WEBHOOKS + "/" + endpoint + "/secret").with(bearer(session)))
                        .andExpect(status().isOk())),
                "$.secret");
        mockMvc.perform(post(WEBHOOKS + "/" + endpoint + "/test").with(bearer(session)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.eventType").value("webhook.test"));
        dispatcher.deliverDue(Instant.now());

        Received message = next();
        assertThat(newSecret).isNotEqualTo(oldSecret);
        assertThat(message.signatures()).containsExactly(signature(newSecret, message), signature(oldSecret, message));
    }

    @Test
    void applicationWideEventsNeedTheConfiguredRole() throws Exception {
        String user = registerAndLogin(uniqueUsername());
        create(user, "user.registered")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:problem:webhook-event-not-allowed"));

        String owner = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        long endpoint = endpointId(create(owner, "user.registered").andExpect(status().isCreated()));
        try {
            String newcomer = uniqueUsername();
            long newcomerId = register(newcomer);
            dispatcher.deliverDue(Instant.now());

            Received message = nextMatching("user.registered", newcomer);
            assertThat(((Number) JsonPath.read(message.body(), "$.data.userId")).longValue())
                    .isEqualTo(newcomerId);
        } finally {
            mockMvc.perform(delete(WEBHOOKS + "/" + endpoint).with(bearer(owner)))
                    .andExpect(status().isNoContent());
        }
    }

    @Test
    void anIntegrationCanRegisterItsReceiverWithAnAccessToken() throws Exception {
        String session = registerAndLogin(uniqueUsername());
        String token = JsonPath.read(
                body(mockMvc.perform(post("/api/v1/users/me/access-tokens")
                        .with(bearer(session))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(
                                "{\"name\":\"zapier\",\"scope\":\"WRITE\",\"expiresInDays\":30,"
                                        + "\"currentPassword\":\"%s\"}",
                                PASSWORD)))),
                "$.token");

        create(token, "note.created").andExpect(status().isCreated());
        mockMvc.perform(get(WEBHOOKS).with(bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventTypes[0]").value("note.created"));
    }

    @Test
    void invalidRequestsAndOtherUsersEndpointsAreRefused() throws Exception {
        String alice = registerAndLogin(uniqueUsername());
        long endpoint = endpointId(create(alice, "note.created"));
        String bob = registerAndLogin(uniqueUsername());

        create(bob, "no.such.event")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:webhook-event-type-invalid"));
        mockMvc.perform(post(WEBHOOKS)
                        .with(bearer(bob))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"ftp://127.0.0.1/hook\",\"eventTypes\":[\"note.created\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:webhook-url-invalid"));
        mockMvc.perform(get(WEBHOOKS + "/" + endpoint).with(bearer(bob)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:problem:webhook-not-found"));
        mockMvc.perform(delete(WEBHOOKS + "/" + endpoint).with(bearer(bob))).andExpect(status().isNotFound());
        mockMvc.perform(post(WEBHOOKS + "/" + endpoint + "/deliveries/999999/retry")
                        .with(bearer(alice)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:problem:webhook-delivery-not-found"));
    }

    @Test
    void aDeliveryCanBeSentAgain() throws Exception {
        String session = registerAndLogin(uniqueUsername());
        long endpoint = endpointId(create(session, "note.created"));
        createNote(session);
        dispatcher.deliverDue(Instant.now());
        Received first = next();
        long delivery = ((Number) JsonPath.read(
                        body(mockMvc.perform(
                                get(WEBHOOKS + "/" + endpoint + "/deliveries").with(bearer(session)))),
                        "$.content[0].id"))
                .longValue();

        mockMvc.perform(post(WEBHOOKS + "/" + endpoint + "/deliveries/" + delivery + "/retry")
                        .with(bearer(session)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attempts").value(0));
        dispatcher.deliverDue(Instant.now());

        assertThat(next().id()).isEqualTo(first.id());
    }

    @Test
    void aPendingDeliveryCannotBeSentAgain() throws Exception {
        String session = registerAndLogin(uniqueUsername());
        long endpoint = endpointId(create(session, "note.created"));
        createNote(session);

        mockMvc.perform(post(WEBHOOKS + "/" + endpoint + "/deliveries/" + deliveryId(session, endpoint) + "/retry")
                        .with(bearer(session)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem:webhook-delivery-pending"));
    }

    @Test
    void anotherUsersDeliveriesAreNotFound() throws Exception {
        String alice = registerAndLogin(uniqueUsername());
        long aliceEndpoint = endpointId(create(alice, "note.created"));
        String bob = registerAndLogin(uniqueUsername());
        long bobEndpoint = endpointId(create(bob, "note.created"));
        createNote(bob);
        long bobDelivery = deliveryId(bob, bobEndpoint);

        mockMvc.perform(get(WEBHOOKS + "/" + bobEndpoint + "/deliveries").with(bearer(alice)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:problem:webhook-not-found"));
        mockMvc.perform(post(WEBHOOKS + "/" + aliceEndpoint + "/deliveries/" + bobDelivery + "/retry")
                        .with(bearer(alice)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:problem:webhook-delivery-not-found"));
    }

    @Test
    void aDeliveryBeingSentIsNotDueForAnotherInstance() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch arrived = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HttpServer slow = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        slow.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        slow.createContext("/hook", exchange -> {
            requests.incrementAndGet();
            arrived.countDown();
            try {
                // Under the test profile's 2s response timeout, so the held request still succeeds.
                release.await(1500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        slow.start();
        try {
            String session = registerAndLogin(uniqueUsername());
            long endpoint =
                    endpointId(create(session, "note.created", slow.getAddress().getPort()));
            createNote(session);

            CompletableFuture<Integer> first =
                    CompletableFuture.supplyAsync(() -> dispatcher.deliverDue(Instant.now()));
            assertThat(arrived.await(5, TimeUnit.SECONDS)).isTrue();
            dispatcher.deliverDue(Instant.now());
            release.countDown();
            first.get(10, TimeUnit.SECONDS);

            assertThat(requests).hasValue(1);
            mockMvc.perform(get(WEBHOOKS + "/" + endpoint + "/deliveries").with(bearer(session)))
                    .andExpect(jsonPath("$.content[0].status").value("SENT"));
        } finally {
            slow.stop(0);
        }
    }

    @Test
    void failuresRecordedAtTheSameTimeByTwoInstancesAreBothCounted() throws Exception {
        String session = registerAndLogin(uniqueUsername());
        long endpoint = endpointId(create(session, "note.created"));
        answer.set(500);
        createNote(session);
        CountDownLatch locked = new CountDownLatch(1);

        // Another instance recording a failure for the same endpoint, still uncommitted while this one records its own.
        CompletableFuture<Void> other =
                CompletableFuture.runAsync(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    jdbc.update(
                            "update webhook_endpoints set consecutive_failures = consecutive_failures + 1 where id = ?",
                            endpoint);
                    locked.countDown();
                    try {
                        next();
                        Thread.sleep(500);
                    } catch (InterruptedException ex) {
                        throw new IllegalStateException(ex);
                    }
                }));
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
        dispatcher.deliverDue(Instant.now());
        other.get(10, TimeUnit.SECONDS);

        assertThat(jdbc.queryForObject(
                        "select consecutive_failures from webhook_endpoints where id = ?", Integer.class, endpoint))
                .isEqualTo(2);
    }

    @Test
    void anOwnerTurningAnEndpointOnWhileASenderTurnsItOffEndsUpOn() throws Exception {
        String session = registerAndLogin(uniqueUsername());
        long endpoint = endpointId(create(session, "note.created"));
        long userId = jdbc.queryForObject("select user_id from webhook_endpoints where id = ?", Long.class, endpoint);
        CountDownLatch disabled = new CountDownLatch(1);

        // A sender giving up on the endpoint, not yet committed while its owner turns it on.
        CompletableFuture<Void> sender =
                CompletableFuture.runAsync(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    jdbc.update(
                            "update webhook_endpoints set enabled = false, disabled_reason = 'gave up' where id = ?",
                            endpoint);
                    disabled.countDown();
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ex) {
                        throw new IllegalStateException(ex);
                    }
                }));
        assertThat(disabled.await(5, TimeUnit.SECONDS)).isTrue();
        webhooks.update(userId, endpoint, new WebhookUpdateRequest(null, null, null, true));
        sender.get(10, TimeUnit.SECONDS);

        assertThat(jdbc.queryForObject("select enabled from webhook_endpoints where id = ?", Boolean.class, endpoint))
                .isTrue();
    }

    @Test
    void concurrentRegistrationsStayWithinTheEndpointLimit() throws Exception {
        long userId = register(uniqueUsername());
        WebhookRequest request = new WebhookRequest(
                "http://127.0.0.1:" + receiver.getAddress().getPort() + "/hook", null, List.of("note.created"));
        for (int i = 1; i < properties.maxEndpointsPerUser(); i++) {
            webhooks.create(userId, request);
        }
        CountDownLatch created = new CountDownLatch(1);

        // The last free slot is taken but not yet committed while a second registration counts.
        CompletableFuture<Void> first =
                CompletableFuture.runAsync(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    webhooks.create(userId, request);
                    created.countDown();
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ex) {
                        throw new IllegalStateException(ex);
                    }
                }));
        assertThat(created.await(5, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> webhooks.create(userId, request))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        ex -> assertThat(ex.getBody().getType().toString())
                                .isEqualTo("urn:problem:webhook-limit-reached"));
        first.get(10, TimeUnit.SECONDS);

        assertThat(jdbc.queryForObject(
                        "select count(*) from webhook_endpoints where user_id = ?", Integer.class, userId))
                .isEqualTo(properties.maxEndpointsPerUser());
    }

    @Test
    void anUndecryptableSecretTurnsTheEndpointOffWithoutBlockingOtherDeliveries() throws Exception {
        String broken = registerAndLogin(uniqueUsername());
        long brokenEndpoint = endpointId(create(broken, "note.created"));
        String healthy = registerAndLogin(uniqueUsername());
        create(healthy, "note.created");
        // As if its key had been dropped from WEBHOOK_ENCRYPTION_KEY.
        jdbc.update(
                "update webhook_endpoints set secret = 'v1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA' where id = ?",
                brokenEndpoint);

        createNote(broken);
        long healthyNote = createNote(healthy);
        dispatcher.deliverDue(Instant.now());

        Received message = next();
        assertThat(((Number) JsonPath.read(message.body(), "$.data.id")).longValue())
                .isEqualTo(healthyNote);
        mockMvc.perform(get(WEBHOOKS + "/" + brokenEndpoint).with(bearer(broken)))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.disabledReason").value(WebhookDispatcher.UNDECRYPTABLE));
        mockMvc.perform(get(WEBHOOKS + "/" + brokenEndpoint + "/deliveries").with(bearer(broken)))
                .andExpect(jsonPath("$.content[0].status").value("FAILED"));
    }

    @Test
    void theDataExportListsEndpointsWithoutSecrets() throws Exception {
        String session = registerAndLogin(uniqueUsername());
        String secret = JsonPath.read(body(create(session, "note.created")), "$.secret");

        String export = body(mockMvc.perform(get("/api/v1/users/me/export").with(bearer(session)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.related.webhooks[0].eventTypes[0]").value("note.created")));
        assertThat(export).doesNotContain(secret);
    }

    private ResultActions create(String bearer, String eventType) throws Exception {
        return create(bearer, eventType, receiver.getAddress().getPort());
    }

    private ResultActions create(String bearer, String eventType, int port) throws Exception {
        return mockMvc.perform(post(WEBHOOKS)
                .with(bearer(bearer))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(
                        "{\"url\":\"http://127.0.0.1:%d/hook\",\"description\":\"test\",\"eventTypes\":[\"%s\"]}",
                        port, eventType)));
    }

    private long deliveryId(String session, long endpoint) throws Exception {
        return ((Number) JsonPath.read(
                        body(mockMvc.perform(
                                get(WEBHOOKS + "/" + endpoint + "/deliveries").with(bearer(session)))),
                        "$.content[0].id"))
                .longValue();
    }

    private long createNote(String session) throws Exception {
        String note = body(mockMvc.perform(post("/api/v1/notes")
                        .with(bearer(session))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"t\",\"body\":\"b\"}"))
                .andExpect(status().isCreated()));
        return ((Number) JsonPath.read(note, "$.id")).longValue();
    }

    /**
     * Sends everything due at a time. Moving the clock forward also makes other tests' leftover deliveries due, more
     * than one batch of them; each attempt finishes a delivery or moves it past {@code at}, so this ends.
     */
    private void drain(Instant at) {
        while (dispatcher.deliverDue(at) > 0) {
            // Next batch.
        }
    }

    private Received next() throws InterruptedException {
        Received message = received.poll(5, TimeUnit.SECONDS);
        assertThat(message).as("a message reached the receiver").isNotNull();
        return message;
    }

    private Received nextMatching(String type, String username) throws InterruptedException {
        List<Received> seen = new ArrayList<>();
        Received message;
        while ((message = received.poll(5, TimeUnit.SECONDS)) != null) {
            seen.add(message);
            Map<String, Object> data = JsonPath.read(message.body(), "$.data");
            if (type.equals(JsonPath.read(message.body(), "$.type")) && username.equals(data.get("username"))) {
                return message;
            }
        }
        throw new AssertionError("No " + type + " for " + username + " among " + seen);
    }

    private static long endpointId(ResultActions created) throws Exception {
        return ((Number) JsonPath.read(body(created), "$.endpoint.id")).longValue();
    }

    private static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    /** Verifies like a receiver would, independently of {@link WebhookSigner}. */
    private static String signature(String secret, Received message) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Base64.getDecoder().decode(secret.substring("whsec_".length())), "HmacSHA256"));
        byte[] signed = mac.doFinal(
                (message.id() + "." + message.timestamp() + "." + message.body()).getBytes(StandardCharsets.UTF_8));
        return "v1," + Base64.getEncoder().encodeToString(signed);
    }

    private record Received(String id, String timestamp, String signature, String contentType, String body) {

        List<String> signatures() {
            return List.of(signature.split(" "));
        }
    }
}
