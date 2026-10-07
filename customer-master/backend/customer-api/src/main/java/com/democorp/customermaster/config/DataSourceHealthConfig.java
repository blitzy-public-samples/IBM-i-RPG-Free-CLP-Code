package com.democorp.customermaster.config;

import javax.sql.DataSource;
import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the {@code db} health check, {@link BoundedDataSourceHealthIndicator}, in place of
 * Spring Boot's check, whose connection validation has no deadline.
 *
 * <p><b>Name.</b> The bean is named {@code dbHealthIndicator}. Boot's
 * {@code DataSourceHealthContributorAutoConfiguration} backs off when a bean of that name exists, and
 * the health contributor takes the name {@code db} from it: the member that
 * {@code management.endpoint.health.group.readiness.include} lists and whose existence Boot verifies
 * at startup. Like Boot's check, the bean is skipped when {@code management.health.db.enabled} is
 * {@code false}.
 *
 * <p>The class carries no profile or condition: the check exists in the web context and in the
 * {@code generator} profile, as Boot's did. It is found by component scanning from
 * {@code CustomerMasterApplication}, and it needs the application's {@link DataSource}, which every
 * context of the application has. Creating the bean opens no connection.
 */
@Configuration(proxyBeanMethods = false)
public class DataSourceHealthConfig {

    /**
     * Creates the configuration. It holds no state; the bean method takes its collaborator.
     */
    public DataSourceHealthConfig() {
        // Stateless: dbHealthIndicator receives the DataSource from the container.
    }

    /**
     * The {@code db} health check over the application's connection pool. The bean name is
     * {@code dbHealthIndicator}, and the contributor name is {@code db}.
     *
     * @param dataSource the application's {@link DataSource}, the Hikari pool Boot configures
     * @return the bounded check
     */
    @Bean
    @ConditionalOnEnabledHealthIndicator("db")
    public BoundedDataSourceHealthIndicator dbHealthIndicator(DataSource dataSource) {
        return new BoundedDataSourceHealthIndicator(dataSource);
    }
}
