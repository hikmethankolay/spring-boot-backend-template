package com.hikmethankolay.template;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.oneOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The generated OpenAPI document must describe the API as it behaves and as the API guide documents it.
 * A failure here means a controller change was not reflected in the document (or vice versa).
 */
class OpenApiContractIntegrationTest extends IntegrationTest {

    private static final String AUTH = "$.paths['/api/v1/auth/";
    private static final String USERS = "$.paths['/api/v1/users";

    private ResultActions spec() throws Exception {
        return mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    }

    @Test
    void statusCodesMatchTheRuntimeBehaviour() throws Exception {
        spec().andExpect(jsonPath(AUTH + "register'].post.responses['201'].headers.Location")
                        .exists())
                .andExpect(jsonPath(AUTH + "register'].post.responses['200']").doesNotExist())
                .andExpect(jsonPath(AUTH + "register'].post.responses['409']").exists())
                .andExpect(jsonPath(AUTH + "logout'].post.responses['204']").exists())
                .andExpect(jsonPath(AUTH + "logout'].post.responses['200']").doesNotExist())
                .andExpect(jsonPath(AUTH + "login'].post.responses['401']").exists())
                .andExpect(jsonPath(AUTH + "login'].post.responses['200'].headers.Set-Cookie")
                        .exists())
                .andExpect(jsonPath(USERS + "/me'].patch.responses['409']").exists())
                .andExpect(jsonPath(USERS + "/me'].patch.responses['200']").exists())
                .andExpect(jsonPath(USERS + "/{id}'].patch.responses['200']").exists())
                .andExpect(jsonPath(USERS + "/{id}'].get.responses['404']").exists());
    }

    @Test
    void errorsAreProblemDetails() throws Exception {
        spec().andExpect(jsonPath("$.components.schemas.Problem.properties", hasKey("errors")))
                .andExpect(jsonPath(
                        "$.components.schemas.Problem.properties.type.description",
                        containsString("urn:problem:username-taken")))
                // Shared error responses are declared once and referenced.
                .andExpect(jsonPath(AUTH + "register'].post.responses['400']['$ref']")
                        .value("#/components/responses/BadRequest"))
                .andExpect(jsonPath(USERS + "/me'].get.responses['401']['$ref']")
                        .value("#/components/responses/Unauthorized"))
                .andExpect(
                        jsonPath(USERS + "'].get.responses['403']['$ref']").value("#/components/responses/Forbidden"))
                .andExpect(jsonPath(USERS + "/me'].get.responses['429']['$ref']")
                        .value("#/components/responses/TooManyRequests"))
                .andExpect(jsonPath(USERS + "/me'].get.responses['500']['$ref']")
                        .value("#/components/responses/InternalServerError"))
                .andExpect(jsonPath("$.components.responses.*.content", everyItem(hasKey("application/problem+json"))))
                .andExpect(jsonPath("$.components.responses.TooManyRequests.headers", hasKey("Retry-After")))
                .andExpect(jsonPath("$.components.responses.Unauthorized.headers", hasKey("WWW-Authenticate")))
                .andExpect(jsonPath(AUTH + "login'].post.responses['401'].headers", hasKey("WWW-Authenticate")))
                .andExpect(jsonPath(USERS + "/me'].get.responses['200'].content", not(hasKey("*/*"))));
    }

