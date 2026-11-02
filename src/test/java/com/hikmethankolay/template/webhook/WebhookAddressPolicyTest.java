package com.hikmethankolay.template.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hikmethankolay.template.user.Role;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Endpoint URLs must not let users make the server call internal services (SSRF). */
class WebhookAddressPolicyTest {

    private final WebhookAddressPolicy production = policy(true, false);

    @ParameterizedTest
    @ValueSource(
            strings = {
                "127.0.0.1",
                "10.1.2.3",
                "172.16.0.1",
                "192.168.1.1",
                "169.254.169.254",
                "100.64.0.1",
                "100.127.255.254",
                "192.0.0.1",
                "198.18.0.1",
                "198.19.255.254",
                "240.0.0.1",
                "255.255.255.255",
                "64:ff9b::a9fe:a9fe",
                "64:ff9b:1::a00:1",
                "2002:a9fe:a9fe::1",
                "2001:0:4136:e378:8000:63bf:3fff:fdd2",
                "::a9fe:a9fe",
                "0.0.0.0",
                "224.0.0.1",
                "::1",
                "fc00::1",
                "fe80::1",
                "::ffff:10.0.0.1"
            })
    void internalAddressesAreRefused(String address) {
        assertThatThrownBy(() -> production.resolve(address))
                .isInstanceOf(UnknownHostException.class)
                .hasMessageContaining("non-public");
        assertThatThrownBy(() -> production.validate("https://" + bracketed(address) + "/hook"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void publicAddressesAreAllowed() throws Exception {
        assertThat(production.resolve("93.184.216.34")).containsExactly(InetAddress.ofLiteral("93.184.216.34"));
        assertThat(production.resolve("2606:4700::1111")).hasSize(1);
        assertThat(WebhookAddressPolicy.isPublic(InetAddress.ofLiteral("100.63.255.254")))
                .isTrue();
        // Neighbours of the refused ranges stay public.
        for (String neighbour : List.of(
                "192.0.1.1",
                "192.1.0.1",
                "198.17.0.1",
                "198.20.0.1",
                "223.255.255.254",
                "64:ff9b:2::1",
                "65:ff9b::1",
                "64:ff9a::1",
                "2001:1::1",
                "2003::1",
                "1::1")) {
            assertThat(WebhookAddressPolicy.isPublic(InetAddress.ofLiteral(neighbour)))
                    .as(neighbour)
                    .isTrue();
        }
        assertThat(WebhookAddressPolicy.isPublic(InetAddress.ofLiteral("100.128.0.1")))
                .isTrue();
        assertThat(production.validate(" https://93.184.216.34/a/../hook ")).isEqualTo("https://93.184.216.34/hook");
    }

    @Test
    void urlsMustBeHttpsWithoutCredentialsOrFragments() {
        assertThatThrownBy(() -> production.validate("http://93.184.216.34/hook"))
                .hasMessageContaining("https://");
        assertThatThrownBy(() -> production.validate("ftp://93.184.216.34/hook"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> production.validate("https://user:pass@93.184.216.34/hook"))
                .hasMessageContaining("credentials");
        assertThatThrownBy(() -> production.validate("https://93.184.216.34/hook#x"))
                .hasMessageContaining("fragment");
        assertThatThrownBy(() -> production.validate("not a url")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> production.validate("https://nonexistent.invalid/hook"))
                .hasMessageContaining("does not resolve");
    }

    @Test
    void developmentSettingsAllowHttpAndLocalReceivers() throws Exception {
        WebhookAddressPolicy development = policy(false, true);

        assertThatThrownBy(() -> development.validate("ftp://127.0.0.1/hook"))
                .hasMessage("The URL must start with http:// or https://.");
        assertThatThrownBy(() -> production.validate("ftp://93.184.216.34/hook"))
                .hasMessage("The URL must start with https://.");

        assertThat(development.validate("http://127.0.0.1:8080/hook")).isEqualTo("http://127.0.0.1:8080/hook");
        assertThat(development.resolve("[::1]")).hasSize(1);
    }

    private static String bracketed(String address) {
        return address.contains(":") ? "[" + address + "]" : address;
    }

    private static WebhookAddressPolicy policy(boolean requireHttps, boolean allowPrivate) {
        return new WebhookAddressPolicy(new WebhookProperties(
                true,
                List.of(),
                Role.ADMIN,
                requireHttps,
                allowPrivate,
                10,
                Duration.ofSeconds(5),
                Duration.ofSeconds(10),
                8,
                24,
                Duration.ofSeconds(5),
                Duration.ofDays(7)));
    }
}
