package com.hikmethankolay.template.security;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Counts the times a Redis-backed protection let something through because Redis did not answer (ADR 2), as
 * {@code security_fail_open_total{control}} in Prometheus. The {@code SecurityControlFailingOpen} alert fires on it.
 */
public final class FailOpen {

    private FailOpen() {}

    /**
     * Registers the counter of one protection. Registering it up front publishes it at zero, so the alert's
     * {@code increase()} also sees the first failure.
     *
     * @param registry the meter registry
     * @param control  the protection, e.g. {@code rate-limit}
     * @return the counter to increment at each fail-open
     */
    public static Counter counter(MeterRegistry registry, String control) {
        return Counter.builder("security.fail.open")
                .description("Requests a security control let through because Redis was unavailable")
                .tag("control", control)
                .register(registry);
    }
}
