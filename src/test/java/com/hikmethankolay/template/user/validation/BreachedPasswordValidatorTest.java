package com.hikmethankolay.template.user.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.hikmethankolay.template.TestProperties;
import com.hikmethankolay.template.config.AppProperties;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

/** Unit tests of {@link BreachedPasswordValidator}; the Have I Been Pwned call is stubbed. */
class BreachedPasswordValidatorTest {

    /** SHA-1 of "correct horse battery staple", split the way the range API splits it. */
    private static final String PREFIX = "ABF7A";

    private static final String SUFFIX = "AD6438836DBE526AA231ABDE2D0EEF74D42";

    @Test
    void bundledListMatchesIgnoringCase() {
        BreachedPasswordValidator validator = new BreachedPasswordValidator(TestProperties.create());

        assertThat(validator.isBreached("1q2w3e4r5t6y7u8i9o0p")).isTrue();
        assertThat(validator.isBreached("1Q2W3E4R5T6Y7U8I9O0P")).isTrue();
        assertThat(validator.isBreached("password123456")).isTrue();
        assertThat(validator.isBreached("glacier-umbrella-tractor-71")).isFalse();
    }

    @Test
    void pwnedCheckIsOffByDefault() {
        // With the check disabled there is no client: reaching range() would throw.
        assertThat(new BreachedPasswordValidator(TestProperties.create()).isBreached("correct horse battery staple"))
                .isFalse();
    }

    @Test
    void pwnedHashWithACountIsBreached() {
        AtomicReference<String> asked = new AtomicReference<>();
        BreachedPasswordValidator validator = new BreachedPasswordValidator(pwnedEnabled()) {
            @Override
            String range(String prefix) {
                asked.set(prefix);
                return "0018A45C4D1DEF81644B54AB7F969B88D65:1\r\n" + SUFFIX + ":3645804\r\n";
            }
        };

        assertThat(validator.isBreached("correct horse battery staple")).isTrue();
        // Only the 5-character prefix leaves the server.
        assertThat(asked.get()).isEqualTo(PREFIX);
    }

    @Test
    void paddingEntriesAndOtherHashesAreNotBreached() {
        BreachedPasswordValidator validator = new BreachedPasswordValidator(pwnedEnabled()) {
            @Override
            String range(String prefix) {
                // Add-Padding entries have a count of 0.
                return SUFFIX + ":0\r\n0018A45C4D1DEF81644B54AB7F969B88D65:1\r\n";
            }
        };

        assertThat(validator.isBreached("correct horse battery staple")).isFalse();
    }

    @Test
    void emptyResponseIsNotBreached() {
        BreachedPasswordValidator validator = new BreachedPasswordValidator(pwnedEnabled()) {
            @Override
            String range(String prefix) {
                return null;
            }
        };

        assertThat(validator.isBreached("correct horse battery staple")).isFalse();
    }

    @Test
    void unreachableApiFallsBackToTheBundledList() {
        BreachedPasswordValidator validator = new BreachedPasswordValidator(pwnedEnabled()) {
            @Override
            String range(String prefix) {
                throw new ResourceAccessException("connect timed out");
            }
        };

        assertThat(validator.isBreached("correct horse battery staple")).isFalse();
        assertThat(validator.isBreached("1q2w3e4r5t6y7u8i9o0p")).isTrue();
    }

    private static AppProperties pwnedEnabled() {
        AppProperties properties = TestProperties.create();
        return new AppProperties(
                properties.jwt(),
                properties.cookie(),
                properties.cors(),
                properties.login(),
                properties.rateLimit(),
                new AppProperties.Account(false, Duration.ofHours(24), Duration.ofHours(1), true),
                properties.mail(),
                properties.admin(),
                properties.mfa(),
                properties.socialLogin());
    }
}
