package com.hikmethankolay.template.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Unit tests of {@link AccountMailer#humanize}. */
class AccountMailerTest {

    @Test
    void wholeHoursAreWrittenAsHours() {
        assertThat(AccountMailer.humanize(Duration.ofHours(24))).isEqualTo("24 hours");
        assertThat(AccountMailer.humanize(Duration.ofHours(1))).isEqualTo("1 hour");
    }

    @Test
    void anythingElseIsWrittenAsMinutes() {
        assertThat(AccountMailer.humanize(Duration.ofMinutes(90))).isEqualTo("90 minutes");
        assertThat(AccountMailer.humanize(Duration.ofMinutes(1))).isEqualTo("1 minute");
    }
}
