package com.democorp.customermaster.config;

import javax.sql.DataSource;
import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the {@code db} health check, {@link BoundedDataSourceHealthIndicator}, in place of
 * Spring Boot's check, whose connection validation has no deadline, and the
 * {@link ConnectionPoolSaturation} checks that the health check and
 * {@code controller.ApiExceptionHandler} share.
 *
 * <p><b>Name.</b> The health check bean is named {@code dbHealthIndicator}. Boot's
 * {@code DataSourceHealthContributorAutoConfiguration} backs off when a bean of that name exists, and
 * the health contributor takes the name {@code db} from it: the member that
 * {@code management.endpoint.health.group.readiness.include} lists and whose existence Boot verifies
 * at startup. Like Boot's check, the bean is skipped when {@code management.health.db.enabled} is
 * {@code false}. {@code connectionPoolSaturation} carries no condition, so the exception handler has
 * it whether or not the health check exists.
 *
 * <p>The class carries no profile or condition: both beans exist in the web context and in the
 * {@code generator} profile, as Boot's check did. It is found by component scanning from
 * {@code CustomerMasterApplication}, and it needs the application's {@link DataSource}, which every
 * context of the application has. Creating the beans opens no connection.
 */
@Configuration(proxyBeanMethods = false)
public class DataSourceHealthConfig {

    /**
     * Creates the configuration. It holds no state; the bean methods take their collaborators.
     */
    public DataSourceHealthConfig() {
        // Stateless: each bean method receives its collaborators from the container.
    }

    /**
     * The saturation checks over the application's connection pool: whether a failure is the borrow
     * timeout of a saturated pool, and whether PostgreSQL answers a direct probe.
     *
     * @param dataSource the application's {@link DataSource}, the Hikari pool Boot configures
     * @return the checks
     */
    @Bean
    public ConnectionPoolSaturation connectionPoolSaturation(DataSource dataSource) {
        return new ConnectionPoolSaturation(dataSource);
    }

    /**
     * The {@code db} health check over the application's connection pool. The bean name is
     * {@code dbHealthIndicator}, and the contributor name is {@code db}.
     *
     * @param dataSource the application's {@link DataSource}, the Hikari pool Boot configures
     * @param connectionPoolSaturation the checks that keep the probe UP while every pooled connection
     *                                 is busy and PostgreSQL answers
     * @return the bounded check
     */
    @Bean
    @ConditionalOnEnabledHealthIndicator("db")
    public BoundedDataSourceHealthIndicator dbHealthIndicator(DataSource dataSource,
            ConnectionPoolSaturation connectionPoolSaturation) {
        return new BoundedDataSourceHealthIndicator(dataSource, connectionPoolSaturation);
    }
}
