package com.hikmethankolay.template.mail;

import com.hikmethankolay.template.user.PersonalDataProvider;
import org.springframework.stereotype.Component;

/**
 * Adds the user's email preferences to the personal data export. They are deleted with the account by the foreign
 * key of {@code email_preferences}.
 */
@Component
public class EmailPreferenceDataProvider implements PersonalDataProvider {

    private final EmailPreferenceService preferences;

    /**
     * Creates the provider.
     *
     * @param preferences email preference logic
     */
    public EmailPreferenceDataProvider(EmailPreferenceService preferences) {
        this.preferences = preferences;
    }

    /**
     * Returns the section name.
     *
     * @return {@code emailPreferences}
     */
    @Override
    public String section() {
        return "emailPreferences";
    }

    /**
     * Returns the user's choice for every optional email category.
     *
     * @param userId the user requesting the export
     * @return the preferences
     */
    @Override
    public Object export(long userId) {
        return preferences.preferences(userId);
    }
}
