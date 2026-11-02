package com.hikmethankolay.template.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.hikmethankolay.template.user.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** The role a request carries can be lower than the caller's stored role, for a capped access token. */
class CallerRoleTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aRequestCarryingARoleMeetsThatRoleAndTheOnesBelowIt() {
        authenticateWith("ROLE_ADMIN");

        assertThat(CallerRole.atLeast(Role.USER)).isTrue();
        assertThat(CallerRole.atLeast(Role.ADMIN)).isTrue();
        assertThat(CallerRole.atLeast(Role.OWNER))
                .as("an owner's token capped at ADMIN")
                .isFalse();
    }

    @Test
    void onlyRoleAuthoritiesCount() {
        authenticateWith("SCOPE_OWNER", "OWNER", "ROLE_USER");

        assertThat(CallerRole.atLeast(Role.ADMIN)).isFalse();
        assertThat(CallerRole.atLeast(Role.USER)).isTrue();
    }

    @Test
    void codeOutsideARequestIsNotRestricted() {
        assertThat(CallerRole.atLeast(Role.OWNER)).isTrue();
    }

    private static void authenticateWith(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("1", null, authorities));
    }
}
