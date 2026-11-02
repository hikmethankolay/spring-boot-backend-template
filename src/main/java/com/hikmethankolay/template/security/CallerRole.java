package com.hikmethankolay.template.security;

import com.hikmethankolay.template.user.Role;
import java.util.Arrays;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The role the current request was authenticated with, as opposed to the caller's role in the database.
 *
 * <p>The two differ for a personal access token capped by {@code app.access-tokens.max-role}: an owner's token capped
 * at {@code ADMIN} carries {@code ROLE_ADMIN}. Checks that read the role from the database (so a demotion applies at
 * once) must also call {@link #atLeast(Role)}, or a capped token would regain the role the cap removed.</p>
 */
public final class CallerRole {

    /** Not instantiable. */
    private CallerRole() {}

    /**
     * Tells whether the current request carries a role at least as high as the given one.
     *
     * @param minimum the lowest role allowed
     * @return {@code true} if one of its {@code ROLE_*} authorities is {@code minimum} or higher, or if no request is
     *     authenticated (code running outside a request, such as startup or a scheduled job)
     */
    public static boolean atLeast(Role minimum) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return true;
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority -> Arrays.stream(Role.values())
                        .anyMatch(role -> role.compareTo(minimum) >= 0 && authority.equals("ROLE_" + role.name())));
    }
}
