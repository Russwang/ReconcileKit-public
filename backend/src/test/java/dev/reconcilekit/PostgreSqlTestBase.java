package dev.reconcilekit;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** One real PostgreSQL instance per JVM; explicit DB_URL selects an external test database. */
public abstract class PostgreSqlTestBase {
    private static final String EXTERNAL_URL = System.getenv("DB_URL");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        if (usingExternalDatabase()) {
            registry.add("spring.datasource.url", () -> EXTERNAL_URL);
            registry.add("spring.datasource.username", () -> environment("DB_USER", "reconcilekit"));
            registry.add("spring.datasource.password", () -> environment("DB_PASSWORD", ""));
        } else {
            PostgreSQLContainer postgres = ContainerHolder.INSTANCE;
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
        }
    }

    public static boolean usingExternalDatabase() {
        return EXTERNAL_URL != null && !EXTERNAL_URL.isBlank();
    }

    private static String environment(String key, String fallback) {
        String value = System.getenv(key);
        return value == null ? fallback : value;
    }

    private static final class ContainerHolder {
        static final PostgreSQLContainer INSTANCE = start();
        private static PostgreSQLContainer start() {
            PostgreSQLContainer container = new PostgreSQLContainer(DockerImageName.parse("postgres:17.11@sha256:2d2b8998d31037bf721cfdf764d76ba74171b4fab3431b7f72c27c56ddbdf9e3").asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("reconcilekit_test")
                    .withUsername("reconcilekit")
                    .withPassword("synthetic-test-password");
            container.start();
            return container;
        }
    }
}
