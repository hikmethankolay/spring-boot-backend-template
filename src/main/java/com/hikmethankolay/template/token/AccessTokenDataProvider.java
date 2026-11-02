package com.hikmethankolay.template.token;

import com.hikmethankolay.template.user.PersonalDataProvider;
import org.springframework.stereotype.Component;

/** Adds the user's access tokens (names, scopes, dates; never the tokens) to the personal data export. */
@Component
public class AccessTokenDataProvider implements PersonalDataProvider {

    private final AccessTokenService accessTokens;

    /**
     * Creates the provider.
     *
     * @param accessTokens lists the tokens
     */
    public AccessTokenDataProvider(AccessTokenService accessTokens) {
        this.accessTokens = accessTokens;
    }

    /**
     * Returns the section name.
     *
     * @return {@code accessTokens}
     */
    @Override
    public String section() {
        return "accessTokens";
    }

    /**
     * Lists the user's unexpired tokens.
     *
     * @param userId the user
     * @return the tokens, newest first
     */
    @Override
    public Object export(long userId) {
        return accessTokens.list(userId);
    }
}
