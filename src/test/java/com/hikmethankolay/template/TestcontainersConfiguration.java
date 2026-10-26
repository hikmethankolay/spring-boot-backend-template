package com.hikmethankolay.template;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Throwaway PostgreSQL and Redis for tests, pointed to by {@code @ServiceConnection}.
 *
 * <p>The containers are singletons shared by every test context (tests with different
 * annotations get different Spring contexts). This keeps test runs fast and avoids starting
 * several container sets at once, which some runtimes (e.g. rootless Podman) handle poorly.</p>
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    // Package-private for MigrationJobIntegrationTest, which must not get them as beans: closing a context stops
    // the containers it got as beans, for every test that comes after.
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:8-alpine")).withExposedPorts(6379);

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return POSTGRES;
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return REDIS;
    }
}
