package com.hikmethankolay.template;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Managed databases often give the schema owner no right to create extensions. Every migration must still apply
 * there: V22 then skips the trigram indexes for the user search instead of failing the deployment.
 */
class MigrationWithoutExtensionsIntegrationTest {

    @Test
    void migrationsApplyForASchemaOwnerWhoCannotCreateExtensions() {
        TestcontainersConfiguration.POSTGRES.start();
        String name = "limited_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate admin = new JdbcTemplate(dataSource(
                TestcontainersConfiguration.POSTGRES.getJdbcUrl(),
                TestcontainersConfiguration.POSTGRES.getUsername(),
                TestcontainersConfiguration.POSTGRES.getPassword()));
        admin.execute("CREATE ROLE " + name + " LOGIN PASSWORD 'limited'");
        admin.execute("CREATE DATABASE " + name);
        // Without CREATE on the database, even a trusted extension such as pg_trgm cannot be created.
        admin.execute("REVOKE CREATE ON DATABASE " + name + " FROM PUBLIC");
        admin.execute("GRANT CONNECT ON DATABASE " + name + " TO " + name);
        String url =
                TestcontainersConfiguration.POSTGRES.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
        new JdbcTemplate(dataSource(
                        url,
                        TestcontainersConfiguration.POSTGRES.getUsername(),
                        TestcontainersConfiguration.POSTGRES.getPassword()))
                .execute("ALTER SCHEMA public OWNER TO " + name);

        Flyway.configure()
                .dataSource(url, name, "limited")
                .locations("classpath:db/migration")
                .load()
                .migrate();

        JdbcTemplate limited = new JdbcTemplate(dataSource(url, name, "limited"));
        assertThat(limited.queryForObject("SELECT count(*) FROM pg_extension WHERE extname = 'pg_trgm'", Integer.class))
                .isZero();
        assertThat(limited.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = 'users'", String.class))
                .contains("ix_users_created_at", "ix_users_updated_at")
                .doesNotContain("ix_users_username_trgm", "ix_users_email_trgm");
    }

    private static DriverManagerDataSource dataSource(String url, String username, String password) {
        return new DriverManagerDataSource(url, username, password);
    }
}
