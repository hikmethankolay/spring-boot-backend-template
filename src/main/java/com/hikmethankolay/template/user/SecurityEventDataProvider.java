package com.hikmethankolay.template.user;

import com.hikmethankolay.template.audit.SecurityEvents;
import org.springframework.stereotype.Component;

/** Adds the user's security events from the audit log to the personal data export. */
@Component
public class SecurityEventDataProvider implements PersonalDataProvider {

    private final SecurityEvents events;

    /**
     * Creates the provider.
     *
     * @param events reads the audit log
     */
    public SecurityEventDataProvider(SecurityEvents events) {
        this.events = events;
    }

    /**
     * Returns the section name.
     *
     * @return {@code securityEvents}
     */
    @Override
    public String section() {
        return "securityEvents";
    }

    /**
     * Lists the user's security events.
     *
     * @param userId the user
     * @return every event about the user, newest first
     */
    @Override
    public Object export(long userId) {
        return events.allForUser(userId);
    }
}
