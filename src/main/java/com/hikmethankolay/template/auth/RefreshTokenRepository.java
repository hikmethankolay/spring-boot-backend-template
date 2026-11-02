package com.hikmethankolay.template.auth;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Spring Data repository for {@link RefreshToken}. */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /**
     * Finds a token by its hash and locks the row, so two concurrent refreshes with the same
     * token are handled one after the other.
     *
     * @param tokenHash hex SHA-256 of the token
     * @return the token, if it exists
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Lists the unused, unexpired tokens of a user: one per active session.
     *
     * @param userId owner of the tokens
     * @param now    the current time
     * @return the active tokens, newest first
     */
    @Query("select t from RefreshToken t where t.userId = :userId and t.usedAt is null and t.expiresAt > :now "
            + "order by t.createdAt desc")
    List<RefreshToken> findActiveByUserId(long userId, Instant now);

    /**
     * Deletes every token of a session.
     *
     * @param familyId the session
     * @return the number of deleted tokens
     */
    @Modifying
    @Query("delete from RefreshToken t where t.familyId = :familyId")
    int deleteAllByFamilyId(UUID familyId);

    /**
     * Locks every token of a session that belongs to the user. A refresh of the session in flight holds the lock on
     * its token, so this waits until it commits, and a delete that follows also sees the token it issued.
     *
     * @param userId   owner of the session
     * @param familyId the session
     * @return the locked tokens
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.userId = :userId and t.familyId = :familyId")
    List<RefreshToken> lockAllByUserIdAndFamilyId(long userId, UUID familyId);

    /**
     * Deletes every token of a session if it belongs to the user.
     *
     * @param userId   owner of the session
     * @param familyId the session
     * @return the number of deleted tokens; {@code 0} if the user has no such session
     */
    @Modifying
    @Query("delete from RefreshToken t where t.userId = :userId and t.familyId = :familyId")
    int deleteAllByUserIdAndFamilyId(long userId, UUID familyId);

    /**
     * Locks every token of the user's other sessions, like {@link #lockAllByUserIdAndFamilyId(long, UUID)} for one.
     *
     * @param userId   owner of the sessions
     * @param familyId the session to leave out
     * @return the locked tokens
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.userId = :userId and t.familyId <> :familyId")
    List<RefreshToken> lockAllByUserIdExceptFamily(long userId, UUID familyId);

    /**
     * Deletes every token of the user's other sessions.
     *
     * @param userId   owner of the sessions
     * @param familyId the session to keep
     * @return the number of deleted tokens
     */
    @Modifying
    @Query("delete from RefreshToken t where t.userId = :userId and t.familyId <> :familyId")
    int deleteAllByUserIdExceptFamily(long userId, UUID familyId);

    /**
     * Removes the used tokens of a session except one.
     *
     * @param familyId  the session
     * @param tokenHash hash of the used token to keep
     */
    @Modifying
    @Query(
            "delete from RefreshToken t where t.familyId = :familyId and t.usedAt is not null and t.tokenHash <> :tokenHash")
    void deleteUsedInFamilyExcept(UUID familyId, String tokenHash);

    /**
     * Deletes every token of a user, ending all their sessions.
     *
     * @param userId owner of the tokens
     * @return the number of deleted tokens
     */
    @Modifying
    @Query("delete from RefreshToken t where t.userId = :userId")
    int deleteAllByUserId(long userId);

    /**
     * Deletes all tokens that expired before the given time.
     *
     * @param time cut-off time
     * @return the number of deleted tokens
     */
    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < :time")
    int deleteAllExpiredBefore(Instant time);
}
