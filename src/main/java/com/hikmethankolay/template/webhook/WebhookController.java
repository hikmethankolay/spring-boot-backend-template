package com.hikmethankolay.template.webhook;

import com.hikmethankolay.template.common.ApiDocs;
import com.hikmethankolay.template.common.SortableBy;
import com.hikmethankolay.template.security.TokenService;
import com.hikmethankolay.template.webhook.dto.WebhookDeliveryResponse;
import com.hikmethankolay.template.webhook.dto.WebhookRequest;
import com.hikmethankolay.template.webhook.dto.WebhookResponse;
import com.hikmethankolay.template.webhook.dto.WebhookSecretResponse;
import com.hikmethankolay.template.webhook.dto.WebhookUpdateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lets users register endpoints that receive signed webhook events, and inspect and retry their deliveries. Personal
 * access tokens may call these endpoints, so an integration can register its own receiver.
 */
@RestController
@RequestMapping("/api/v1/webhooks")
@Tag(name = "Webhooks")
public class WebhookController {

    private static final String NOT_FOUND = "You have no webhook endpoint with this id";

    private static final String DISABLED = "Webhooks are turned off on this server";

    private final WebhookService webhooks;

    /**
     * Creates the controller.
     *
     * @param webhooks manages endpoints and deliveries
     */
    public WebhookController(WebhookService webhooks) {
        this.webhooks = webhooks;
    }

