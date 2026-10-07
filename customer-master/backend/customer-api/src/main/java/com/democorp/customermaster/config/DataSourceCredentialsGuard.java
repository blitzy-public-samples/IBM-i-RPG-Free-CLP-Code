package com.democorp.customermaster.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Stops startup when the database role has no username or no password, before the connection pool,
 * Flyway or the generator can use them.
 *
 * <p>The IBM i programs ran under the job's user profile and needed no database credential. Here the
 * API, Flyway and the generator connect as one application role, which {@code application.yml} binds
 * as {@code spring.datasource.username: ${DB_USER:}} and {@code spring.datasource.password:
 * ${DB_PASSWORD:}}, with no default. An unset variable therefore becomes an empty value, and nothing
 * else rejects it: pgjdbc connects with no password, or as the operating-system user when the
 * username is empty, and a server that trusts the connection accepts it, so the application would
 * start without its required configuration.
 *
 * <p><b>What is checked.</b> Every {@link JdbcConnectionDetails} bean, the effective source Spring
 * Boot builds the {@code DataSource} from: the details it derives from {@code spring.datasource.*},
 * or a provider that replaces them, such as the details Testcontainers {@code @ServiceConnection}
 * registers in the integration tests. The username and the password must each contain text.
 * Otherwise startup fails with an {@link IllegalStateException} that names every missing key with its
 * environment variable, and the type of the details. The message never carries a credential value or
 * the JDBC URL. Credentials set only on the pool ({@code spring.datasource.hikari.*}) do not count:
 * {@code DB_USER} and {@code DB_PASSWORD} are the one documented source.
 *
 * <p><b>When.</b> The check runs after the details bean is initialized (a {@code @ServiceConnection}
 * provider reaches its container only then) and before the {@code DataSource} that depends on it is
 * created, so before any connection is opened and before Flyway runs.
 *
 * <p>The class carries no profile or condition: it applies in the web context and in the
 * {@code generator} profile, which runs with no web server and with Flyway disabled. It is found by
 * component scanning from {@code CustomerMasterApplication}. It has no dependencies, so registering it
 * creates no other bean early, and it is {@link PriorityOrdered}, so it is registered in the first
 * group of post-processors and also sees a details bean another post-processor creates.
 */
@Component
public class DataSourceCredentialsGuard implements BeanPostProcessor, PriorityOrdered {

    /** The username's property and environment variable, as {@code application.yml} binds them. */
    private static final String USERNAME_KEY = "spring.datasource.username (environment variable DB_USER)";

    /** The password's property and environment variable, as {@code application.yml} binds them. */
    private static final String PASSWORD_KEY = "spring.datasource.password (environment variable DB_PASSWORD)";

    /**
     * Creates the guard, which takes no collaborators so it can be registered among the first
     * post-processors without creating any other bean early.
     */
    public DataSourceCredentialsGuard() {
        // Stateless: every check reads only the details bean it is given.
    }

    /**
     * Checks a {@link JdbcConnectionDetails} bean and returns every bean unchanged.
     *
     * @param bean     the initialized bean
     * @param beanName the bean's name
     * @return {@code bean}
     * @throws IllegalStateException if {@code bean} is a {@link JdbcConnectionDetails} whose username or
     *                               password is null, empty or blank
     */
    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof JdbcConnectionDetails details) {
            verify(details);
        }
        return bean;
    }

    /**
     * Runs among the last of the priority post-processors; the check does not depend on the others.
     *
     * @return {@link Ordered#LOWEST_PRECEDENCE}
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    /**
     * Fails when the details supply no username or no password, naming both keys when both are missing.
     *
     * @param details the connection details the {@code DataSource} is built from
     * @throws IllegalStateException if the username or the password is null, empty or blank; the message
     *                               names the missing keys and the type of {@code details}, never a value
     */
    private static void verify(JdbcConnectionDetails details) {
        List<String> missing = new ArrayList<>(2);
        if (!StringUtils.hasText(details.getUsername())) {
            missing.add(USERNAME_KEY);
        }
        if (!StringUtils.hasText(details.getPassword())) {
            missing.add(PASSWORD_KEY);
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Database credentials missing: " + String.join(" and ", missing)
                    + (missing.size() == 1 ? " has" : " have") + " no value in connection details "
                    + details.getClass().getName() + ". The API, Flyway and the generator connect as this one"
                    + " role, so startup stops before any database connection is opened.");
        }
    }
}
