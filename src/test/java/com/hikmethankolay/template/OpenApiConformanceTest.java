package com.hikmethankolay.template;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;

/** {@link OpenApiConformance} rejects responses the committed document does not describe. */
class OpenApiConformanceTest {

    @Test
    void aBodyMatchingTheReferencedSchemaPasses() throws IOException {
        MvcResult result = providers(200, """
                [{"provider":"github","authorizationUri":"https://github.com/login/oauth/authorize"}]""");

        assertThatCode(() -> OpenApiConformance.instance().match(result)).doesNotThrowAnyException();
    }

    @Test
    void aBodyMissingARequiredFieldOfTheReferencedSchemaFails() throws IOException {
        // The item schema is a $ref into components, so this also proves the reference is resolved.
        MvcResult result = providers(200, """
                [{"provider":"github"}]""");

        assertThatThrownBy(() -> OpenApiConformance.instance().match(result))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("does not match the documented schema");
    }

    @Test
    void anUndocumentedStatusFails() throws IOException {
        MvcResult result = providers(418, "[]");

        assertThatThrownBy(() -> OpenApiConformance.instance().match(result))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("status not documented");
    }

    private static MvcResult providers(int status, String body) throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        MvcResult result = mock(MvcResult.class);
        when(result.getRequest()).thenReturn(new MockHttpServletRequest("GET", "/api/v1/auth/oauth2/providers"));
        when(result.getResponse()).thenReturn(response);
        return result;
    }
}
