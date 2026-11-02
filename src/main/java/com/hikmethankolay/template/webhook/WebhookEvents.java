package com.hikmethankolay.template.webhook;

import com.hikmethankolay.template.user.Role;
import com.hikmethankolay.template.user.UserDeleted;
import com.hikmethankolay.template.user.UserRegistered;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes webhook events. Call {@link #publish} inside the transaction of the change: one delivery per subscribed
 * endpoint is written to the outbox with it, so an event is sent if and only if the change commits.
 *
 * <pre>{@code
 * webhookEvents.publish(WebhookEventType.NOTE_CREATED, userId, Map.of("id", note.getId()));
 * }</pre>
 *
 * <p>Keep {@code data} small, ideally ids: receivers fetch the current state through the API, so a payload never
 * carries personal data to an endpoint that should no longer get it, and an out-of-order delivery does no harm.</p>
 */
@Component
public class WebhookEvents {

    private final WebhookEndpointRepository endpoints;

    private final WebhookDeliveryRepository deliveries;

    private final WebhookProperties properties;

    private final JsonMapper json;

    /**
     * Creates the publisher.
     *
     * @param endpoints  finds the subscribed endpoints
     * @param deliveries stores the queued deliveries
     * @param properties whether webhooks are on and who receives application-wide events
     * @param json       writes the payloads
     */
    public WebhookEvents(
            WebhookEndpointRepository endpoints,
            WebhookDeliveryRepository deliveries,
            WebhookProperties properties,
            JsonMapper json) {
        this.endpoints = endpoints;
        this.deliveries = deliveries;
        this.properties = properties;
        this.json = json;
    }

    /**
     * Queues an event for every endpoint subscribed to it.
     *
     * @param type   the event
     * @param userId the user it is about; for {@link WebhookEventType.Audience#OWNER} events, their endpoints receive
     *               it
     * @param data   the {@code data} object of the payload
     * @return how many deliveries were queued
     */
    @Transactional
    public int publish(WebhookEventType type, long userId, Map<String, ?> data) {
        if (!properties.enabled()) {
            return 0;
        }
        List<WebhookEndpoint> receivers = type.audience() == WebhookEventType.Audience.OWNER
                ? endpoints.findOwnerSubscribers(type.type(), userId)
                : endpoints.findRoleSubscribers(type.type(), rolesFrom(properties.adminEventsMinRole()));
        if (receivers.isEmpty()) {
            return 0;
        }
        String messageId = newMessageId();
        String payload = payload(type, data);
        receivers.forEach(
                endpoint -> deliveries.save(new WebhookDelivery(endpoint.getId(), messageId, type.type(), payload)));
        return receivers.size();
    }

    /**
     * Queues an event for one endpoint, whatever it subscribes to.
     *
     * @param endpoint the receiver
     * @param type     the event
     * @param data     the {@code data} object of the payload
     * @return the queued delivery
     */
    @Transactional
    public WebhookDelivery queue(WebhookEndpoint endpoint, WebhookEventType type, Map<String, ?> data) {
        return deliveries.save(new WebhookDelivery(endpoint.getId(), newMessageId(), type.type(), payload(type, data)));
    }

    /**
     * Sends {@code user.registered} with the account that created it.
     *
     * @param event the new user
     */
    @EventListener
    public void userRegistered(UserRegistered event) {
        publish(
                WebhookEventType.USER_REGISTERED,
                event.userId(),
                Map.of("userId", event.userId(), "username", event.username()));
    }

    /**
     * Sends {@code user.deleted} with the deletion.
     *
     * @param event the deleted user
     */
    @EventListener
    public void userDeleted(UserDeleted event) {
        publish(WebhookEventType.USER_DELETED, event.userId(), Map.of("userId", event.userId()));
    }

    /**
     * Lists the roles at or above a minimum.
     *
     * @param minimum the lowest role
     * @return the role names
     */
    static List<String> rolesFrom(Role minimum) {
        return Arrays.stream(Role.values())
                .filter(role -> role.compareTo(minimum) >= 0)
                .map(Role::name)
                .toList();
    }

    private String payload(WebhookEventType type, Map<String, ?> data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type.type());
        body.put("timestamp", Instant.now().toString());
        body.put("data", data);
        return json.writeValueAsString(body);
    }

    private static String newMessageId() {
        return "msg_" + UUID.randomUUID().toString().replace("-", "");
    }
}
