package com.hikmethankolay.template.config;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Stops the {@code prod} profile from starting with the development defaults of {@code application.yml}.
 *
 * <p>Those defaults work locally but break a real deployment quietly: browsers on the real domain are refused by
 * CORS, and every verification and password reset email links to {@code http://localhost:3000} or never leaves the
 * server, so nobody can finish signing up. The check names each variable to set and fails before the application
 * accepts requests.</p>
 */
@Component
@Profile("prod")
class ProductionSettingsCheck {

    /** Password of the local database in {@code application.yml} and {@code compose.yaml}. */
    static final String DEVELOPMENT_DB_PASSWORD = "app";

    /** SMTP port of the local Mailpit in {@code application.yml} and {@code compose.yaml}. */
    static final int MAILPIT_PORT = 1025;

    /**
     * Runs the check.
     *
     * @param properties application properties
     * @param database   the connection actually used, so a service connection is judged by its own credentials
     * @param mailHost   SMTP host emails are sent through
     * @param mailPort   SMTP port, or {@code null} for the default
     * @throws IllegalStateException if a development default is still set
     */
    ProductionSettingsCheck(
            AppProperties properties,
            JdbcConnectionDetails database,
            @Value("${spring.mail.host:}") String mailHost,
            @Value("${spring.mail.port:#{null}}") Integer mailPort) {
        List<String> problems = problems(properties, database.getPassword(), mailHost, mailPort);
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "The prod profile is running with development settings: " + String.join("; ", problems));
        }
    }

    /**
     * Lists the development defaults still set.
     *
     * @param properties application properties
     * @param dbPassword password of the database connection
     * @param mailHost   SMTP host
     * @param mailPort   SMTP port, or {@code null} for the default
     * @return one message per problem, naming the variable to set; empty when there is none
     */
    static List<String> problems(AppProperties properties, String dbPassword, String mailHost, Integer mailPort) {
        List<String> problems = new ArrayList<>();
        List<String> origins = properties.cors().allowedOrigins();
        if (origins != null && origins.stream().anyMatch(ProductionSettingsCheck::isLocal)) {
            problems.add("CORS_ALLOWED_ORIGINS must list your frontend's origin, not localhost (" + origins + ")");
        }
        String linkBaseUrl = properties.mail().linkBaseUrl();
        if (isLocal(linkBaseUrl)) {
            problems.add("MAIL_LINK_BASE_URL must be your frontend's URL, not localhost (" + linkBaseUrl + ")");
        }
        String unsubscribeBaseUrl = properties.mail().unsubscribeBaseUrl();
        if (isLocal(unsubscribeBaseUrl)) {
            problems.add("MAIL_UNSUBSCRIBE_BASE_URL must be this API's public URL, not localhost (" + unsubscribeBaseUrl
                    + ")");
        }
        AppProperties.SocialLogin social = properties.socialLogin();
        boolean socialLogin = social.github().isConfigured() || social.google().isConfigured();
        if (socialLogin && isLocal(social.redirectUri())) {
            problems.add("OAUTH2_REDIRECT_URI must be your frontend's callback page, not localhost ("
                    + social.redirectUri() + ")");
        }
        if (DEVELOPMENT_DB_PASSWORD.equals(dbPassword)) {
            problems.add("DB_PASSWORD must not be the development password");
        }
        // A local relay on another port is a legitimate setup; only the Mailpit default is refused.
        if (isLocalHost(mailHost) && mailPort != null && mailPort == MAILPIT_PORT) {
            problems.add("MAIL_HOST and MAIL_PORT must point to your SMTP server, not the local Mailpit (" + mailHost
                    + ":" + mailPort + ")");
        }
        String from = properties.mail().from();
        if (isPlaceholderAddress(from)) {
            problems.add("MAIL_FROM must be an address of your own domain (" + from + ")");
        }
        return problems;
    }

    /**
     * Tells whether an email address uses a domain reserved for documentation (RFC 2606).
     *
     * @param address an email address; {@code null} is not a placeholder
     * @return {@code true} for {@code example.com}, {@code example.net}, {@code example.org} and their subdomains
     */
    static boolean isPlaceholderAddress(String address) {
        if (address == null) {
            return false;
        }
        String domain = address.substring(address.lastIndexOf('@') + 1).strip().toLowerCase(Locale.ROOT);
        return List.of("example.com", "example.net", "example.org").stream()
                .anyMatch(reserved -> domain.equals(reserved) || domain.endsWith("." + reserved));
    }

    /**
     * Tells whether a URL points to this machine.
     *
     * @param url an origin or URL; {@code null} and unparseable values are not local
     * @return {@code true} for {@code localhost}, {@code *.localhost}, {@code 127.*}, {@code [::1]} and {@code 0.0.0.0}
     */
    static boolean isLocal(String url) {
        if (url == null) {
            return false;
        }
        String host;
        try {
            host = URI.create(url.strip()).getHost();
        } catch (IllegalArgumentException ex) {
            return false;
        }
        return isLocalHost(host);
    }

    /**
     * Tells whether a host name points to this machine.
     *
     * @param host a host name or IP address; {@code null} is not local
     * @return {@code true} for {@code localhost}, {@code *.localhost}, {@code 127.*}, {@code ::1} and {@code 0.0.0.0}
     */
    static boolean isLocalHost(String host) {
        if (host == null) {
            return false;
        }
        host = host.strip().toLowerCase(Locale.ROOT);
        return host.equals("localhost")
                || host.endsWith(".localhost")
                || host.startsWith("127.")
                || host.equals("[::1]")
                || host.equals("::1")
                || host.equals("0.0.0.0");
    }
}
