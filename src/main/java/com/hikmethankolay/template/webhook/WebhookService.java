package com.hikmethankolay.template.webhook;

import com.hikmethankolay.template.audit.AuditEvent;
import com.hikmethankolay.template.audit.AuditLog;
import com.hikmethankolay.template.common.ApiException;
import com.hikmethankolay.template.common.ProblemType;
import com.hikmethankolay.template.common.SortAllowList;
import com.hikmethankolay.template.security.CallerRole;
import com.hikmethankolay.template.user.Role;
import com.hikmethankolay.template.user.UserRepository;
import com.hikmethankolay.template.webhook.dto.WebhookDeliveryResponse;
import com.hikmethankolay.template.webhook.dto.WebhookRequest;
import com.hikmethankolay.template.webhook.dto.WebhookResponse;
import com.hikmethankolay.template.webhook.dto.WebhookSecretResponse;
import com.hikmethankolay.template.webhook.dto.WebhookUpdateRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lets users register, change and remove webhook endpoints, rotate their secrets, send a test event and inspect and
 * retry deliveries. Every endpoint belongs to one user; another user's endpoint looks the same as a missing one.
 */
@Service
public class WebhookService {

    /** Properties the delivery log can be sorted by. */
    public static final String SORTABLE_PROPERTIES = "id, createdAt";

    private static final SortAllowList SORTING = new SortAllowList(List.of(SORTABLE_PROPERTIES.split(", ")));

    /** How long the previous secret keeps signing after a rotation. */
    static final Duration ROTATION_OVERLAP = Duration.ofHours(24);

    private final WebhookEndpointRepository endpoints;

    private final WebhookDeliveryRepository deliveries;

    private final WebhookEvents events;

    private final WebhookAddressPolicy addresses;

    private final UserRepository users;

    private final WebhookSecrets webhookSecrets;

    private final AuditLog audit;

    private final WebhookProperties properties;

    /**
     * Creates the service.
     *
     * @param endpoints  stores the endpoints
     * @param deliveries stores the deliveries
     * @param events     queues test events
     * @param addresses  checks endpoint URLs
     * @param users      reads the caller's role for application-wide events
     * @param webhookSecrets encrypts the signing secrets
     * @param audit      records changes
     * @param properties limits and switches
     */
    public WebhookService(
            WebhookEndpointRepository endpoints,
            WebhookDeliveryRepository deliveries,
            WebhookEvents events,
            WebhookAddressPolicy addresses,
            UserRepository users,
            WebhookSecrets webhookSecrets,
            AuditLog audit,
            WebhookProperties properties) {
        this.endpoints = endpoints;
        this.deliveries = deliveries;
        this.events = events;
        this.addresses = addresses;
        this.users = users;
        this.webhookSecrets = webhookSecrets;
        this.audit = audit;
        this.properties = properties;
    }

    /**
     * Lists the caller's endpoints.
     *
     * @param userId the caller
     * @return the endpoints, newest first
     */
    @Transactional(readOnly = true)
    public List<WebhookResponse> list(long userId) {
        return endpoints.findByUserIdOrderByIdDesc(userId).stream()
                .map(WebhookService::toResponse)
                .toList();
    }

    /**
     * Returns one of the caller's endpoints.
     *
     * @param userId the caller
     * @param id     the endpoint
     * @return the endpoint
     * @throws ApiException 404 if the caller has no such endpoint
     */
    @Transactional(readOnly = true)
    public WebhookResponse get(long userId, long id) {
        return toResponse(find(userId, id));
    }

    /**
     * Registers an endpoint.
     *
     * @param userId  the caller
     * @param request URL, label and events
     * @return the endpoint and its signing secret, shown only now
     * @throws ApiException 400 if the URL or an event is invalid, 403 if webhooks are off or an event needs a higher
     *                      role, 409 if the caller has too many endpoints
     */
    @Transactional
    public WebhookSecretResponse create(long userId, WebhookRequest request) {
        requireEnabled();
        String url = validUrl(request.url());
        List<String> types = validEventTypes(userId, request.eventTypes());
        if (endpoints.countByUserId(userId) >= properties.maxEndpointsPerUser()) {
            throw ApiException.conflict(
                    ProblemType.WEBHOOK_LIMIT_REACHED,
                    "You have " + properties.maxEndpointsPerUser() + " webhook endpoints; delete one first.");
        }
        WebhookEndpoint endpoint = new WebhookEndpoint(userId, url, blankToNull(request.description()), types);
        String secret = WebhookSigner.newSecret();
        endpoint.rotateSecret(encrypt(secret, userId), null);
        endpoints.save(endpoint);
        audit.record(AuditEvent.WEBHOOK_CREATED, userId, "endpointId", endpoint.getId(), "events", types);
        return new WebhookSecretResponse(secret, toResponse(endpoint));
    }

