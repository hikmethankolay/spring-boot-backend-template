package com.hikmethankolay.template.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.hikmethankolay.template.TestProperties;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.method.HandlerMethod;

/** Called directly: springdoc caches the generated document, so integration tests would run it only once. */
class ApiDocsSecurityCustomizerTest {

    private final ApiDocsSecurityCustomizer customizer = new ApiDocsSecurityCustomizer(TestProperties.create());

    /** Public operations need no token; the others accept a bearer token or the cookie and document 401. */
    @Test
    void publicOperationsNeedNoTokenAndOthersNeedBearerOrCookie() {
        Operation login = operation();
        Operation me = operation();
        OpenAPI openApi = new OpenAPI()
                .paths(new Paths()
                        .addPathItem("/api/v1/auth/login", new PathItem().post(login))
                        .addPathItem("/api/v1/users/me", new PathItem().get(me)));

        customizer.customise(openApi);

        assertThat(openApi.getComponents().getSecuritySchemes()).containsKeys("bearerAuth", "cookieAuth");
        assertThat(login.getSecurity()).isEmpty();
        assertThat(login.getResponses()).doesNotContainKey("401");
        assertThat(me.getSecurity()).hasSize(2);
        assertThat(me.getResponses()).containsKey("401");
    }

    /** Components already in the document are reused, not replaced. */
    @Test
    void existingComponentsAreKept() {
        Components components = new Components();
        OpenAPI openApi = new OpenAPI().components(components).paths(new Paths());

        customizer.customise(openApi);

        assertThat(openApi.getComponents()).isSameAs(components);
    }

    /** Only signed-in POSTs the filter covers document Idempotency-Key and its errors; two-factor enrollment does not. */
    @Test
    void signedInPostsDocumentTheIdempotencyKey() {
        Operation createNote = operation();
        Operation listNotes = operation();
        Operation enroll = operation();
        Operation login = operation();
        OpenAPI openApi = new OpenAPI()
                .paths(new Paths()
                        .addPathItem(
                                "/api/v1/notes", new PathItem().post(createNote).get(listNotes))
                        .addPathItem("/api/v1/users/me/mfa/totp", new PathItem().post(enroll))
                        .addPathItem("/api/v1/auth/login", new PathItem().post(login)));

        customizer.customise(openApi);

        assertThat(createNote.getParameters())
                .singleElement()
                .extracting(Parameter::getIn, Parameter::getName)
                .containsExactly("header", "Idempotency-Key");
        assertThat(createNote.getResponses()).containsKeys("409", "422");
        for (Operation uncovered : List.of(listNotes, enroll, login)) {
            assertThat(uncovered.getParameters()).isNull();
            assertThat(uncovered.getResponses()).doesNotContainKey("422");
        }
    }

    /** Only refresh and logout document the refresh token cookie. */
    @Test
    void refreshAndLogoutDocumentTheRefreshCookie() {
        Operation refresh = operation();
        Operation login = operation();
        OpenAPI openApi = new OpenAPI()
                .paths(new Paths()
                        .addPathItem("/api/v1/auth/refresh", new PathItem().post(refresh))
                        .addPathItem("/api/v1/auth/login", new PathItem().post(login)));

        customizer.customise(openApi);

        assertThat(refresh.getParameters())
                .singleElement()
                .extracting(Parameter::getIn, Parameter::getName)
                .containsExactly("cookie", "refresh_token");
        assertThat(login.getParameters()).isNull();
    }

    /**
     * Operations restricted by {@code @PreAuthorize} document 403.
     *
     * @throws Exception if the handler method cannot be found
     */
    @Test
    void roleRestrictedOperationsDocument403() throws Exception {
        Operation restricted = operation();
        Operation open = operation();

        assertThat(customizer.customize(restricted, handler("restricted"))).isSameAs(restricted);
        customizer.customize(open, handler("open"));

        assertThat(restricted.getResponses()).containsKey("403");
        assertThat(open.getResponses()).doesNotContainKey("403");
    }

    /**
     * Creates an operation with an empty response map.
     *
     * @return the operation
     */
    private static Operation operation() {
        return new Operation().responses(new ApiResponses());
    }

    /**
     * Returns a handler method of {@link Endpoints}.
     *
     * @param name the method name
     * @return the handler method
     * @throws NoSuchMethodException if there is no such method
     */
    private static HandlerMethod handler(String name) throws NoSuchMethodException {
        return new HandlerMethod(new Endpoints(), Endpoints.class.getDeclaredMethod(name));
    }

    /** Controller methods to build handler methods from. */
    static class Endpoints {
        /** An endpoint restricted to administrators. */
        @PreAuthorize("hasRole('ADMIN')")
        void restricted() {}

        /** An endpoint without a role check. */
        void open() {}
    }
}
