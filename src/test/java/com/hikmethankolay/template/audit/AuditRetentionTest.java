package com.hikmethankolay.template.audit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** The retention cannot be shorter than the 90 days the database keeps anyway, and purges in batches. */
class AuditRetentionTest {

    @Test
    void aRetentionUnder90DaysStopsStartup() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        assertThatThrownBy(() -> new AuditRetention(jdbc, Duration.ofDays(30)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 90 days");
        assertThatCode(() -> new AuditRetention(jdbc, Duration.ofDays(90))).doesNotThrowAnyException();
    }

    @Test
    void purgesBatchesUntilOneComesBackShort() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any()))
                .thenReturn(AuditRetention.BATCH_SIZE, AuditRetention.BATCH_SIZE, 3);

        new AuditRetention(jdbc, Duration.ofDays(365)).purge();

        verify(jdbc, times(3)).queryForObject(anyString(), eq(Integer.class), any(), eq(AuditRetention.BATCH_SIZE));
    }
}
