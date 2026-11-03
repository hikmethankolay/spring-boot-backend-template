package com.hikmethankolay.template.webhook;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/** Access to {@code webhook_endpoints}. */
public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpoint, Long> {

    /**
     * Lists a user's endpoints, newest first.
     *
     * @param userId the owner
     * @return the endpoints
     */
    List<WebhookEndpoint> findByUserIdOrderByIdDesc(long userId);

    /**
     * Finds one of a user's endpoints.
     *
     * @param id     the endpoint
     * @param userId the owner; another user's endpoint is not found
     * @return the endpoint
     */
    Optional<WebhookEndpoint> findByIdAndUserId(long id, long userId);

    /**
     * Finds one of a user's endpoints and locks it until the transaction ends, so an owner's change and a sender
     * turning the endpoint off are applied one after the other, each on the other's result.
     *
     * @param id     the endpoint
     * @param userId the owner; another user's endpoint is not found
     * @return the locked endpoint
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from WebhookEndpoint e where e.id = :id and e.userId = :userId")
    Optional<WebhookEndpoint> lockByIdAndUserId(long id, long userId);

    /**
     * Finds an endpoint and locks it until the transaction ends, so its failure count is updated by one sender at a
     * time.
     *
     * @param id the endpoint
     * @return the locked endpoint
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from WebhookEndpoint e where e.id = :id")
    Optional<WebhookEndpoint> lockById(long id);

    /**
     * Holds a lock on a user's endpoints until the transaction ends, so concurrent registrations count one after
     * another.
     *
     * @param userId the owner
     * @return always 1
     */
    @Query(
            value = "select 1 from pg_advisory_xact_lock(hashtextextended('webhook_endpoints:' || :userId, 0))",
            nativeQuery = true)
    int lockUser(long userId);

    /**
     * Counts a user's endpoints.
     *
     * @param userId the owner
     * @return how many there are
     */
    long countByUserId(long userId);

    /**
     * Finds the enabled endpoints of an active user that subscribe to an event.
     *
     * @param type   the event name
     * @param userId the user the event is about
     * @return the receiving endpoints
     */
    @Query(
            value = "select e.* from webhook_endpoints e join users u on u.id = e.user_id "
                    + "where e.enabled and u.suspended_at is null and :type = any(e.event_types) "
                    + "and e.user_id = :userId",
            nativeQuery = true)
    List<WebhookEndpoint> findOwnerSubscribers(String type, long userId);

    /**
     * Finds the enabled endpoints of active users with one of the given roles that subscribe to an event.
     *
     * @param type  the event name
     * @param roles roles allowed to receive it
     * @return the receiving endpoints
     */
    @Query(
            value = "select e.* from webhook_endpoints e join users u on u.id = e.user_id "
                    + "where e.enabled and u.suspended_at is null and :type = any(e.event_types) "
                    + "and u.role in (:roles)",
            nativeQuery = true)
    List<WebhookEndpoint> findRoleSubscribers(String type, Collection<String> roles);
}