    @Test
    void schemasAreCompleteForClientGenerators() throws Exception {
        spec().andExpect(jsonPath("$.tags[*].name", containsInAnyOrder("Authentication", "Account", "Users")))
                .andExpect(jsonPath(
                        "$.components.schemas.PageMetadata.required",
                        containsInAnyOrder("number", "size", "totalElements", "totalPages")))
                .andExpect(jsonPath(
                        "$.components.schemas.PagedModelUserResponse.required", containsInAnyOrder("content", "page")))
                .andExpect(jsonPath("$.components.schemas.RegisterRequest.properties.password.writeOnly")
                        .value(true))
                .andExpect(jsonPath("$.components.schemas.RegisterRequest.properties.password.format")
                        .value("password"))
                .andExpect(jsonPath(
                        "$.components.schemas.RegisterRequest.properties.password.description",
                        containsString("15-128 characters")))
                .andExpect(jsonPath("$.components.schemas.LoginRequest.properties.password.maxLength")
                        .value(128))
                .andExpect(jsonPath("$.components.schemas.UserResponse.properties.email.format")
                        .value("email"))
                .andExpect(
                        jsonPath("$.components.schemas.TokenResponse.properties.tokenType.enum", contains("Bearer")));
    }

    @Test
    void securityMatchesTheFilterChains() throws Exception {
        spec().andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme")
                        .value("bearer"))
                .andExpect(
                        jsonPath("$.components.securitySchemes.cookieAuth.in").value("cookie"))
                .andExpect(
                        jsonPath("$.components.securitySchemes.cookieAuth.name").value("auth_token"))
                .andExpect(jsonPath(AUTH + "login'].post.security", empty()))
                .andExpect(jsonPath(AUTH + "refresh'].post.security", empty()))
                .andExpect(jsonPath(
                        AUTH + "refresh'].post.parameters[?(@.in == 'cookie')].name", hasItem("refresh_token")))
                .andExpect(jsonPath(AUTH + "password-reset'].post.security", empty()))
                .andExpect(jsonPath(USERS + "/me'].get.security[*]", hasItem(hasKey("bearerAuth"))))
                .andExpect(jsonPath(USERS + "/me'].get.security[*]", hasItem(hasKey("cookieAuth"))))
                .andExpect(jsonPath(USERS + "/me'].get.responses", not(hasKey("403"))));
    }

    @Test
    void paginationUsesSeparateParametersWithALimit() throws Exception {
        spec().andExpect(jsonPath(USERS + "'].get.parameters[*].name", containsInAnyOrder("page", "size", "sort")))
                .andExpect(jsonPath(USERS + "'].get.parameters[?(@.name == 'size')].schema.maximum", hasItem(100)))
                .andExpect(jsonPath(
                        USERS + "'].get.parameters[?(@.name == 'sort')].description",
                        hasItem(containsString("Allowed properties: id, username, email"))))
                .andExpect(jsonPath(
                        USERS + "'].get.parameters[?(@.name == 'sort')].description",
                        everyItem(not(containsString("password")))))
                .andExpect(
                        jsonPath(
                                USERS + "'].get.parameters[?(@.name == 'sort')].schema.items.pattern",
                                hasItem(
                                        "^(id|username|email|role|emailVerified|createdAt|updatedAt)(,(asc|desc|ASC|DESC))?$")));
    }

    @Test
    void operationsHaveDescriptiveIds() throws Exception {
        spec().andExpect(jsonPath(
                        "$.paths.*.*.operationId", everyItem(not(oneOf("get", "list", "update", "delete", "me")))))
                .andExpect(jsonPath(USERS + "'].get.operationId").value("listUsers"))
                .andExpect(jsonPath(AUTH + "register'].post.operationId").value("registerUser"));
    }

    @Test
    void responsesDeclareHeadersAndRequiredFields() throws Exception {
        spec().andExpect(jsonPath(USERS + "/me'].get.responses['200'].headers", hasKey("X-Trace-Id")))
                .andExpect(jsonPath(USERS + "/me'].get.responses['200'].headers", hasKey("RateLimit-Remaining")))
                .andExpect(jsonPath("$.components.schemas.UserResponse.required", hasItem("id")))
                .andExpect(jsonPath("$.components.schemas.TokenResponse.required", hasItem("refreshToken")))
                .andExpect(jsonPath("$.components.schemas.TokenRequest.properties.token.minLength")
                        .value(1))
                .andExpect(jsonPath("$.servers[0].url").value("/"));
    }
}
