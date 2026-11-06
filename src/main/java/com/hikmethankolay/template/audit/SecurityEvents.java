package com.hikmethankolay.template.audit;

import com.hikmethankolay.template.audit.dto.SecurityEventResponse;
import com.hikmethankolay.template.common.SortAllowList;
import java.sql.ResultSet;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

/**
 * Reads the audit trail of one user, for {@code GET /api/v1/users/me/security-events}, the administrators' view of a
 * user and the personal data export. Events outlive their user, so the trail of a deleted account can still be read
 * by id.
 */
@Service
public class SecurityEvents {

    /** Properties the events can be sorted by. */
    public static final String SORTABLE_PROPERTIES = "occurredAt";

    /** Shown as the actor of events another user (an administrator) caused, so their id is not revealed. */
    static final String OTHER_ACTOR = "admin";

    private static final SortAllowList SORTING = new SortAllowList(List.of(SORTABLE_PROPERTIES));

    private static final String COLUMNS = "SELECT occurred_at, action, actor_id, client_ip FROM audit_events ";

    // Two constants rather than a concatenated direction, so no SQL is built from request input.
    private static final String PAGE_NEWEST_FIRST =
            COLUMNS + "WHERE user_id = ? ORDER BY occurred_at DESC, id DESC LIMIT ? OFFSET ?";

    private static final String PAGE_OLDEST_FIRST =
            COLUMNS + "WHERE user_id = ? ORDER BY occurred_at ASC, id ASC LIMIT ? OFFSET ?";

    private final JdbcTemplate jdbc;

    /**
     * Creates the service.
     *
     * @param jdbc database access
     */
    public SecurityEvents(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Lists one page of a user's events.
     *
     * @param userId   the user the events are about
     * @param pageable page and size; sorted by {@code occurredAt}, newest first unless {@code occurredAt,asc}
     * @return the page
     * @throws com.hikmethankolay.template.common.ApiException 400 if sorted by anything but {@code occurredAt}
     */
    public Page<SecurityEventResponse> forUser(long userId, Pageable pageable) {
        SORTING.check(pageable);
        Sort.Order order = pageable.getSort().getOrderFor(SORTABLE_PROPERTIES);
        String query = order != null && order.isAscending() ? PAGE_OLDEST_FIRST : PAGE_NEWEST_FIRST;
        List<SecurityEventResponse> events =
                jdbc.query(query, mapper(userId), userId, pageable.getPageSize(), pageable.getOffset());
        return PageableExecutionUtils.getPage(events, pageable, () -> count(userId));
    }

    /**
     * Lists every event of a user, newest first, for the personal data export.
     *
     * @param userId the user
     * @return the events
     */
    public List<SecurityEventResponse> allForUser(long userId) {
        return jdbc.query(COLUMNS + "WHERE user_id = ? ORDER BY occurred_at DESC, id DESC", mapper(userId), userId);
    }

    private long count(long userId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE user_id = ?", Long.class, userId);
        return count == null ? 0 : count;
    }

    /**
     * Maps rows about one user; an actor other than the user is shown as {@value #OTHER_ACTOR}, never by id,
     * and that actor's client address is left out (it is the administrator's, not the user's).
     *
     * @param userId the user the events are about
     * @return the row mapper
     */
    private static RowMapper<SecurityEventResponse> mapper(long userId) {
        String self = String.valueOf(userId);
        return (ResultSet row, int rowNumber) -> {
            String actor = row.getString("actor_id");
            boolean foreign = actor != null && !actor.equals(self);
            return new SecurityEventResponse(
                    row.getTimestamp("occurred_at").toInstant(),
                    row.getString("action"),
                    foreign ? OTHER_ACTOR : actor,
                    foreign ? null : row.getString("client_ip"));
        };
    }
}
