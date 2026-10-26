package com.hikmethankolay.template.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.hikmethankolay.template.TestProperties;
import com.hikmethankolay.template.config.AppProperties;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerExceptionResolver;

/** The API's own origin, as {@link CookieCsrfFilter} works it out for each scheme and port. */
class CookieCsrfFilterTest {

    @Test
    void theOwnOriginIsAllowedOnDefaultAndOtherPorts() throws Exception {
        assertThat(passes("http", 80, "http://api.example.com")).isTrue();
        assertThat(passes("https", 443, "https://api.example.com")).isTrue();
        assertThat(passes("http", 8080, "http://api.example.com:8080")).isTrue();
        assertThat(passes("https", 8443, "https://api.example.com:8443")).isTrue();
        // The same host on another scheme or port is another origin.
        assertThat(passes("https", 443, "http://api.example.com")).isFalse();
        assertThat(passes("http", 8080, "http://api.example.com")).isFalse();
    }

    @Test
    void withoutAllowedOriginsOnlyTheOwnOriginPasses() throws Exception {
        AppProperties p = TestProperties.create();
        AppProperties noOrigins = new AppProperties(
                p.jwt(),
                p.cookie(),
                new AppProperties.Cors(null),
                p.login(),
                p.rateLimit(),
                p.account(),
                p.mail(),
                p.admin(),
                p.mfa(),
                p.socialLogin());

        assertThat(passes(noOrigins, "http", 80, "http://localhost:3000")).isFalse();
        assertThat(passes(noOrigins, "http", 80, "http://api.example.com")).isTrue();
    }

    private static boolean passes(String scheme, int port, String origin) throws Exception {
        return passes(TestProperties.create(), scheme, port, origin);
    }

    private static boolean passes(AppProperties properties, String scheme, int port, String origin) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/notes");
        request.setScheme(scheme);
        request.setServerName("api.example.com");
        request.setServerPort(port);
        request.addHeader("Origin", origin);
        request.setCookies(new Cookie("auth_token", "token"));
        MockFilterChain chain = new MockFilterChain();

        new CookieCsrfFilter(mock(HandlerExceptionResolver.class), properties)
                .doFilter(request, new MockHttpServletResponse(), chain);

        return chain.getRequest() != null;
    }
}
