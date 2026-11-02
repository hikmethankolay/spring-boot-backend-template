package com.hikmethankolay.template.mail;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/**
 * Spring Data repository for {@link OutboxEmail}.
 *
 * <p>The locking queries use {@code FOR UPDATE SKIP LOCKED}, so several instances can send at the
 * same time without sending any email twice.</p>
 */
public interface OutboxEmailRepository extends JpaRepository<OutboxEmail, Long> {

    /**
     * Locks the emails that are due, skipping dead letters and rows another instance is sending: emails that belong to no broadcast
     * first, so a large broadcast never delays a password reset, then oldest first.
     *
     * @param now   the current time
     * @param limit maximum number of emails
     * @return the locked emails
     */
    @Query(
            value = "select * from email_outbox where failed_at is null and next_attempt_at <= :now "
                    + "order by (broadcast_id is not null), id limit :limit for update skip locked",
            nativeQuery = true)
    List<OutboxEmail> lockDue(Instant now, int limit);

    /**
     * Counts the emails waiting that belong to no broadcast; dead letters are not waiting.
     *
     * @return the number of transactional and other single emails waiting
     */
    long countByBroadcastIdIsNullAndFailedAtIsNull();

    /**
     * Counts the broadcast emails waiting; dead letters are not waiting.
     *
     * @return the number of emails of broadcasts waiting
     */
    long countByBroadcastIdIsNotNullAndFailedAtIsNull();

    /**
     * Deletes the dead letters given up before a time.
     *
     * @param before the oldest {@code failed_at} kept
     * @return the number of emails deleted
     */
    @Modifying
    @Query(value = "delete from email_outbox where failed_at < :before", nativeQuery = true)
    int deleteFailedBefore(Instant before);

    /**
     * Locks one email, unless another instance is sending it.
     *
     * @param id the email id
     * @return the locked email, or empty if it is gone or locked
     */
    @Query(value = "select * from email_outbox where id = :id for update skip locked", nativeQuery = true)
    Optional<OutboxEmail> lockById(long id);
}
