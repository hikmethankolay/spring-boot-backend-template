package com.hikmethankolay.template.user;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/**
 * Spring Data repository for {@link User}. Query methods are derived from their names.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Finds a user whose username or email matches.
     *
     * @param username username to match
     * @param email    lower-case email to match
     * @return the matching user, if any
     */
    Optional<User> findByUsernameOrEmail(String username, String email);

    /**
     * Finds a user by email.
     *
     * @param email lower-case email to match
     * @return the matching user, if any
     */
    Optional<User> findByEmail(String email);

    /**
     * Checks whether a username is taken.
     *
     * @param username username to check
     * @return {@code true} if a user has this username
     */
    boolean existsByUsername(String username);

    /**
     * Checks whether an email is taken.
     *
     * @param email lower-case email to check
     * @return {@code true} if a user has this email
     */
    boolean existsByEmail(String email);

    /**
     * Checks whether any user has a role.
     *
     * @param role role to check
     * @return {@code true} if at least one user has this role
     */
    boolean existsByRole(Role role);

    /**
     * Replaces a password hash with a stronger hash of the same password, unless the hash changed in the
     * meantime (e.g. a password change). Skips the {@code @Version} check: the password is the same, so
     * a concurrent update of other fields must not fail the login that triggered this.
     *
     * @param id      user id
     * @param oldHash the hash the login was checked against
     * @param newHash the new hash
     * @return the number of updated rows: 1, or 0 if the hash changed
     */
    @Modifying
    @Query("update User u set u.passwordHash = :newHash where u.id = :id and u.passwordHash = :oldHash")
    int upgradePasswordHash(long id, String oldHash, String newHash);
}
