package dev.finguard.config;

import org.springframework.boot.test.context.TestConfiguration;

/**
 * Test infrastructure configuration for integration tests.
 *
 * <p>Integration tests connect to the {@code finguard_test} database on the
 * PostgreSQL container started by {@code docker-compose up -d}. The container
 * must be running before executing integration tests.</p>
 *
 * <p>The test database is automatically created by {@code init.sql} when the
 * Docker Compose PostgreSQL container starts for the first time.</p>
 *
 * <p>Connection details are configured in {@code src/test/resources/application.yml}.</p>
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestContainersConfig {
}
