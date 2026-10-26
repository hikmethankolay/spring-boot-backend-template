package com.hikmethankolay.template.config;

import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Warns once when the app runs without the {@code prod} profile against a database that is not local: the
 * development defaults (API docs on, cookies without Secure, verified emails not required) are then active on what
 * may be a real deployment. {@link ProductionSettingsCheck} covers the {@code prod} profile itself.
 */
@Component
@Profile("!prod")
class DevelopmentSettingsWarning {

    private static final Logger log = LoggerFactory.getLogger(DevelopmentSettingsWarning.class);

    /**
     * Checks the database URL and warns if it points elsewhere.
     *
     * @param databaseUrl value of {@code spring.datasource.url}
     */
    DevelopmentSettingsWarning(@Value("${spring.datasource.url:}") String databaseUrl) {
        String host = remoteHost(databaseUrl);
        if (host != null) {
            log.warn(
                    "The prod profile is off, but the database is on {}: development settings are active. Set "
                            + "SPRING_PROFILES_ACTIVE=prod for anything but local development.",
                    host);
        }
    }

    /**
     * Finds the host of a JDBC URL that points to another machine.
     *
     * @param jdbcUrl e.g. {@code jdbc:postgresql://db.example.com:5432/app}
     * @return the host, or {@code null} if it is local: localhost, a loopback address, or a name without a dot (a
     *     compose or Kubernetes service in the same project)
     */
    static String remoteHost(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:")) {
            return null;
        }
        String host;
        try {
            host = URI.create(jdbcUrl.substring("jdbc:".length())).getHost();
        } catch (IllegalArgumentException ex) {
            return null;
        }
        return host == null || ProductionSettingsCheck.isLocalHost(host) || !host.contains(".") ? null : host;
    }
}
