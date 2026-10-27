package com.hikmethankolay.template.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Unit tests of {@link KnownDevices#describe}. */
class KnownDevicesTest {

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 "
                        + "Safari/537.36 | Chrome on Windows",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 "
                        + "Safari/537.36 Edg/130.0.0.0 | Edge on Windows",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 "
                        + "Safari/537.36 OPR/115.0.0.0 | Opera on Windows",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) "
                        + "Version/18.0 Safari/605.1.15 | Safari on macOS",
                "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) "
                        + "CriOS/130.0 Mobile/15E148 Safari/604.1 | Chrome on iOS",
                "Mozilla/5.0 (iPad; CPU OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) FxiOS/131.0 "
                        + "Mobile/15E148 Safari/605.1.15 | Firefox on iOS",
                "Mozilla/5.0 (X11; Linux x86_64; rv:131.0) Gecko/20100101 Firefox/131.0 | Firefox on Linux",
                "Mozilla/5.0 (Linux; Android 14; SM-S921B) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/26.0 "
                        + "Chrome/122.0.0.0 Mobile Safari/537.36 | Samsung Internet on Android",
                "Mozilla/5.0 (X11; CrOS x86_64 14541.0.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 "
                        + "Safari/537.36 | Chrome on ChromeOS",
                "okhttp/4.12.0 (Android 14) | Android",
                "MyApp/1.0 Firefox/1.0 | Firefox",
                "curl/8.10.1 | Unknown device"
            })
    void namesTheBrowserAndSystem(String userAgent, String device) {
        assertThat(KnownDevices.describe(userAgent)).isEqualTo(device);
    }

    @Test
    void missingUserAgentIsAnUnknownDevice() {
        assertThat(KnownDevices.describe(null)).isEqualTo(KnownDevices.UNKNOWN_DEVICE);
    }
}
