package com.democorp.customermaster.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionManagerCustomizationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionExecutionListener;
import org.springframework.transaction.TransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Specifies {@link TransactionManagerConfig} beside the transaction auto-configurations Spring Boot runs in
 * the application: Boot's own manager backs off, the one manager is a
 * {@link BrokenConnectionTransactionManager} named {@code transactionManager}, Boot's customizers
 * ({@code spring.transaction.*} and execution listeners) reach it, Boot's {@link TransactionTemplate}
 * drives it, and it consults the application's one {@link ConnectionPoolSaturation}.
 *
 * <p>An {@link ApplicationContextRunner} over a mocked {@link DataSource}, with
 * {@link DataSourceHealthConfig} registering the saturation checks as in the application: no database, no
 * Docker. Creating the manager must not touch the data source.
 */
@DisplayName("TransactionManagerConfig: the one transaction manager, customized as Boot's would be")
final class TransactionManagerConfigTest {

    /** The bean name of Boot's manager, which {@code @Transactional} resolves by default. */
    private static final String BEAN_NAME = "transactionManager";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TransactionManagerCustomizationAutoConfiguration.class,
                    DataSourceTransactionManagerAutoConfiguration.class,
                    TransactionAutoConfiguration.class))
            .withUserConfiguration(DataSourceHealthConfig.class, TransactionManagerConfig.class)
            .withBean(DataSource.class, () -> mock(DataSource.class));

    @Test
    @DisplayName("Boot backs off: one TransactionManager, named transactionManager, the broken-connection manager")
    void replacesBootsManager() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(TransactionManager.class)).containsOnlyKeys(BEAN_NAME);
            assertThat(context.getBean(BEAN_NAME)).isInstanceOf(BrokenConnectionTransactionManager.class);
            assertThat(context.getBean(TransactionTemplate.class).getTransactionManager())
                    .isSameAs(context.getBean(BEAN_NAME));
            verifyNoInteractions(context.getBean(DataSource.class));
        });
    }

    @Test
    @DisplayName("the manager consults the context's one ConnectionPoolSaturation")
    void receivesTheContextsSaturationChecks() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ConnectionPoolSaturation.class);
            assertThat(context.getBean(BrokenConnectionTransactionManager.class))
                    .extracting("poolSaturation")
                    .isSameAs(context.getBean(ConnectionPoolSaturation.class));
        });
    }

    @Test
    @DisplayName("without the saturation checks the context does not start")
    void requiresTheSaturationChecks() {
        new ApplicationContextRunner()
                .withUserConfiguration(TransactionManagerConfig.class)
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining(ConnectionPoolSaturation.class.getName()));
    }

    @Test
    @DisplayName("spring.transaction.* and execution listeners apply, as to Boot's manager")
    void appliesBootsCustomizers() {
        TransactionExecutionListener listener = mock(TransactionExecutionListener.class);
        runner.withPropertyValues(
                        "spring.transaction.default-timeout=7s",
                        "spring.transaction.rollback-on-commit-failure=true")
                .withBean(TransactionExecutionListener.class, () -> listener)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    BrokenConnectionTransactionManager manager =
                            context.getBean(BrokenConnectionTransactionManager.class);
                    assertThat(manager.getDefaultTimeout()).isEqualTo(7);
                    assertThat(manager.isRollbackOnCommitFailure()).isTrue();
                    assertThat(manager.getTransactionExecutionListeners()).containsExactly(listener);
                });
    }

    @Test
    @DisplayName("without spring.transaction.* the manager keeps Spring's defaults")
    void keepsSpringsDefaultsWhenNothingIsSet() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            BrokenConnectionTransactionManager manager = context.getBean(BrokenConnectionTransactionManager.class);
            assertThat(manager.getDefaultTimeout()).isEqualTo(TransactionDefinition.TIMEOUT_DEFAULT);
            assertThat(manager.isRollbackOnCommitFailure()).isFalse();
            assertThat(manager.getTransactionExecutionListeners()).isEmpty();
        });
    }
}
