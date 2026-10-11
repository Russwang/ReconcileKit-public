package dev.reconcilekit.transaction;

import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.orm.jpa.JpaTransactionManager;

/** JDBC ledger writes share one datasource transaction manager; entity mapping keeps JPA support. */
@Configuration(proxyBeanMethods = false)
public class LedgerTransactionConfiguration {
    @Bean("ledgerTransactions")
    JdbcTransactionManager ledgerTransactions(DataSource dataSource) {
        return new JdbcTransactionManager(dataSource);
    }
    @Bean("transactionManager")
    @Primary
    JpaTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }
}
