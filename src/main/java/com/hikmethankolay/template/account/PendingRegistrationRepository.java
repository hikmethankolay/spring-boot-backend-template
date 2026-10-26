package com.hikmethankolay.template.account;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Spring Data repository for {@link PendingRegistration}. */
public interface PendingRegistrationRepository extends JpaRepository<PendingRegistration, Long> {

    /**
     * Finds a registration by the hash of its token.
     *
     * @param tokenHash hex SHA-256 of the token
     * @return the registration, if it exists
     */
    Optional<PendingRegistration> findByTokenHash(String tokenHash);

    /**
     * Finds the newest registration for an address.
     *
     * @param email the normalised address
     * @return the newest registration, if any
     */
    Optional<PendingRegistration> findFirstByEmailOrderByCreatedAtDesc(String email);

    /**
     * Tells whether a registration for an address was created after the given time.
     *
     * @param email the normalised address
     * @param time  cut-off time
     * @return {@code true} if such a registration exists
     */
    boolean existsByEmailAndCreatedAtAfter(String email, Instant time);

    /**
     * Deletes every registration for an address, so only the newest link works.
     *
     * @param email the normalised address
     */
    @Modifying
    @Query("delete from PendingRegistration r where r.email = :email")
    void deleteAllByEmail(String email);

    /**
     * Deletes all registrations whose link expired before the given time.
     *
     * @param time cut-off time
     * @return the number of deleted registrations
     */
    @Modifying
    @Query("delete from PendingRegistration r where r.expiresAt < :time")
    int deleteAllExpiredBefore(Instant time);
}
