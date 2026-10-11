package dev.reconcilekit;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

@TestConfiguration(proxyBeanMethods = false)
public class ImportTestConfiguration {
    @Bean @Primary ControlledImportObserver controlledImportObserver(JdbcTemplate jdbc) {
        return new ControlledImportObserver(jdbc);
    }
}
