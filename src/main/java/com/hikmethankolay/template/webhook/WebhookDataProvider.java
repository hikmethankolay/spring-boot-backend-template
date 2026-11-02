package com.hikmethankolay.template.webhook;

import com.hikmethankolay.template.user.PersonalDataProvider;
import org.springframework.stereotype.Component;

/** Adds the user's webhook endpoints (URLs, events, dates; never the secrets) to the personal data export. */
@Component
public class WebhookDataProvider implements PersonalDataProvider {

    private final WebhookService webhooks;

    /**
     * Creates the provider.
     *
     * @param webhooks lists the endpoints
     */
    public WebhookDataProvider(WebhookService webhooks) {
        this.webhooks = webhooks;
    }

    /**
     * Returns the section name.
     *
     * @return {@code webhooks}
     */
    @Override
    public String section() {
        return "webhooks";
    }

    /**
     * Lists the user's endpoints.
     *
     * @param userId the user
     * @return the endpoints, newest first
     */
    @Override
    public Object export(long userId) {
        return webhooks.export(userId);
    }
}
