package com.hikmethankolay.template.security;

import com.hikmethankolay.template.user.Role;
import com.hikmethankolay.template.user.User;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Security view of a {@link User}, used only while a password is checked during login.
 *
 * @param id           database id of the user
 * @param username     username
 * @param passwordHash encoded password, never exposed through {@code toString()}
 * @param role          role of the user
 * @param emailVerified  whether the email address has been confirmed
 * @param sessionVersion the user's session version when the password was checked
 * @param suspended      whether an administrator suspended the account
 */
public record AppUserDetails(
        long id,
        String username,
        String passwordHash,
        Role role,
        boolean emailVerified,
        int sessionVersion,
        boolean suspended)
        implements UserDetails {

    /**
     * Copies the fields needed for authentication from an entity.
     *
     * @param user the persisted user
     * @return the security view of the user
     */
    static AppUserDetails from(User user) {
        return new AppUserDetails(
                user.getId(),
                user.getUsername(),
                user.getPasswordHash(),
                user.getRole(),
                user.isEmailVerified(),
                user.getSessionVersion(),
                user.getSuspendedAt() != null);
    }

    /**
     * Returns the user's single role as a Spring Security authority.
     *
     * @return a list containing {@code ROLE_<role>}
     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    /**
     * Returns the encoded password for verification.
     *
     * @return the password hash
     */
    @Override
    public String getPassword() {
        return passwordHash;
    }

    /**
     * Returns the username.
     *
     * @return the username
     */
    @Override
    public String getUsername() {
        return username;
    }

    /**
     * Describes the user without the password hash, so it is safe to log.
     *
     * @return id, username and role
     */
    @Override
    public String toString() {
        return "AppUserDetails[id=" + id + ", username=" + username + ", role=" + role + "]";
    }
}
