package com.hikmethankolay.template.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

/**
 * Type-safe view of the {@code app.*} properties defined in {@code application.yml}.
 *
 * <p>The properties are validated at startup, so a misconfigured environment
 * (for example a missing {@code JWT_SECRET}) fails fast with a clear message.</p>
 *
 * @param jwt    token signing and lifetime settings
 * @param cookie settings of the HttpOnly authentication cookies
 * @param cors   cross-origin settings for browser clients
 * @param login     brute-force protection settings
 * @param rateLimit per-IP request rate limit
 * @param account   email verification and password reset settings
 * @param mail      outgoing email settings
 * @param admin     optional initial administrator account
 * @param mfa         two-factor authentication settings
 * @param socialLogin optional login with GitHub and Google
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @Valid @NotNull Jwt jwt,
        @Valid @NotNull Cookie cookie,
        @Valid @NotNull Cors cors,
        @Valid @NotNull Login login,
        @Valid @NotNull RateLimit rateLimit,
        @Valid @NotNull Account account,
        @Valid @NotNull Mail mail,
        @Valid @NotNull Admin admin,
        @Valid @NotNull Mfa mfa,
        @Valid @NotNull SocialLogin socialLogin) {

    /**
     * JWT settings.
     *
     * <p>Each key setting takes a comma-separated list: the first key signs new tokens and every key
     * verifies them, so a key can be rotated without logging everyone out (see the Security guide).</p>
     *
     * @param algorithm       {@code HS256} (shared secret) or {@code RS256} (private key; other services verify
     *                        tokens with the public keys at {@code /.well-known/jwks.json})
     * @param secret          HS256 signing secrets, each at least 43 random characters (256 bits of entropy)
     * @param privateKey      RS256 private keys in PEM format (PKCS#8 or PKCS#1), each at least 2048 bits
     * @param issuer          value of the {@code iss} claim; tokens from other issuers are rejected
     * @param audience        value of the {@code aud} claim; tokens for other audiences are rejected
     * @param accessTokenTtl  lifetime of an access token (JWT); keep it short
     * @param refreshTokenTtl inactivity after which a session ends
     * @param rememberMeTtl   inactivity after which a session started with "remember me" ends
     * @param maxSessionTtl   time after the login when a session ends however often it is refreshed
     * @param rememberMeMaxSessionTtl the same for sessions started with "remember me"
     */
    public record Jwt(
            @NotNull Algorithm algorithm,
            // 43 Base64 characters carry 258 bits: an HS256 key needs 256 bits of entropy, not only of length.
            List<
                            @Size(
                                    min = 43,
                                    message =
                                            "must be at least 43 random characters, e.g. openssl rand -base64 48 (JWT_SECRET)")
                            String>
                    secret,
            List<String> privateKey,
            @NotBlank String issuer,
            @NotBlank String audience,
            @NotNull Duration accessTokenTtl,
            @NotNull Duration refreshTokenTtl,
            @NotNull Duration rememberMeTtl,
            @NotNull Duration maxSessionTtl,
            @NotNull Duration rememberMeMaxSessionTtl) {

        /**
         * Requires the keys of the selected algorithm.
         *
         * @return {@code true} if at least one key is set for the selected {@code algorithm}
         */
        @AssertTrue(message = "must be set: JWT_SECRET for HS256, JWT_PRIVATE_KEY for RS256")
        public boolean isKeyConfigured() {
            List<String> keys = algorithm == Algorithm.RS256 ? privateKey : secret;
            return keys != null && !keys.isEmpty();
        }

        /**
         * Rejects secrets made of a few repeated characters, such as {@code aaaa...} or {@code changeme...}.
         * shortcut: a heuristic that cannot measure randomness; for real assurance load a generated key from a secret store.
         *
         * @return {@code true} if every secret uses at least 10 different characters
         */
        @AssertTrue(message = "must be a random value, not repeated or placeholder text (JWT_SECRET)")
        public boolean isSecretVaried() {
            return secret == null
                    || secret.stream()
                            .allMatch(value -> value.chars().distinct().count() >= 10);
        }

        /** Token signing algorithm. */
        public enum Algorithm {
            /** HMAC-SHA256 with a shared secret: only this application can verify tokens. */
            HS256,
            /** RSA-SHA256: anyone with the public key can verify tokens, only this application can sign them. */
            RS256
        }
    }

    /**
     * Authentication cookie settings.
     *
     * @param name        name of the access token cookie, sent to every path
     * @param refreshName name of the refresh token cookie, sent only to {@code /api/v1/auth}
     * @param secure      whether the cookies are sent over HTTPS only; enable in production
     * @param sameSite    {@code SameSite} attribute; {@code Strict} protects against CSRF
     */
    public record Cookie(
            @NotBlank String name,
            @NotBlank String refreshName,
            boolean secure,
            @NotBlank String sameSite) {

        /**
         * Returns the name the access token cookie is sent under. With {@code secure}, the {@code __Host-} prefix
         * makes browsers accept it only over HTTPS, for this host alone and for path {@code /}, so a sibling
         * subdomain cannot set or shadow it.
         *
         * @return the cookie name
         */
        public String accessCookieName() {
            return secure ? "__Host-" + name : name;
        }

        /**
         * Returns the name the refresh token cookie is sent under. With {@code secure}, the {@code __Secure-} prefix
         * makes browsers accept it only over HTTPS ({@code __Host-} does not allow its {@code /api/v1/auth} path).
         *
         * @return the cookie name
         */
        public String refreshCookieName() {
            return secure ? "__Secure-" + refreshName : refreshName;
        }
    }

    /**
     * CORS settings.
     *
     * @param allowedOrigins origins allowed to call the API with credentials; never use {@code *}
     */
    public record Cors(List<String> allowedOrigins) {}

    /**
     * Brute-force protection settings.
     *
     * @param maxAttempts        failed logins after which an IP is blocked
     * @param accountMaxAttempts failed logins after which an account is blocked; higher than
     *                           {@code maxAttempts}, so locking out a user takes several IPs
     * @param lockDuration       how long a block lasts after the last failure
     */
    public record Login(
            @Positive int maxAttempts,
            @Positive int accountMaxAttempts,
            @NotNull Duration lockDuration) {}

    /**
     * Per-client-IP rate limit for all API requests (Actuator is exempt).
     *
     * @param enabled           whether requests are limited
     * @param requestsPerMinute requests allowed per IP in each one-minute window
     * @param routes            stricter limits per IP for single paths, counted on top of the global one
     */
    public record RateLimit(boolean enabled, @Positive int requestsPerMinute, List<@Valid Route> routes) {

        /**
         * Treats a missing route list as empty.
         *
         * @param enabled           whether requests are limited
         * @param requestsPerMinute requests allowed per IP in each one-minute window
         * @param routes            stricter limits per IP for single paths, may be {@code null}
         */
        public RateLimit {
            routes = routes == null ? List.of() : List.copyOf(routes);
        }

        /**
         * A limit for the paths matching a pattern.
         *
         * @param path     Spring path pattern, e.g. {@code /api/v1/auth/login/**} for the path and everything below
         * @param requests requests allowed per IP in each window
         * @param window   length of the window
         */
        public record Route(
                @NotBlank String path,
                @Positive int requests,
                @NotNull Duration window) {}
    }

    /**
     * Email verification and password reset settings.
     *
     * @param requireVerifiedEmail      whether an account whose address is unconfirmed (after an email change) is
     *                                  refused at login; sign-ups always need their link, whatever this says
     * @param verificationTokenTtl      how long an email verification link is valid
     * @param passwordResetTokenTtl     how long a password reset link is valid
     * @param pwnedCheckEnabled         whether new passwords are also checked against Have I Been Pwned;
     *                                  only the first 5 hex digits of the password's SHA-1 leave the server
     */
    public record Account(
            boolean requireVerifiedEmail,
            @NotNull Duration verificationTokenTtl,
            @NotNull Duration passwordResetTokenTtl,
            boolean pwnedCheckEnabled) {}

    /**
     * Outgoing email settings (the SMTP server itself is configured with {@code spring.mail.*}).
     *
     * @param from               sender address
     * @param linkBaseUrl        base URL of the frontend that handles the links in emails, e.g.
     *                           {@code https://app.example.com}; links go to {@code /verify-email?token=...}
     *                           and {@code /reset-password?token=...}
     * @param maxAttempts        sending attempts before an email is given up
     * @param retryDelay         wait after the first failed attempt; doubles after each further failure
     */
    public record Mail(
            @NotBlank String from,
            @NotBlank String linkBaseUrl,
            @Positive int maxAttempts,
            @NotNull Duration retryDelay) {}

    /**
     * Initial administrator, created on startup when all three fields are set and no admin exists yet.
     *
     * @param username admin username
     * @param email    admin email address
     * @param password admin password in plain text; change it after the first login
     */
    public record Admin(String username, String email, String password) {

        /**
         * Tells whether all admin fields are set.
         *
         * @return {@code true} if username, email and password are all non-blank
         */
        public boolean isConfigured() {
            return StringUtils.hasText(username) && StringUtils.hasText(email) && StringUtils.hasText(password);
        }
    }

    /**
     * Two-factor authentication (TOTP) settings.
     *
     * @param encryptionKey encrypts the TOTP secrets stored in the database; changing it makes every enrolled
     *                      authenticator useless until its user signs in with a recovery code and re-enrolls
     * @param challengeTtl  how long a user has to enter the code after the password
     */
    public record Mfa(
            @NotBlank @Size(min = 32, message = "must be at least 32 characters (MFA_ENCRYPTION_KEY)")
            String encryptionKey,

            @NotNull Duration challengeTtl) {

        /**
         * Rejects placeholder keys such as a repeated character.
         *
         * @return {@code false} if the key has fewer than 10 distinct characters
         */
        @AssertTrue(message = "must be a random value, not repeated or placeholder text (MFA_ENCRYPTION_KEY)")
        public boolean isEncryptionKeyVaried() {
            return encryptionKey == null || encryptionKey.chars().distinct().count() >= 10;
        }
    }

    /**
     * Login with an external provider. A provider is offered only when its client id and secret are set.
     *
     * @param redirectUri the frontend page the provider sends the user back to; must be registered at the provider
     * @param github      GitHub OAuth app credentials
     * @param google      Google OAuth client credentials
     */
    public record SocialLogin(
            String redirectUri,
            @NotNull Client github,
            @NotNull Client google) {

        /**
         * Tells whether the redirect URI is set when a provider is.
         *
         * @return {@code false} if a provider is configured without a redirect URI
         */
        @AssertTrue(message = "must be set when a provider is configured (OAUTH2_REDIRECT_URI)")
        public boolean isRedirectUriConfigured() {
            return StringUtils.hasText(redirectUri) || !(github.isConfigured() || google.isConfigured());
        }

        /**
         * Credentials registered at a provider.
         *
         * @param clientId     client id
         * @param clientSecret client secret
         */
        public record Client(String clientId, String clientSecret) {

            /**
             * Tells whether the provider is enabled.
             *
             * @return {@code true} if both the id and the secret are set
             */
            public boolean isConfigured() {
                return StringUtils.hasText(clientId) && StringUtils.hasText(clientSecret);
            }
        }
    }
}
