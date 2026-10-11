package dev.reconcilekit;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

@TestConfiguration(proxyBeanMethods = false)
public class TransactionTestConfiguration {
    @Bean @Primary ControlledTransactionObserver controlledTransactionObserver(JdbcTemplate jdbc) {
        return new ControlledTransactionObserver(jdbc);
    }
}
