package com.hikmethankolay.template.audit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** The retention cannot be shorter than the 90 days the database keeps anyway. */
class AuditRetentionTest {

    @Test
    void aRetentionUnder90DaysStopsStartup() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        assertThatThrownBy(() -> new AuditRetention(jdbc, Duration.ofDays(30)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 90 days");
        assertThatCode(() -> new AuditRetention(jdbc, Duration.ofDays(90))).doesNotThrowAnyException();
    }
}
