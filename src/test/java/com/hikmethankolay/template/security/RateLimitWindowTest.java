package com.hikmethankolay.template.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.hikmethankolay.template.security.RateLimitFilter.Window;
import org.junit.jupiter.api.Test;

/** Which limit {@link RateLimitFilter} reports when several apply. */
class RateLimitWindowTest {

    @Test
    void anExceededLimitWins() {
        Window global = new Window(10, 120, 30);
        Window route = new Window(6, 5, 3000);

        assertThat(RateLimitFilter.stricter(global, route)).isSameAs(route);
        assertThat(RateLimitFilter.stricter(route, global)).isSameAs(route);
    }

    @Test
    void whenBothAreExceededTheOneResettingLastWins() {
        Window minute = new Window(200, 120, 30);
        Window hour = new Window(6, 5, 3000);

        assertThat(RateLimitFilter.stricter(minute, hour)).isSameAs(hour);
        assertThat(RateLimitFilter.stricter(hour, minute)).isSameAs(hour);
    }

    @Test
    void otherwiseTheOneWithFewestRequestsLeftWins() {
        Window global = new Window(10, 120, 30);
        Window route = new Window(3, 5, 3000);

        assertThat(RateLimitFilter.stricter(global, route)).isSameAs(route);
        assertThat(route.remaining()).isEqualTo(2);
        assertThat(new Window(9, 5, 1).remaining()).isZero();
    }
}
