package com.hikmethankolay.template.common;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ClientAddressCheckTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private final ClientAddressCheck filter = new ClientAddressCheck(meters);

    @Test
    void countsRequestsFromNonPublicAddressesAndServesThem() throws Exception {
        MockFilterChain chain = send("/api/v1/users/me", "10.0.0.7");
        send("/api/v1/users/me", "203.0.113.9");
        send("/api/v1/users/me", "127.0.0.1");
        // Probes and Prometheus reach Actuator from inside the network.
        send("/actuator/health", "10.0.0.7");

        assertThat(chain.getRequest()).isNotNull();
        assertThat(meters.get("client.address.not.public").counter().count()).isEqualTo(2);
    }

    private MockFilterChain send(String path, String address) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRemoteAddr(address);
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return chain;
    }
}
