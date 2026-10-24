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
    }
}
