package com.hikmethankolay.template.security;

import com.hikmethankolay.template.config.AppProperties;
import com.hikmethankolay.template.config.OpenApiConfig;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.Arrays;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.PathContainer;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Describes authentication in the OpenAPI document the same way {@link SecurityConfig} enforces it.
 *
 * <ul>
 *   <li>Two alternative schemes: the {@code Authorization: Bearer} header and the access token cookie.</li>
 *   <li>Operations under {@link SecurityConfig#PUBLIC_PATHS} need no authentication; every other one
 *       requires either scheme and can answer 401.</li>
 *   <li>Operations guarded by {@code @PreAuthorize} can also answer 403.</li>
 *   <li>Refresh and logout document the refresh token cookie, which browsers send instead of a body.</li>
 * </ul>
 *
 * <p>Because the public paths come from {@code SecurityConfig}, the document cannot drift from the
 * actual security rules. Runs before {@code OpenApiConfig}'s customizer, so the 401 and 403 responses
 * also get the common response headers.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiDocsSecurityCustomizer implements OpenApiCustomizer, OperationCustomizer {

    /** Name of the bearer header scheme. */
    static final String BEARER_SCHEME = "bearerAuth";

    /** Name of the access token cookie scheme. */
    static final String COOKIE_SCHEME = "cookieAuth";

    private static final List<String> REFRESH_COOKIE_PATHS = List.of("/api/v1/auth/refresh", "/api/v1/auth/logout");

    private final String cookieName;

    private final String refreshCookieName;

    private final List<PathPattern> publicPaths = Arrays.stream(SecurityConfig.PUBLIC_PATHS)
            .map(PathPatternParser.defaultInstance::parse)
            .toList();

    /**
     * Creates the customizer.
     *
     * @param properties application properties holding the cookie names
     */
    public ApiDocsSecurityCustomizer(AppProperties properties) {
        this.cookieName = properties.cookie().accessCookieName();
        this.refreshCookieName = properties.cookie().refreshCookieName();
    }

    /**
     * Declares the security schemes and marks each operation public or protected.
     *
     * @param openApi the scanned document
     */
    @Override
    public void customise(OpenAPI openApi) {
        if (openApi.getComponents() == null) {
            openApi.setComponents(new Components());
        }
        openApi.getComponents()
                .addSecuritySchemes(
                        BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Access token from login or refresh"))
                .addSecuritySchemes(
                        COOKIE_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE)
                                .name(cookieName)
                                .description("HttpOnly cookie set by login and refresh, for browsers"));
        // Two requirement objects mean "either one".
        List<SecurityRequirement> authenticated = List.of(
                new SecurityRequirement().addList(BEARER_SCHEME), new SecurityRequirement().addList(COOKIE_SCHEME));

        openApi.getPaths()
                .forEach((path, item) -> item.readOperations().forEach(operation -> {
                    if (REFRESH_COOKIE_PATHS.contains(path)) {
                        operation.addParametersItem(new Parameter()
                                .in("cookie")
                                .name(refreshCookieName)
                                .required(false)
                                .description(
                                        "Refresh token cookie set by login and refresh; used when the body has no refreshToken")
                                .schema(new StringSchema()));
                    }
                    if (isPublic(path)) {
                        operation.setSecurity(List.of());
                    } else {
                        operation.setSecurity(authenticated);
                        operation
                                .getResponses()
                                .putIfAbsent("401", OpenApiConfig.responseRef(OpenApiConfig.UNAUTHORIZED));
                    }
                }));
    }

    /**
     * Adds a 403 response to operations restricted by {@code @PreAuthorize}.
     *
     * @param operation     the operation being built
     * @param handlerMethod the controller method behind it
     * @return the operation
     */
    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        if (AnnotatedElementUtils.hasAnnotation(handlerMethod.getMethod(), PreAuthorize.class)) {
            operation.getResponses().putIfAbsent("403", OpenApiConfig.responseRef(OpenApiConfig.FORBIDDEN));
        }
        return operation;
    }

    /**
     * Tells whether a documented path is reachable without authentication.
     *
     * @param path the path as written in the document, e.g. {@code /api/v1/auth/login}
     * @return {@code true} if it matches one of the public paths
     */
    private boolean isPublic(String path) {
        PathContainer container = PathContainer.parsePath(path);
        return publicPaths.stream().anyMatch(pattern -> pattern.matches(container));
    }
}
