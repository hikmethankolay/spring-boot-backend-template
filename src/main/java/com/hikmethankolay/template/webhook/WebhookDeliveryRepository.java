package com.hikmethankolay.template.webhook;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/**
 * Access to {@code webhook_deliveries}.
 *
 * <p>{@link #lockDue} uses {@code FOR UPDATE SKIP LOCKED}: the sender leases the row it locked (moves its due time
 * forward) and commits before the HTTP call, so several instances send without sending the same delivery twice and
 * no transaction stays open while a receiver answers.</p>
 */
public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, Long> {

    /**
     * Locks the oldest due delivery of an endpoint whose owner is not in {@code servedOwners}. The dispatcher passes
     * the owners it already sent to in the current round, so one owner's backlog (or slow receiver) takes at most one
     * send per round and cannot hold up everyone else's deliveries.
     *
     * @param now          the current time
     * @param servedOwners user ids to skip; never empty (pass a value no user has, such as {@code -1})
     * @return the locked delivery, or empty if none is due or every due one is locked by another sender
     */
    @Query(
            value = "select d.* from webhook_deliveries d join webhook_endpoints e on e.id = d.endpoint_id "
                    + "where d.status = 'PENDING' and d.next_attempt_at <= :now and e.user_id not in (:servedOwners) "
                    + "order by d.next_attempt_at, d.id limit 1 for update of d skip locked",
            nativeQuery = true)
    Optional<WebhookDelivery> lockDue(Instant now, Collection<Long> servedOwners);

    /**
     * Lists an endpoint's deliveries in the requested order.
     *
     * @param endpointId the endpoint
     * @param pageable   the page
     * @return one page of deliveries
     */
    Page<WebhookDelivery> findByEndpointId(long endpointId, Pageable pageable);

    /**
     * Finds one delivery of an endpoint and locks it until the transaction ends, so two retries cannot both queue it.
     *
     * @param id         the delivery
     * @param endpointId the endpoint it must belong to
     * @return the delivery
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<WebhookDelivery> findByIdAndEndpointId(long id, long endpointId);

    /**
     * Gives up the pending deliveries of an endpoint that was turned off.
     *
     * @param endpointId the endpoint
     * @param now        the current time
     * @return how many were marked failed
     */
    @Modifying
    @Query(
            value = "update webhook_deliveries set status = 'FAILED', finished_at = :now, "
                    + "last_error = 'Endpoint turned off' where endpoint_id = :endpointId and status = 'PENDING'",
            nativeQuery = true)
    int failPending(long endpointId, Instant now);

    /**
     * Deletes finished deliveries.
     *
     * @param before deliveries finished before this time are deleted
     * @return how many were deleted
     */
    @Modifying
    @Query(value = "delete from webhook_deliveries where finished_at < :before", nativeQuery = true)
    int deleteFinishedBefore(Instant before);
}
