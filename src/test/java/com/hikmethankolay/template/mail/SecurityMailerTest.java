package com.hikmethankolay.template.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The device name in a new-login alert comes from the client, so it is reduced to plain words. */
class SecurityMailerTest {

    @Test
    void ordinaryDeviceNamesStayReadable() {
        assertThat(SecurityMailer.plainDevice("Chrome 131.0 on Windows (x64)"))
                .isEqualTo("Chrome 131.0 on Windows (x64)");
    }

    @Test
    void linksLineBreaksAndPunctuationAreRemoved() {
        String device = "Chrome\r\n\r\nYour account is locked! Visit https://evil.example/fix or www.evil.example now";

        assertThat(SecurityMailer.plainDevice(device))
                .doesNotContain("\n", "\r", "!", "https", "evil.example", "www")
                .hasSizeLessThanOrEqualTo(60);
    }

    @Test
    void emptyOrMissingIsUnknown() {
        assertThat(SecurityMailer.plainDevice(null)).isEqualTo("unknown");
        assertThat(SecurityMailer.plainDevice("<>!!")).isEqualTo("unknown");
    }
}
