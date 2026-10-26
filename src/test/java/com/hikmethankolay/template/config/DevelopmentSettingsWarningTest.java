package com.hikmethankolay.template.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** Without the prod profile, a database on another machine gets one warning; local ones get none. */
@ExtendWith(OutputCaptureExtension.class)
class DevelopmentSettingsWarningTest {

    @Test
    void recognisesLocalAndRemoteDatabases() {
        assertThat(DevelopmentSettingsWarning.remoteHost("jdbc:postgresql://localhost:5432/app"))
                .isNull();
        assertThat(DevelopmentSettingsWarning.remoteHost("jdbc:postgresql://127.0.0.1/app"))
                .isNull();
        assertThat(DevelopmentSettingsWarning.remoteHost("jdbc:postgresql://db:5432/app"))
                .isNull();
        assertThat(DevelopmentSettingsWarning.remoteHost("jdbc:postgresql://db.acme.test:5432/app"))
                .isEqualTo("db.acme.test");
        assertThat(DevelopmentSettingsWarning.remoteHost("jdbc:postgresql://10.0.4.7:5432/app"))
                .isEqualTo("10.0.4.7");
        assertThat(DevelopmentSettingsWarning.remoteHost(null)).isNull();
        assertThat(DevelopmentSettingsWarning.remoteHost("not a jdbc url")).isNull();
        assertThat(DevelopmentSettingsWarning.remoteHost("jdbc:postgresql://bad host/app"))
                .isNull();
    }

    @Test
    void warnsOnceForARemoteDatabase(CapturedOutput output) {
        new DevelopmentSettingsWarning("jdbc:postgresql://localhost:5432/app");
        assertThat(output).doesNotContain("The prod profile is off");

        new DevelopmentSettingsWarning("jdbc:postgresql://db.acme.test:5432/app");
        assertThat(output).contains("The prod profile is off, but the database is on db.acme.test");
    }
}
