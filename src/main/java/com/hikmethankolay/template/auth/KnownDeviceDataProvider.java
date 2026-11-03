package com.hikmethankolay.template.auth;

import com.hikmethankolay.template.user.PersonalDataProvider;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Adds the devices the user signed in from (device, network, dates; never the cookie) to the personal data export. */
@Component
public class KnownDeviceDataProvider implements PersonalDataProvider {

    private final JdbcTemplate jdbc;

    /**
     * Creates the provider.
     *
     * @param jdbc database access
     */
    public KnownDeviceDataProvider(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns the section name.
     *
     * @return {@code knownDevices}
     */
    @Override
    public String section() {
        return "knownDevices";
    }

    /**
     * Lists the devices the user signed in from.
     *
     * @param userId the user
     * @return one {@link KnownDevice} per device, most recently seen first
     */
    @Override
    public Object export(long userId) {
        return jdbc.query(
                "SELECT device, network, created_at, last_seen_at FROM known_devices WHERE user_id = ? "
                        + "ORDER BY last_seen_at DESC",
                (rs, row) -> new KnownDevice(
                        rs.getString("device"),
                        rs.getString("network"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("last_seen_at").toInstant()),
                userId);
    }

    /**
     * A device the user signed in from.
     *
     * @param device     browser and system, e.g. {@code Firefox on Linux}
     * @param network    the network it signed in from, or {@code unknown}
     * @param createdAt  its first sign-in
     * @param lastSeenAt its latest sign-in
     */
    public record KnownDevice(String device, String network, Instant createdAt, Instant lastSeenAt) {}
}
