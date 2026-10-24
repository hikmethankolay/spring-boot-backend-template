package com.hikmethankolay.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hikmethankolay.template.common.TraceIdResponseFilter;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;

/** Tracing and metrics are disabled in tests by default; this test turns them on and checks both. */
@AutoConfigureTracing
@AutoConfigureMetrics
class ObservabilityIntegrationTest extends IntegrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void everyResponseCarriesTraceId() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(TraceIdResponseFilter.HEADER, matchesPattern("[0-9a-f]{32}")));
    }

    @Test
    void prometheusEndpointExposesHttpMetrics() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());

        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("http_server_requests_seconds_count")))
                .andExpect(content().string(containsString("http_server_requests_seconds_bucket")))
                .andExpect(content().string(containsString("application=\"spring-boot-template\"")));
    }

    /**
     * Every metric the alert rules in {@code ops/prometheus/alerts.yml} rely on is exposed.
     *
     * @throws Exception if a request fails
     */
    @Test
    void metricsUsedByTheAlertRulesAreExposed() throws Exception {
        mockMvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());

        // See ops/prometheus/alerts.yml: latency SLO bucket, log-based alerts, scheduled jobs (the outbox runs at
        // startup).
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(content().string(containsString("le=\"0.3\"")))
                .andExpect(content().string(containsString("logback_events_total{")));
        // The outbox's first scheduled run starts with the context and may not have finished yet.
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> mockMvc.perform(get("/actuator/prometheus"))
                        .andExpect(content().string(containsString("tasks_scheduled_execution_seconds_count{"))));
    }

    @Test
    void auditEventsAreCountedInPrometheus() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .with(fromIp("10.0.10.1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"identifier":"nobody-here","password":"Wrong#123"}"""))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(content().string(containsString("security_audit_events_total{")))
                .andExpect(content().string(containsString("event=\"auth.login_failed\"")));
    }

    @Test
    void metricsAreNotPushedOverOtlpByDefault() {
        // Prometheus scrapes the metrics; an OTLP push would fail every minute without a collector.
        assertThat(applicationContext.getBeansOfType(OtlpMeterRegistry.class)).isEmpty();
    }
}
