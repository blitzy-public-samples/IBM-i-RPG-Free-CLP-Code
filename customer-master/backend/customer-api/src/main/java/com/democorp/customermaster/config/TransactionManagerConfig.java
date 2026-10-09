package com.democorp.customermaster.config;

import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.transaction.TransactionManagerCustomizers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the application's one transaction manager, {@link BrokenConnectionTransactionManager}, in
 * place of the {@code JdbcTransactionManager} Spring Boot would create, so a transaction whose
 * connection broke ends with the statement's own exception instead of a failed rollback (the manager's
 * description says why that matters for the 500 DEM9999 log line), and a read-only transaction waits
 * for a pooled connection while the pool is saturated and PostgreSQL answers, instead of failing after
 * the connection timeout. The manager receives the application's one {@link ConnectionPoolSaturation},
 * the checks {@link DataSourceHealthConfig} registers, to tell the two apart.
 *
 * <p><b>Boot backs off.</b> {@code DataSourceTransactionManagerAutoConfiguration} creates its manager
 * only when no {@code TransactionManager} bean exists ({@code @ConditionalOnMissingBean}), and
 * application configuration is processed before auto-configuration, so this bean is the only manager.
 * The Spring Data JDBC configuration, {@code repository.JdbcConfig}, defines none. The bean is built as
 * Boot builds its own: over the application's {@link DataSource}, here with the saturation checks
 * beside it, then handed to Boot's
 * {@link TransactionManagerCustomizers}, so {@code spring.transaction.default-timeout},
 * {@code spring.transaction.rollback-on-commit-failure} and any {@code TransactionExecutionListener}
 * bean still apply. Boot would create a non-translating {@code DataSourceTransactionManager} when
 * {@code spring.dao.exceptiontranslation.enabled} is {@code false}; the application never sets that
 * property, and this manager always translates.
 *
 * <p>The class carries no profile or condition: the bean exists in the web context, in the {@code test}
 * profile and in the {@code generator} profile, whose {@code CustomerLoader.load} runs in a transaction
 * of this manager, as do the saturation checks, which carry no condition either. It is found by
 * component scanning from {@code CustomerMasterApplication}. Creating the bean opens no connection.
 */
@Configuration(proxyBeanMethods = false)
public class TransactionManagerConfig {

    /**
     * Creates the configuration. It holds no state; the bean method takes its collaborators.
     */
    public TransactionManagerConfig() {
        // Stateless: the bean method receives its collaborators from the container.
    }

    /**
     * The application's transaction manager. The bean name is {@code transactionManager}, the name
     * Boot's own manager has and {@code @Transactional} resolves by default.
     *
     * @param dataSource the application's {@link DataSource}, the Hikari pool Boot configures
     * @param connectionPoolSaturation the checks over that pool, which decide whether a read-only
     *                                 transaction borrows again after a borrow timeout
     * @param transactionManagerCustomizers Boot's customizers ({@code spring.transaction.*} and
     *                                      execution listeners), applied when present
     * @return the manager, customized
     */
    @Bean
    public BrokenConnectionTransactionManager transactionManager(DataSource dataSource,
            ConnectionPoolSaturation connectionPoolSaturation,
            ObjectProvider<TransactionManagerCustomizers> transactionManagerCustomizers) {
        BrokenConnectionTransactionManager transactionManager =
                new BrokenConnectionTransactionManager(dataSource, connectionPoolSaturation);
        transactionManagerCustomizers.ifAvailable(customizers -> customizers.customize(transactionManager));
        return transactionManager;
    }
}
