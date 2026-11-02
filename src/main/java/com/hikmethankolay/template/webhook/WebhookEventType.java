package com.hikmethankolay.template.webhook;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Optional;

/**
 * The events endpoints can subscribe to. Add a constant here when your feature publishes a new event.
 *
 * <p>An {@link Audience#OWNER} event goes to the endpoints of the user it is about. An {@link Audience#ADMINS} event
 * is about the application as a whole and goes to the endpoints of users whose role reaches
 * {@code app.webhooks.admin-events-min-role}.</p>
 */
public enum WebhookEventType {
    /** Sent by {@code POST /api/v1/webhooks/{id}/test}. */
    TEST("webhook.test", Audience.OWNER),
    /** The user created a note (data: id). */
    NOTE_CREATED("note.created", Audience.OWNER),
    /** The user changed a note (data: id). */
    NOTE_UPDATED("note.updated", Audience.OWNER),
    /** The user deleted a note (data: id). */
    NOTE_DELETED("note.deleted", Audience.OWNER),
    /** Someone signed up (data: userId, username). */
    USER_REGISTERED("user.registered", Audience.ADMINS),
    /** An account was deleted, by its owner or an administrator (data: userId). */
    USER_DELETED("user.deleted", Audience.ADMINS);

    /** Who receives an event. */
    public enum Audience {
        /** The endpoints of the user the event is about. */
        OWNER,
        /** The endpoints of administrators (see {@code app.webhooks.admin-events-min-role}). */
        ADMINS
    }

    private final String type;

    private final Audience audience;

    WebhookEventType(String type, Audience audience) {
        this.type = type;
        this.audience = audience;
    }

    /**
     * Returns the name used in the payload and in subscriptions.
     *
     * @return e.g. {@code note.created}
     */
    @JsonValue
    public String type() {
        return type;
    }

    /**
     * Returns who receives the event.
     *
     * @return the audience
     */
    public Audience audience() {
        return audience;
    }

    /**
     * Finds an event type by its name.
     *
     * @param type e.g. {@code note.created}
     * @return the event type, or empty if there is none
     */
    public static Optional<WebhookEventType> of(String type) {
        return Arrays.stream(values()).filter(value -> value.type.equals(type)).findFirst();
    }
}
