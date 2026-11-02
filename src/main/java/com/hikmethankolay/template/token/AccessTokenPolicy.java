package com.hikmethankolay.template.token;

import java.util.Set;
import java.util.function.Supplier;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthenticatedAuthorizationManager;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Request rule of the authenticated API: the caller must be authenticated, and a request made with a personal
 * access token must also stay within what tokens may do.
 *
 * <ul>
 *   <li>Account self-service under {@code /api/v1/users/me/**} (password, two-factor, sessions, linked accounts,
 *       email preferences, data export, the tokens themselves) needs an interactive login; only
 *       {@code GET /api/v1/users/me} is open to tokens, so a script can tell whose token it holds.</li>
 *   <li>A {@code READ} token may only send {@code GET}, {@code HEAD} and {@code OPTIONS}.</li>
 * </ul>
 *
 * <p>Role checks stay on the controllers ({@code @PreAuthorize}); the token's role is already capped by
 * {@code app.access-tokens.max-role}.</p>
 */
public final class AccessTokenPolicy implements AuthorizationManager<RequestAuthorizationContext> {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    /** The one account path open to access tokens, with {@code GET} only. */
    public static final String OWN_PROFILE_PATH = "/api/v1/users/me";

    private static final RequestMatcher OWN_PROFILE =
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, OWN_PROFILE_PATH);

    /** Paths that need an interactive login: no access token may call them. */
    public static final String ACCOUNT_SELF_SERVICE_PATHS = "/api/v1/users/me/**";

    private static final RequestMatcher ACCOUNT_SELF_SERVICE =
            PathPatternRequestMatcher.withDefaults().matcher(ACCOUNT_SELF_SERVICE_PATHS);

    private static final AuthorizationDecision GRANTED = new AuthorizationDecision(true);

    private static final AuthorizationDecision DENIED = new AuthorizationDecision(false);

    private final AuthorizationManager<RequestAuthorizationContext> authenticated =
            AuthenticatedAuthorizationManager.authenticated();

    /** Creates the policy. */
    public AccessTokenPolicy() {
        // Stateless.
    }

    /**
     * Decides on a request.
     *
     * @param authentication the caller
     * @param context        the request
     * @return granted for authenticated callers within the rules above
     */
    @Override
    public AuthorizationResult authorize(
            Supplier<? extends Authentication> authentication, RequestAuthorizationContext context) {
        AuthorizationResult result = authenticated.authorize(authentication, context);
        if (result == null || !result.isGranted()) {
            return DENIED;
        }
        if (!(authentication.get().getPrincipal() instanceof Jwt jwt) || !AccessTokenService.isAccessToken(jwt)) {
            return GRANTED;
        }
        var request = context.getRequest();
        if (ACCOUNT_SELF_SERVICE.matches(request) && !OWN_PROFILE.matches(request)) {
            return DENIED;
        }
        boolean readOnly = AccessTokenScope.READ.name().equals(jwt.getClaimAsString(AccessTokenService.SCOPE_CLAIM));
        return readOnly && !SAFE_METHODS.contains(request.getMethod()) ? DENIED : GRANTED;
    }
}
