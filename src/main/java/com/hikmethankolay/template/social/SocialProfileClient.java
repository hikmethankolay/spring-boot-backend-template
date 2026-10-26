package com.hikmethankolay.template.social;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Talks to the provider: exchanges the authorization code the frontend received for an access token, then
 * reads who the user is. Calls time out after a few seconds, so a slow provider cannot tie up request threads.
 */
@Component
public class SocialProfileClient {

    private static final Logger log = LoggerFactory.getLogger(SocialProfileClient.class);

    // The state was generated and checked by the frontend; the token request does not carry it.
    private static final String UNUSED_STATE = "checked-by-client";

    private final RestClientAuthorizationCodeTokenResponseClient tokens =
            new RestClientAuthorizationCodeTokenResponseClient();

    private final RestClient json;

    /** Creates the client with 5-second connect and 10-second read timeouts. */
    public SocialProfileClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        // Spring Security's default token client setup, plus the timeouts.
        tokens.setRestClient(RestClient.builder()
                .requestFactory(requestFactory)
                .messageConverters(converters -> {
                    converters.clear();
                    converters.add(new FormHttpMessageConverter());
                    converters.add(new OAuth2AccessTokenResponseHttpMessageConverter());
                })
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build());
        this.json = RestClient.builder().requestFactory(requestFactory).build();
    }

    /**
     * Exchanges an authorization code and reads the user's profile.
     *
     * @param registration the provider
     * @param code         the authorization code from the redirect
     * @param codeVerifier the PKCE verifier the frontend generated
     * @return the profile
     * @throws BadCredentialsException if the provider rejects the code or cannot be reached (mapped to 401)
     */
    public SocialProfile fetch(ClientRegistration registration, String code, String codeVerifier) {
        try {
            String accessToken = exchange(registration, code, codeVerifier);
            String userInfoUri =
                    registration.getProviderDetails().getUserInfoEndpoint().getUri();
            Map<String, Object> user = json.get()
                    .uri(userInfoUri)
                    .headers(headers -> headers.setBearerAuth(accessToken))
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (user == null) {
                throw new BadCredentialsException("The provider returned no profile.");
            }
            String subject = String.valueOf(user.get(
                    registration.getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName()));
            return "github".equals(registration.getRegistrationId())
                    ? githubProfile(subject, user, userInfoUri + "/emails", accessToken)
                    : new SocialProfile(
                            registration.getRegistrationId(),
                            subject,
                            user.get("email") instanceof String email ? email : null,
                            Boolean.TRUE.equals(user.get("email_verified")),
                            null);
        } catch (OAuth2AuthorizationException | RestClientException ex) {
            // The code itself is not logged: it is a credential until it expires. The provider's error text has
            // its line breaks removed, so it cannot forge log lines.
            log.warn(
                    "Login with {} failed: {}",
                    registration.getRegistrationId(),
                    String.valueOf(ex.getMessage()).replaceAll("[\\r\\n]", " "));
            throw new BadCredentialsException("The provider rejected the login.", ex);
        }
    }

    private String exchange(ClientRegistration registration, String code, String codeVerifier) {
        OAuth2AuthorizationRequest request = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri(registration.getProviderDetails().getAuthorizationUri())
                .clientId(registration.getClientId())
                .redirectUri(registration.getRedirectUri())
                .scopes(registration.getScopes())
                .state(UNUSED_STATE)
                .attributes(attributes -> attributes.put(PkceParameterNames.CODE_VERIFIER, codeVerifier))
                .build();
        OAuth2AuthorizationResponse response = OAuth2AuthorizationResponse.success(code)
                .redirectUri(registration.getRedirectUri())
                .state(UNUSED_STATE)
                .build();
        return tokens.getTokenResponse(new OAuth2AuthorizationCodeGrantRequest(
                        registration, new OAuth2AuthorizationExchange(request, response)))
                .getAccessToken()
                .getTokenValue();
    }

    /** GitHub's profile only has the public email, if any; the verified primary one is in /user/emails. */
    private SocialProfile githubProfile(
            String subject, Map<String, Object> user, String emailsUri, String accessToken) {
        List<GithubEmail> emails = json.get()
                .uri(emailsUri)
                .headers(headers -> headers.setBearerAuth(accessToken))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        GithubEmail primary = emails == null
                ? null
                : emails.stream().filter(GithubEmail::primary).findFirst().orElse(null);
        return new SocialProfile(
                "github",
                subject,
                primary != null ? primary.email() : null,
                primary != null && primary.verified(),
                user.get("login") instanceof String login ? login : null);
    }

    /**
     * One entry of GitHub's {@code /user/emails} response.
     *
     * @param email    the address
     * @param primary  whether it is the account's primary address
     * @param verified whether GitHub verified it
     */
    record GithubEmail(String email, boolean primary, boolean verified) {}
}
