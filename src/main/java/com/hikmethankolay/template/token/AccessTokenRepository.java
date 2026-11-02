package com.hikmethankolay.template.token;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Access to {@code access_tokens}. */
public interface AccessTokenRepository extends JpaRepository<AccessToken, Long> {

    /**
     * Finds a token by the hash of its value.
     *
     * @param tokenHash SHA-256 of the token
     * @return the token, expired or not
     */
    Optional<AccessToken> findByTokenHash(String tokenHash);

    /**
     * Lists a user's unexpired tokens, newest first.
     *
     * @param userId the owner
     * @param now    the current time
     * @return the tokens
     */
    List<AccessToken> findByUserIdAndExpiresAtAfterOrderByIdDesc(long userId, Instant now);

    /**
     * Counts a user's unexpired tokens.
     *
     * @param userId the owner
     * @param now    the current time
     * @return how many there are
     */
    long countByUserIdAndExpiresAtAfter(long userId, Instant now);

    /**
     * Deletes one of a user's tokens.
     *
     * @param id     the token
     * @param userId the owner; another user's token is not touched
     * @return 1 if it was deleted, 0 if the user has no such token
     */
    @Modifying
    @Query("DELETE FROM AccessToken t WHERE t.id = :id AND t.userId = :userId")
    int deleteByIdAndUserId(long id, long userId);

    /**
     * Deletes every token of a user.
     *
     * @param userId the owner
     * @return how many were deleted
     */
    @Modifying
    @Query("DELETE FROM AccessToken t WHERE t.userId = :userId")
    int deleteAllByUserId(long userId);

    /**
     * Records a use, at most once a minute per token.
     *
     * @param id    the token
     * @param now   the time of the use
     * @param since earlier uses after this time are recent enough
     * @return 1 if the time was written
     */
    @Modifying
    @Query("UPDATE AccessToken t SET t.lastUsedAt = :now WHERE t.id = :id "
            + "AND (t.lastUsedAt IS NULL OR t.lastUsedAt < :since)")
    int touch(long id, Instant now, Instant since);

    /**
     * Deletes expired tokens.
     *
     * @param now the current time
     * @return how many were deleted
     */
    @Modifying
    @Query("DELETE FROM AccessToken t WHERE t.expiresAt <= :now")
    int deleteExpired(Instant now);
}
