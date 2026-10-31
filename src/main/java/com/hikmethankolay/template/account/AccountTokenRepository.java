package com.hikmethankolay.template.account;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Spring Data repository for {@link AccountToken}. */
public interface AccountTokenRepository extends JpaRepository<AccountToken, Long> {

    /**
     * Finds a token by its hash and purpose and locks it until the transaction ends. A second request redeeming the
     * same token waits, then finds it deleted, so a token is used once even by concurrent requests.
     *
     * @param tokenHash hex SHA-256 of the token
     * @param types     the purposes it may have
     * @return the token, if it exists
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AccountToken> findByTokenHashAndTypeIn(String tokenHash, Collection<AccountTokenType> types);

    /**
     * Tells whether a token of a user for one purpose was created after the given time.
     *
     * @param userId owner of the tokens
     * @param type   purpose of the tokens
     * @param time   cut-off time
     * @return {@code true} if such a token exists
     */
    boolean existsByUserIdAndTypeAndCreatedAtAfter(long userId, AccountTokenType type, Instant time);

    /**
     * Deletes all tokens of a user for one purpose, so only the newest link works.
     *
     * @param userId owner of the tokens
     * @param type   purpose of the tokens to delete
     */
    @Modifying
    @Query("delete from AccountToken t where t.userId = :userId and t.type = :type")
    void deleteAllByUserIdAndType(long userId, AccountTokenType type);

    /**
     * Deletes all tokens that expired before the given time.
     *
     * @param time cut-off time
     * @return the number of deleted tokens
     */
    @Modifying
    @Query("delete from AccountToken t where t.expiresAt < :time")
    int deleteAllExpiredBefore(Instant time);
}
