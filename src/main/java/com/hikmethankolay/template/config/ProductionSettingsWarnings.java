package com.hikmethankolay.template.config;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Warns once at startup when the {@code prod} profile runs with an unencrypted connection or with a protection turned
 * off. Unlike {@link ProductionSettingsCheck} it never stops the app: each of these can be deliberate (a database on a
 * private network, Flyway run by the app on purpose), so existing deployments keep starting, but the choice shows up
 * in the log.
 */
@Component
@Profile("prod")
class ProductionSettingsWarnings {

    private static final Logger log = LoggerFactory.getLogger(ProductionSettingsWarnings.class);

    private static final Pattern VERIFYING_SSL_MODE =
            Pattern.compile("[?&]sslmode=verify-(full|ca)(&|$)", Pattern.CASE_INSENSITIVE);

    /**
     * Logs one warning per weakened setting.
     *
     * @param properties       application properties
     * @param database         the database connection actually used
     * @param redisHost        {@code spring.data.redis.host}
     * @param redisPassword    {@code spring.data.redis.password}
     * @param redisSsl         {@code spring.data.redis.ssl.enabled}
     * @param apiDocs          {@code springdoc.api-docs.enabled}
     * @param swaggerUi        {@code springdoc.swagger-ui.enabled}
     * @param startTlsRequired {@code spring.mail.properties[mail.smtp.starttls.required]}
     * @param flyway           {@code spring.flyway.enabled}
     * @param webhooksPrivate  {@code app.webhooks.allow-private-addresses}
     * @param webhooksHttps    {@code app.webhooks.require-https}
     */
    ProductionSettingsWarnings(
            AppProperties properties,
            JdbcConnectionDetails database,
            @Value("${spring.data.redis.host:localhost}") String redisHost,
            @Value("${spring.data.redis.password:}") String redisPassword,
            @Value("${spring.data.redis.ssl.enabled:false}") boolean redisSsl,
            @Value("${springdoc.api-docs.enabled:true}") boolean apiDocs,
            @Value("${springdoc.swagger-ui.enabled:true}") boolean swaggerUi,
            @Value("${spring.mail.properties[mail.smtp.starttls.required]:false}") boolean startTlsRequired,
            @Value("${spring.flyway.enabled:true}") boolean flyway,
            @Value("${app.webhooks.allow-private-addresses:false}") boolean webhooksPrivate,
            @Value("${app.webhooks.require-https:true}") boolean webhooksHttps) {
        webhookWarnings(webhooksPrivate, webhooksHttps).forEach(warning -> log.warn("Production setting: {}", warning));
        // shortcut: reads the host/password/ssl properties only; a spring.data.redis.url, Sentinel or Cluster setup is
        // judged by them too. Read DataRedisConnectionDetails if those become common.
        for (String warning : warnings(
                properties,
                database.getJdbcUrl(),
                redisHost,
                redisPassword,
                redisSsl,
                apiDocs || swaggerUi,
                startTlsRequired,
                flyway)) {
            log.warn("Production setting: {}", warning);
        }
    }

    /**
     * Lists the weakened settings.
     *
     * @param properties       application properties
     * @param jdbcUrl          JDBC URL of the database
     * @param redisHost        Redis host
     * @param redisPassword    Redis password, empty or {@code null} for none
     * @param redisSsl         whether Redis is reached over TLS
     * @param apiDocs          whether Swagger UI or the OpenAPI document is served
     * @param startTlsRequired whether SMTP refuses to send without STARTTLS
     * @param flyway           whether the app runs the migrations itself
     * @return one message per weakened setting, naming the variable to change; empty when there is none
     */
    static List<String> warnings(
            AppProperties properties,
            String jdbcUrl,
            String redisHost,
            String redisPassword,
            boolean redisSsl,
            boolean apiDocs,
            boolean startTlsRequired,
            boolean flyway) {
        List<String> warnings = new ArrayList<>();
        if (jdbcUrl != null
                && !isLocalDatabase(jdbcUrl)
                && !VERIFYING_SSL_MODE.matcher(jdbcUrl).find()) {
            warnings.add("DB_URL reaches a remote database without sslmode=verify-full (or verify-ca); the driver's"
                    + " default does not check the server certificate and may fall back to plain text");
        }
        if (redisPassword == null || redisPassword.isEmpty()) {
            warnings.add("REDIS_PASSWORD is empty: anyone who can reach Redis can read and change the lockout,"
                    + " rate limit and token revocation state");
        }
        if (!redisSsl && !ProductionSettingsCheck.isLocalHost(redisHost)) {
            warnings.add("REDIS_SSL_ENABLED is false for the remote Redis " + redisHost
                    + ": its password and data cross the network in plain text");
        }
        if (!properties.cookie().secure()) {
            warnings.add("COOKIE_SECURE is false: authentication cookies are also sent over plain HTTP");
        }
        if (apiDocs) {
            warnings.add("SWAGGER_ENABLED is true: the API documentation is public");
        }
        if (!properties.rateLimit().enabled()) {
            warnings.add("RATE_LIMIT_ENABLED is false: no per-IP request limit");
        }
        if (!properties.account().requireVerifiedEmail()) {
            warnings.add("REQUIRE_VERIFIED_EMAIL is false: accounts whose new email address is unconfirmed can log in");
        }
        if (!startTlsRequired) {
            warnings.add("MAIL_STARTTLS_REQUIRED is false: emails and the SMTP password may be sent unencrypted"
                    + " (fine with implicit TLS on port 465)");
        }
        if (flyway) {
            warnings.add("FLYWAY_ENABLED is true: the app's database role owns the schema, so a compromised app could"
                    + " drop tables or the trigger that keeps audit_events append-only; migrate with a separate role");
        }
        return warnings;
    }

    /**
     * Lists the weakened webhook settings.
     *
     * @param allowPrivateAddresses whether webhooks may be delivered to internal addresses
     * @param requireHttps          whether endpoint URLs must use HTTPS
     * @return one message per weakened setting; empty when there is none
     */
    static List<String> webhookWarnings(boolean allowPrivateAddresses, boolean requireHttps) {
        List<String> warnings = new ArrayList<>();
        if (allowPrivateAddresses) {
            warnings.add("WEBHOOKS_ALLOW_PRIVATE_ADDRESSES is true: users can make the server send requests to"
                    + " internal services and cloud metadata (SSRF)");
        }
        if (!requireHttps) {
            warnings.add("WEBHOOKS_REQUIRE_HTTPS is false: webhook payloads and signatures may cross the network in"
                    + " plain text");
        }
        return warnings;
    }

    /**
     * Tells whether every host of a JDBC URL is this machine.
     *
     * @param jdbcUrl e.g. {@code jdbc:postgresql://db1:5432,db2:5432/app?sslmode=verify-full}
     * @return {@code true} for loopback hosts, an empty host (the driver's localhost) and URLs without a host part
     */
    static boolean isLocalDatabase(String jdbcUrl) {
        int start = jdbcUrl.indexOf("//");
        if (start < 0) {
            return true;
        }
        String authority = jdbcUrl.substring(start + 2).split("[/?]", 2)[0];
        if (authority.isEmpty()) {
            return true;
        }
        for (String hostAndPort : authority.split(",")) {
            String host = hostAndPort.startsWith("[")
                    ? hostAndPort.substring(0, hostAndPort.indexOf(']') + 1)
                    : hostAndPort.split(":")[0];
            if (!ProductionSettingsCheck.isLocalHost(host)) {
                return false;
            }
        }
        return true;
    }
}
