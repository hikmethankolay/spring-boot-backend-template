package com.hikmethankolay.template.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClientIpTest {

    @Test
    void ipv4AddressesAreTheirOwnKey() {
        assertThat(ClientIp.limitKey("203.0.113.7")).isEqualTo("203.0.113.7");
        assertThat(ClientIp.limitKey("::ffff:203.0.113.7")).isEqualTo("203.0.113.7");
    }

    @Test
    void ipv6AddressesShareTheKeyOfTheirSlash64() {
        String key = ClientIp.limitKey("2001:db8:1:2::1");

        assertThat(ClientIp.limitKey("2001:db8:1:2:ffff:ffff:ffff:ffff")).isEqualTo(key);
        assertThat(ClientIp.limitKey("2001:0db8:0001:0002:0:0:0:abcd")).isEqualTo(key);
        assertThat(ClientIp.limitKey("2001:db8:1:3::1")).isNotEqualTo(key);
    }

    @Test
    void valuesThatAreNotAnAddressAreKeptAsTheyAre() {
        assertThat(ClientIp.limitKey("unknown")).isEqualTo("unknown");
        assertThat(ClientIp.network("unknown")).isEqualTo("unknown");
    }

    @Test
    void onlyPublicAddressesTellClientsApart() {
        assertThat(ClientIp.isPublic("203.0.113.7")).isTrue();
        assertThat(ClientIp.isPublic("2001:db8:1:2::1")).isTrue();
        for (String shared : new String[] {
            "10.0.0.5",
            "172.16.4.1",
            "192.168.1.1",
            "127.0.0.1",
            "169.254.1.1",
            "0.0.0.0",
            "::1",
            "fe80::1",
            "fd00::1",
            "fc00::1",
            "unknown"
        }) {
            assertThat(ClientIp.isPublic(shared)).as(shared).isFalse();
        }
        assertThat(ClientIp.isPublic(null)).isFalse();
    }

    @Test
    void ipv4AddressesBelongToTheirSlash24() {
        assertThat(ClientIp.network("203.0.113.7")).isEqualTo("203.0.113.0/24");
        assertThat(ClientIp.network("203.0.113.250")).isEqualTo("203.0.113.0/24");
        assertThat(ClientIp.network("::ffff:203.0.113.7")).isEqualTo("203.0.113.0/24");
        assertThat(ClientIp.network("255.254.253.1")).isEqualTo("255.254.253.0/24");
    }

    @Test
    void ipv6AddressesBelongToTheirSlash64() {
        assertThat(ClientIp.network("2001:db8:1:2::1"))
                .isEqualTo(ClientIp.limitKey("2001:db8:1:2::1"))
                .isEqualTo(ClientIp.network("2001:db8:1:2:ffff::9"));
    }
}