    /**
     * Changes an endpoint.
     *
     * @param userId  the caller
     * @param id      the endpoint
     * @param request the fields to change
     * @return the endpoint
     * @throws ApiException 400 if the URL or an event is invalid, 403 if an event needs a higher role, 404 if the caller
     *                      has no such endpoint
     */
    @Transactional
    public WebhookResponse update(long userId, long id, WebhookUpdateRequest request) {
        requireEnabled();
        WebhookEndpoint endpoint = find(userId, id);
        // The subscriptions the endpoint ends up with are checked even when unchanged: otherwise a caller who may no
        // longer receive application-wide events could point an existing subscription to them at a new URL.
        List<String> types =
                request.eventTypes() != null ? validEventTypes(userId, request.eventTypes()) : endpoint.getEventTypes();
        requireMayReceive(userId, types);
        if (request.url() != null) {
            endpoint.setUrl(validUrl(request.url()));
        }
        if (request.description() != null) {
            endpoint.setDescription(blankToNull(request.description()));
        }
        endpoint.setEventTypes(types);
        if (request.enabled() != null) {
            endpoint.setEnabled(request.enabled());
            if (!request.enabled()) {
                deliveries.failPending(id, Instant.now());
            }
        }
        audit.record(AuditEvent.WEBHOOK_UPDATED, userId, "endpointId", id);
        return toResponse(endpoint);
    }

    /**
     * Removes an endpoint and its deliveries.
     *
     * @param userId the caller
     * @param id     the endpoint
     * @throws ApiException 404 if the caller has no such endpoint
     */
    @Transactional
    public void delete(long userId, long id) {
        endpoints.delete(find(userId, id));
        audit.record(AuditEvent.WEBHOOK_DELETED, userId, "endpointId", id);
    }

    /**
     * Gives an endpoint a new signing secret. The old one keeps signing for 24 hours, so receivers can switch over.
     *
     * @param userId the caller
     * @param id     the endpoint
     * @return the new secret, shown only now
     * @throws ApiException 403 if the endpoint has application-wide events the caller may not receive, 404 if the
     *                      caller has no such endpoint
     */
    @Transactional
    public WebhookSecretResponse rotateSecret(long userId, long id) {
        WebhookEndpoint endpoint = find(userId, id);
        requireMayReceive(userId, endpoint.getEventTypes());
        String secret = WebhookSigner.newSecret();
        endpoint.rotateSecret(encrypt(secret, userId), Instant.now().plus(ROTATION_OVERLAP));
        audit.record(AuditEvent.WEBHOOK_SECRET_ROTATED, userId, "endpointId", id);
        return new WebhookSecretResponse(secret, toResponse(endpoint));
    }

    /**
     * Queues a {@code webhook.test} event for an endpoint.
     *
     * @param userId the caller
     * @param id     the endpoint
     * @return the queued delivery
     * @throws ApiException 403 if webhooks are off, 404 if the caller has no such endpoint, 409 if it is turned off
     */
    @Transactional
    public WebhookDeliveryResponse sendTest(long userId, long id) {
        requireEnabled();
        WebhookEndpoint endpoint = find(userId, id);
        requireEndpointEnabled(endpoint);
        requireMayReceive(userId, endpoint.getEventTypes());
        return toResponse(events.queue(endpoint, WebhookEventType.TEST, Map.of("endpointId", id)));
    }

    /**
     * Lists an endpoint's deliveries.
     *
     * @param userId   the caller
     * @param id       the endpoint
     * @param pageable the page
     * @return one page of deliveries, newest first
     * @throws ApiException 404 if the caller has no such endpoint
     */
    @Transactional(readOnly = true)
    public Page<WebhookDeliveryResponse> deliveries(long userId, long id, Pageable pageable) {
        find(userId, id);
        return deliveries.findByEndpointId(id, SORTING.check(pageable)).map(WebhookService::toResponse);
    }

