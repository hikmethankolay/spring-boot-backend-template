package com.hikmethankolay.template.audit;

import com.hikmethankolay.template.audit.dto.SecurityEventResponse;
import com.hikmethankolay.template.common.SortAllowList;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.jdbc.core.JdbcTemplate;
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
                jdbc.query(query, SecurityEvents::map, userId, pageable.getPageSize(), pageable.getOffset());
        return PageableExecutionUtils.getPage(events, pageable, () -> count(userId));
    }

    /**
     * Lists every event of a user, newest first, for the personal data export.
     *
     * @param userId the user
     * @return the events
     */
    public List<SecurityEventResponse> allForUser(long userId) {
        return jdbc.query(
                COLUMNS + "WHERE user_id = ? ORDER BY occurred_at DESC, id DESC", SecurityEvents::map, userId);
    }

    private long count(long userId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE user_id = ?", Long.class, userId);
        return count == null ? 0 : count;
    }

    private static SecurityEventResponse map(ResultSet row, int rowNumber) throws SQLException {
        return new SecurityEventResponse(
                row.getTimestamp("occurred_at").toInstant(),
                row.getString("action"),
                row.getString("actor_id"),
                row.getString("client_ip"));
    }
}