    /**
     * Lists the caller's endpoints.
     *
     * @param jwt the caller's token
     * @return the endpoints, newest first
     */
    @GetMapping
    @Operation(operationId = "listWebhooks", summary = "List your webhook endpoints")
    @ApiResponse(
            responseCode = "200",
            description = "The endpoints",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = WebhookResponse.class))))
    public List<WebhookResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return webhooks.list(TokenService.userId(jwt));
    }

    /**
     * Registers an endpoint.
     *
     * @param jwt     the caller's token
     * @param request URL, label and events
     * @return the endpoint and its signing secret
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            operationId = "createWebhook",
            summary = "Register a webhook endpoint",
            description =
                    "Events are POSTed to the URL as JSON `{type, timestamp, data}`, signed per Standard Webhooks "
                            + "(`webhook-id`, `webhook-timestamp`, `webhook-signature` headers). The `whsec_` secret is shown "
                            + "only in this response. Application-wide events such as `user.registered` need the role set by "
                            + "`app.webhooks.admin-events-min-role`.")
    @ApiResponse(
            responseCode = "201",
            description = "Endpoint registered",
            content = @Content(schema = @Schema(implementation = WebhookSecretResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = DISABLED + ", or an event needs a higher role",
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    @ApiResponse(
            responseCode = "409",
            description = "You already have the most endpoints allowed",
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    public WebhookSecretResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody WebhookRequest request) {
        return webhooks.create(TokenService.userId(jwt), request);
    }

    /**
     * Returns one endpoint.
     *
     * @param jwt the caller's token
     * @param id  the endpoint
     * @return the endpoint
     */
    @GetMapping("/{id}")
    @Operation(operationId = "getWebhook", summary = "Get a webhook endpoint")
    @ApiResponse(
            responseCode = "200",
            description = "The endpoint",
            content = @Content(schema = @Schema(implementation = WebhookResponse.class)))
    @ApiResponse(
            responseCode = "404",
            description = NOT_FOUND,
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    public WebhookResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return webhooks.get(TokenService.userId(jwt), id);
    }

    /**
     * Changes an endpoint.
     *
     * @param jwt     the caller's token
     * @param id      the endpoint
     * @param request the fields to change
     * @return the endpoint
     */
    @PatchMapping("/{id}")
    @Operation(
            operationId = "updateWebhook",
            summary = "Change a webhook endpoint",
            description = "Fields left out keep their value. `enabled: false` gives up the pending deliveries; "
                    + "`enabled: true` turns an endpoint the server turned off back on.")
    @ApiResponse(
            responseCode = "200",
            description = "The changed endpoint",
            content = @Content(schema = @Schema(implementation = WebhookResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = DISABLED + ", or an event needs a higher role",
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    @ApiResponse(
            responseCode = "404",
            description = NOT_FOUND,
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    public WebhookResponse update(
            @AuthenticationPrincipal Jwt jwt, @PathVariable long id, @Valid @RequestBody WebhookUpdateRequest request) {
        return webhooks.update(TokenService.userId(jwt), id, request);
    }

    /**
     * Removes an endpoint.
     *
     * @param jwt the caller's token
     * @param id  the endpoint
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "deleteWebhook", summary = "Delete a webhook endpoint")
    @ApiResponse(responseCode = "204", description = "The endpoint and its deliveries are deleted")
    @ApiResponse(
            responseCode = "404",
            description = NOT_FOUND,
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        webhooks.delete(TokenService.userId(jwt), id);
    }

    /**
     * Gives an endpoint a new signing secret.
     *
     * @param jwt the caller's token
     * @param id  the endpoint
     * @return the new secret
     */
    @PostMapping("/{id}/secret")
    @Operation(
            operationId = "rotateWebhookSecret",
            summary = "Rotate a webhook signing secret",
            description =
                    "Returns a new `whsec_` secret, shown only now. For 24 hours each message carries a signature "
                            + "from the old and the new secret, so receivers can switch without dropping messages.")
    @ApiResponse(
            responseCode = "200",
            description = "The new secret",
            content = @Content(schema = @Schema(implementation = WebhookSecretResponse.class)))
    @ApiResponse(
            responseCode = "404",
            description = NOT_FOUND,
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    public WebhookSecretResponse rotateSecret(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return webhooks.rotateSecret(TokenService.userId(jwt), id);
    }

    /**
     * Queues a test event.
     *
     * @param jwt the caller's token
     * @param id  the endpoint
     * @return the queued delivery
     */
    @PostMapping("/{id}/test")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
            operationId = "testWebhook",
            summary = "Send a test event",
            description = "Queues a `webhook.test` event for this endpoint; follow it in the delivery log.")
    @ApiResponse(
            responseCode = "202",
            description = "Test event queued",
            content = @Content(schema = @Schema(implementation = WebhookDeliveryResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = DISABLED,
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    @ApiResponse(
            responseCode = "404",
            description = NOT_FOUND,
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    @ApiResponse(
            responseCode = "409",
            description = "The endpoint is turned off",
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    public WebhookDeliveryResponse test(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return webhooks.sendTest(TokenService.userId(jwt), id);
    }

    /**
     * Lists an endpoint's deliveries.
     *
     * @param jwt      the caller's token
     * @param id       the endpoint
     * @param pageable page, size and sort order
     * @return one page of deliveries
     */
    @GetMapping("/{id}/deliveries")
    @Operation(
            operationId = "listWebhookDeliveries",
            summary = "List an endpoint's deliveries",
            description = "Recent events sent or being sent to this endpoint, with their attempts and the last answer. "
                    + "Finished deliveries are kept for `app.webhooks.delivery-retention`.")
    @ApiResponse(responseCode = "200", description = "One page of deliveries")
    @ApiResponse(
            responseCode = "404",
            description = NOT_FOUND,
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    @SortableBy(WebhookService.SORTABLE_PROPERTIES)
    public Page<WebhookDeliveryResponse> deliveries(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long id,
            @ParameterObject @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC)
                    Pageable pageable) {
        return webhooks.deliveries(TokenService.userId(jwt), id, pageable);
    }

    /**
     * Sends a delivery again.
     *
     * @param jwt        the caller's token
     * @param id         the endpoint
     * @param deliveryId the delivery
     * @return the queued delivery
     */
    @PostMapping("/{id}/deliveries/{deliveryId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
            operationId = "retryWebhookDelivery",
            summary = "Send a delivery again",
            description =
                    "Queues a sent or failed delivery again: the same message (same `webhook-id`) with a fresh set "
                            + "of attempts.")
    @ApiResponse(
            responseCode = "202",
            description = "Delivery queued",
            content = @Content(schema = @Schema(implementation = WebhookDeliveryResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = DISABLED,
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    @ApiResponse(
            responseCode = "404",
            description = "You have no such endpoint, or it has no such delivery",
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    @ApiResponse(
            responseCode = "409",
            description = "The endpoint is turned off, or the delivery is still queued or being sent",
            content = @Content(mediaType = ApiDocs.PROBLEM_JSON, schema = @Schema(ref = ApiDocs.PROBLEM_SCHEMA)))
    public WebhookDeliveryResponse retry(
            @AuthenticationPrincipal Jwt jwt, @PathVariable long id, @PathVariable long deliveryId) {
        return webhooks.retry(TokenService.userId(jwt), id, deliveryId);
    }
}