    /**
     * Sends a delivery again, with a fresh set of attempts.
     *
     * @param userId     the caller
     * @param id         the endpoint
     * @param deliveryId the delivery
     * @return the queued delivery
     * @throws ApiException 403 if webhooks are off, 404 if the caller has no such endpoint or delivery, 409 if the
     *                      endpoint is turned off
     */
    @Transactional
    public WebhookDeliveryResponse retry(long userId, long id, long deliveryId) {
        requireEnabled();
        WebhookEndpoint endpoint = find(userId, id);
        requireEndpointEnabled(endpoint);
        requireMayReceive(userId, endpoint.getEventTypes());
        WebhookDelivery delivery = deliveries
                .findByIdAndEndpointId(deliveryId, id)
                .orElseThrow(() -> ApiException.notFound(
                        ProblemType.WEBHOOK_DELIVERY_NOT_FOUND, "This endpoint has no delivery with this id."));
        delivery.requeue(Instant.now());
        return toResponse(delivery);
    }

    /**
     * Lists the caller's endpoints for the personal data export.
     *
     * @param userId the caller
     * @return the endpoints, without secrets
     */
    @Transactional(readOnly = true)
    public List<WebhookResponse> export(long userId) {
        return list(userId);
    }

    private WebhookEndpoint find(long userId, long id) {
        return endpoints
                .findByIdAndUserId(id, userId)
                .orElseThrow(() -> ApiException.notFound(
                        ProblemType.WEBHOOK_NOT_FOUND, "You have no webhook endpoint with this id."));
    }

    private String validUrl(String url) {
        try {
            return addresses.validate(url);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest(ProblemType.WEBHOOK_URL_INVALID, ex.getMessage());
        }
    }

    private List<String> validEventTypes(long userId, List<String> requested) {
        List<WebhookEventType> types = requested.stream()
                .distinct()
                .map(name -> WebhookEventType.of(name)
                        .filter(type -> type != WebhookEventType.TEST)
                        .orElseThrow(() -> ApiException.badRequest(
                                ProblemType.WEBHOOK_EVENT_TYPE_INVALID, "There is no event type " + name + ".")))
                .toList();
        List<String> names = types.stream().map(WebhookEventType::type).toList();
        requireMayReceive(userId, names);
        return names;
    }

    /**
     * Refuses to create, change, rotate, test or retry an endpoint with application-wide events unless the caller may
     * receive them: both their stored role and the role of the request (a capped access token) must reach
     * {@code app.webhooks.admin-events-min-role}.
     *
     * @param userId the caller
     * @param types  the event names the endpoint has or will have
     * @throws ApiException 403 if an event is application-wide and the caller may not receive it
     */
    private void requireMayReceive(long userId, List<String> types) {
        boolean adminEvents = types.stream()
                .flatMap(name -> WebhookEventType.of(name).stream())
                .anyMatch(type -> type.audience() == WebhookEventType.Audience.ADMINS);
        if (adminEvents && !mayReceiveAdminEvents(userId)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    ProblemType.WEBHOOK_EVENT_NOT_ALLOWED,
                    "Application-wide events need the " + properties.adminEventsMinRole() + " role.");
        }
    }

    private boolean mayReceiveAdminEvents(long userId) {
        Role minimum = properties.adminEventsMinRole();
        // Both the stored role and the request's: a personal access token capped below the minimum cannot subscribe.
        return CallerRole.atLeast(minimum)
                && users.findById(userId)
                        .map(user -> user.getRole().compareTo(minimum) >= 0)
                        .orElse(false);
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN, ProblemType.WEBHOOKS_DISABLED, "Webhooks are turned off on this server.");
        }
    }

    private static void requireEndpointEnabled(WebhookEndpoint endpoint) {
        if (!endpoint.isEnabled()) {
            throw ApiException.conflict(
                    ProblemType.WEBHOOK_ENDPOINT_DISABLED, "Turn the endpoint on before sending to it.");
        }
    }

    private String encrypt(String secret, long userId) {
        return webhookSecrets.encrypt(secret, userId);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static WebhookResponse toResponse(WebhookEndpoint endpoint) {
        return new WebhookResponse(
                endpoint.getId(),
                endpoint.getUrl(),
                endpoint.getDescription(),
                endpoint.getEventTypes(),
                endpoint.isEnabled(),
                endpoint.getDisabledReason(),
                endpoint.getConsecutiveFailures(),
                endpoint.getCreatedAt());
    }

    private static WebhookDeliveryResponse toResponse(WebhookDelivery delivery) {
        boolean pending = delivery.getStatus() == WebhookDelivery.Status.PENDING;
        return new WebhookDeliveryResponse(
                delivery.getId(),
                delivery.getMessageId(),
                delivery.getEventType(),
                delivery.getStatus().name(),
                delivery.getAttempts(),
                delivery.getResponseStatus(),
                delivery.getLastError(),
                delivery.getCreatedAt(),
                pending ? delivery.getNextAttemptAt() : null,
                delivery.getFinishedAt());
    }
}
