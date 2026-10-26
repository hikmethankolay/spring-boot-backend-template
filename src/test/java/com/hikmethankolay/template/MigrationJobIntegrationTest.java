package com.hikmethankolay.template;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The split used in production (deploy/kubernetes/migrate-job.yaml): the "migrate" profile runs the migrations and
 * starts nothing else; then the app starts with Flyway off against the migrated schema, which Hibernate validates.
 * Both run here in that order, so the result does not depend on which test migrated the shared database first.
 */
class MigrationJobIntegrationTest {

    @Test
    void theMigrateProfileMigratesThenTheAppStartsWithoutFlyway() {
        try (ConfigurableApplicationContext migrate = new SpringApplicationBuilder(Application.class)
                .profiles("test", "migrate")
                .run(connections())) {
            assertThat(migrate).isNotInstanceOf(WebServerApplicationContext.class);
            // Needs settings the Job does not have; the Job has only the schema owner's credentials.
            assertThat(migrate.containsBean("adminBootstrap")).isFalse();
            assertThat(migrate.getBean(JdbcTemplate.class)
                            .queryForObject(
                                    "SELECT count(*) FROM flyway_schema_history WHERE NOT success", Integer.class))
                    .isZero();
        }

        try (ConfigurableApplicationContext app = new SpringApplicationBuilder(Application.class)
                .profiles("test")
                .run(Stream.concat(
                                Arrays.stream(connections()),
                                Stream.of("--spring.flyway.enabled=false", "--server.port=0"))
                        .toArray(String[]::new))) {
            assertThat(app.getBeanNamesForType(Flyway.class)).isEmpty();
            assertThat(app.containsBean("adminBootstrap")).isTrue();
        }
    }

    /**
     * Points a context at the shared test containers through arguments (they beat the placeholders in
     * application.yml), not as beans: a context stops the containers it got as beans when it closes.
     */
    private static String[] connections() {
        TestcontainersConfiguration.POSTGRES.start();
        TestcontainersConfiguration.REDIS.start();
        return new String[] {
            "--spring.datasource.url=" + TestcontainersConfiguration.POSTGRES.getJdbcUrl(),
            "--spring.datasource.username=" + TestcontainersConfiguration.POSTGRES.getUsername(),
            "--spring.datasource.password=" + TestcontainersConfiguration.POSTGRES.getPassword(),
            "--spring.data.redis.host=" + TestcontainersConfiguration.REDIS.getHost(),
            "--spring.data.redis.port=" + TestcontainersConfiguration.REDIS.getMappedPort(6379)
        };
    }
}
