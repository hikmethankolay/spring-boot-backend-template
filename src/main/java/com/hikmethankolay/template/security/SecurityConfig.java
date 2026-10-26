package com.hikmethankolay.template.security;

import com.hikmethankolay.template.config.AppProperties;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsPasswordService;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtTypeValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless JWT security configuration.
 *
 * <ul>
 *   <li>Public endpoints get their own filter chain without token processing, so a stale
 *       cookie can never block login or registration.</li>
 *   <li>Every other request needs a valid JWT, sent as a Bearer header or as the auth cookie.</li>
 *   <li>Role checks are declared on controller methods with {@code @PreAuthorize}; {@code OWNER} implies
 *       {@code ADMIN}, which implies {@code USER} ({@link #roleHierarchy()}).</li>
 * </ul>
 *
 * <p>Every response carries a strict {@code Content-Security-Policy}, {@code Referrer-Policy: no-referrer}
 * and a {@code Permissions-Policy}, on top of Spring Security's defaults ({@code X-Content-Type-Options},
 * {@code X-Frame-Options}, {@code Cache-Control: no-store}, and HSTS on HTTPS requests).</p>
 *
 * <p>CSRF protection is disabled because the API is stateless and the auth cookie defaults to
 * {@code SameSite=Strict}, so browsers never attach it to cross-site requests. Revisit this if
 * {@code app.cookie.same-site} is relaxed to {@code Lax} or {@code None}.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfig {

    /** Paths reachable without authentication. Add new public endpoints here. */
    static final String[] PUBLIC_PATHS = {
        "/api/v1/auth/register",
        "/api/v1/auth/register/complete",
        "/api/v1/auth/login",
        "/api/v1/auth/login/mfa",
        "/api/v1/auth/oauth2/*",
        "/api/v1/auth/refresh",
        "/api/v1/auth/logout",
        "/api/v1/auth/verify-email",
        "/api/v1/auth/verify-email/resend",
        "/api/v1/auth/password-reset",
        "/api/v1/auth/password-reset/confirm",
        "/.well-known/jwks.json",
        "/actuator/health",
        "/actuator/health/**",
        "/actuator/info",
        "/actuator/prometheus",
        "/v3/api-docs",
        "/v3/api-docs/**",
        "/swagger-ui.html",
        "/swagger-ui/**",
        "/error"
    };

    /**
     * {@code Content-Security-Policy} for every response. API responses are JSON and never load
     * anything; the relaxed sources only exist for the Swagger UI pages, which use their own
     * scripts, inline styles and data-URI images.
     */
    private static final String CONTENT_SECURITY_POLICY = "default-src 'none'; script-src 'self'; "
            + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; "
            + "frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

    private static final String PERMISSIONS_POLICY = "camera=(), microphone=(), geolocation=(), payment=(), usb=()";

    /** Creates the configuration; Spring instantiates it. */
    public SecurityConfig() {
        // Nothing to initialise.
    }

    /**
     * Filter chain for {@code PUBLIC_PATHS}: everything is permitted and no token is read.
     *
     * @param http           the builder provided by Spring Security
     * @param problemHandler renders security errors as problem details
     * @return the public filter chain, evaluated first
     * @throws Exception if the chain cannot be built
     */
    @Bean
    @Order(1)
    SecurityFilterChain publicFilterChain(HttpSecurity http, SecurityProblemHandler problemHandler) throws Exception {
        return applyCommonSettings(http.securityMatcher(PUBLIC_PATHS), problemHandler)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    /**
     * Filter chain for all other requests: a valid JWT is required (deny by default).
     *
     * @param http                the builder provided by Spring Security
     * @param problemHandler      renders security errors as problem details
     * @param bearerTokenResolver reads the token from the header or the auth cookie
     * @return the authenticated filter chain
     * @throws Exception if the chain cannot be built
     */
    @Bean
    @Order(2)
    SecurityFilterChain apiFilterChain(
            HttpSecurity http, SecurityProblemHandler problemHandler, BearerTokenResolver bearerTokenResolver)
            throws Exception {
        return applyCommonSettings(http, problemHandler)
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .bearerTokenResolver(bearerTokenResolver)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(problemHandler)
                        .accessDeniedHandler(problemHandler))
                .build();
    }

    /**
     * Settings shared by both chains: stateless, no CSRF, no form/basic login, CORS enabled.
     *
     * @param http           the builder to configure
     * @param problemHandler renders security errors as problem details
     * @return the same builder, for chaining
     */
    private static HttpSecurity applyCommonSettings(HttpSecurity http, SecurityProblemHandler problemHandler) {
        return http.csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
                        .permissionsPolicyHeader(permissions -> permissions.policy(PERMISSIONS_POLICY)))
                .exceptionHandling(exceptions ->
                        exceptions.authenticationEntryPoint(problemHandler).accessDeniedHandler(problemHandler));
    }

    /**
     * Maps the {@code roles} claim to Spring Security authorities ({@code ADMIN} becomes {@code ROLE_ADMIN}).
     *
     * @return the converter used by the resource server
     */
    static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(TokenService.ROLES_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    /**
     * Role hierarchy for {@code @PreAuthorize}: the owner has every administrator right, and an
     * administrator every user right. Owner-only rules are checked in {@code UserService}.
     *
     * @return the hierarchy picked up by method security
     */
    @Bean
    static RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.withDefaultRolePrefix()
                .role("OWNER")
                .implies("ADMIN")
                .role("ADMIN")
                .implies("USER")
                .build();
    }

    /**
     * Password hashing with Argon2id at the OWASP minimum (19 MiB of memory, 2 iterations, 1 lane). The
     * delegating encoder stores the algorithm id with each hash ({@code {argon2}...}); hashes from
     * earlier versions ({@code {bcrypt}...}) still match and are rehashed at the next login.
     *
     * @return the password encoder
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        // Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8() uses 16 MiB, below the OWASP minimum.
        return new DelegatingPasswordEncoder(
                "argon2",
                Map.of(
                        "argon2", new Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2),
                        "bcrypt", new BCryptPasswordEncoder()));
    }

    /**
     * Username/password authentication used by the login endpoint.
     *
     * @param userDetailsService loads users by username or email
     * @param passwordEncoder    verifies password hashes
     * @param passwordUpgrade    stores the new hash when a login used an outdated algorithm or setting
     * @return the authentication manager
     */
    @Bean
    AuthenticationManager authenticationManager(
            UserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder,
            UserDetailsPasswordService passwordUpgrade) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        provider.setUserDetailsPasswordService(passwordUpgrade);
        return new ProviderManager(provider);
    }

    /**
     * The signing and verification keys from {@code app.jwt}.
     *
     * @param properties application properties holding the algorithm and keys
     * @return the keys
     */
    @Bean
    JwtKeys jwtKeys(AppProperties properties) {
        return new JwtKeys(properties);
    }

    /**
     * Signs tokens with the first configured key.
     *
     * @param keys the configured keys
     * @return the JWT encoder used by {@link TokenService}
     */
    @Bean
    JwtEncoder jwtEncoder(JwtKeys keys) {
        return keys.encoder();
    }

    /**
     * Reads the token from the {@code Authorization} header or the auth cookie.
     *
     * @param properties application properties holding the cookie name
     * @return the resolver used by the resource server and by logout
     */
    @Bean
    BearerTokenResolver bearerTokenResolver(AppProperties properties) {
        return new CookieBearerTokenResolver(properties.cookie().accessCookieName());
    }

    /**
     * Verifies signature (against any configured key), expiry, issuer, audience, the {@code at+jwt} type and
     * revocation of incoming tokens.
     *
     * @param keys        the configured keys
     * @param properties  application properties holding the issuer
     * @param revocations denylist of revoked token ids
     * @return the JWT decoder used by the resource server
     */
    @Bean
    JwtDecoder jwtDecoder(JwtKeys keys, AppProperties properties, TokenRevocationService revocations) {
        NimbusJwtDecoder decoder = keys.decoder();
        OAuth2TokenValidator<Jwt> notRevoked = jwt -> revocations.isRevoked(jwt)
                ? OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "The token has been revoked.", null))
                : OAuth2TokenValidatorResult.success();
        String audience = properties.jwt().audience();
        // Listed one by one: JwtValidators.createDefault* adds a JwtTypeValidator that accepts only "JWT" or no typ,
        // which would reject every access token (typ "at+jwt", RFC 9068).
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(),
                new JwtIssuerValidator(properties.jwt().issuer()),
                new JwtTypeValidator(TokenService.ACCESS_TOKEN_TYPE, "application/" + TokenService.ACCESS_TOKEN_TYPE),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD, aud -> aud != null && aud.contains(audience)),
                notRevoked));
        return decoder;
    }

    /**
     * CORS policy for browser clients, restricted to {@code app.cors.allowed-origins}.
     *
     * @param properties application properties holding the allowed origins
     * @return the CORS configuration picked up by {@code http.cors()}
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(AppProperties properties) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(properties.cors().allowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        // Every header the OpenAPI document declares on responses, so cross-origin clients can read them.
        cors.setExposedHeaders(List.of(
                "Location",
                "Retry-After",
                "X-Trace-Id",
                "RateLimit-Limit",
                "RateLimit-Remaining",
                "RateLimit-Reset",
                HttpHeaders.CONTENT_DISPOSITION));
        cors.setAllowCredentials(true);
        cors.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }
}
