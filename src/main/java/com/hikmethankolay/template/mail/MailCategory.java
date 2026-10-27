package com.hikmethankolay.template.mail;

import java.util.Arrays;
import java.util.Optional;

/**
 * What an email is about, which decides whether its recipient can turn it off. Transactional categories are always
 * sent and carry no unsubscribe link; optional ones are on by default, carry an unsubscribe footer and
 * {@code List-Unsubscribe} headers, and are skipped for users who turned them off. Adding a category is one
 * constant.
 */
public enum MailCategory {

    /** Sign-up, verification and password reset emails. */
    ACCOUNT("account", "Account", "Sign-up, email verification and password reset", false),

    /** Alerts about sign-ins and changes to the account's security settings. */
    SECURITY("security", "Security alerts", "Sign-ins from new devices and changes to your security settings", false),

    /** Announcements of new features and changes. */
    PRODUCT_UPDATES("product-updates", "Product updates", "News about new features and changes", true);

    private final String key;

    private final String title;

    private final String description;

    private final boolean optional;

    /**
     * Creates a category.
     *
     * @param key         stable name used in the API, the database and unsubscribe tokens
     * @param title       name shown to users
     * @param description what the category covers, shown on the preferences page
     * @param optional    whether users can turn it off
     */
    MailCategory(String key, String title, String description, boolean optional) {
        this.key = key;
        this.title = title;
        this.description = description;
        this.optional = optional;
    }

    /**
     * Returns the stable name.
     *
     * @return e.g. {@code product-updates}
     */
    public String key() {
        return key;
    }

    /**
     * Returns the name shown to users.
     *
     * @return e.g. {@code Product updates}
     */
    public String title() {
        return title;
    }

    /**
     * Returns what the category covers.
     *
     * @return a one-line description
     */
    public String description() {
        return description;
    }

    /**
     * Tells whether users can turn the category off.
     *
     * @return {@code true} for optional categories, {@code false} for transactional ones
     */
    public boolean optional() {
        return optional;
    }

    /**
     * Finds a category by its stable name.
     *
     * @param key the name, e.g. {@code product-updates}
     * @return the category, or empty if none has that name
     */
    public static Optional<MailCategory> fromKey(String key) {
        return Arrays.stream(values())
                .filter(category -> category.key.equals(key))
                .findFirst();
    }
}
