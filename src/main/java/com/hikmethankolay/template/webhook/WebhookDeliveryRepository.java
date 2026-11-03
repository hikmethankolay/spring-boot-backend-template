package com.hikmethankolay.template.webhook;

import jakarta.persistence.LockModeType;
import java.time.Instant;
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
     * Locks the oldest due delivery.
     *
     * @param now the current time
     * @return the locked delivery, or empty if none is due or every due one is locked by another sender
     */
    @Query(
            value = "select * from webhook_deliveries where status = 'PENDING' and next_attempt_at <= :now "
                    + "order by next_attempt_at, id limit 1 for update skip locked",
            nativeQuery = true)
    Optional<WebhookDelivery> lockDue(Instant now);

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
