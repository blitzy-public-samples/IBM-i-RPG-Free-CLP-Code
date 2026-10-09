package com.democorp.customermaster.config;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Stops startup when the database schema, which {@code application.yml} takes from {@code DB_SCHEMA}, is
 * set but empty or blank, or begins or ends with a space or a control character, before the connection
 * pool, Flyway or the generator can use it.
 *
 * <p>The IBM i SQL scripts named their library ({@code set schema}). Here {@code application.yml} takes
 * the schema from one variable in three places, each as {@code ${DB_SCHEMA:customer_master}}: the
 * {@code currentSchema} parameter of {@code spring.datasource.url}, {@code spring.datasource.hikari.schema}
 * and {@code spring.flyway.schemas}. The default applies only while the variable is unset, so a variable
 * set to an empty or blank value reaches all three as it is. Hikari then sets that value as the
 * {@code search_path} of every connection and Flyway receives no usable schema: for an empty value it
 * creates a schema literally named {@code ""} with its history table, and the first migration fails with
 * SQL state 3F000, "no schema has been selected to create in", an error that never names the variable. Such
 * a value is refused rather than replaced by {@code customer_master}: an operator who sets the variable
 * means some schema, and falling back to the default one would migrate and write where nobody asked.
 *
 * <p>A value with a space or a control character at either end, such as {@code " qa_pad"}, is refused
 * as well. Spring Boot trims each entry of the comma-separated {@code spring.flyway.schemas} but binds
 * {@code spring.datasource.hikari.schema} as written, so Flyway would migrate {@code qa_pad} while Hikari
 * set {@code " qa_pad"}, a different quoted name, as the {@code search_path}: the API would report ready
 * and every query would fail with SQL state 42P01.
 *
 * <p><b>What is checked.</b> Each value below must contain text, and its first and last characters must
 * not be at or below U+0020, that is a space or a control character, tab and line breaks included:
 * exactly the characters {@link String#trim()} removes. No other rule applies: every other name PostgreSQL
 * accepts passes, mixed case such as {@code QaUpper} included, which Hikari and Flyway quote.
 * <ul>
 *   <li>{@code spring.datasource.hikari.schema}, when set. Hikari applies any value other than null to
 *       each new connection, as written, so an empty one never means "no schema".</li>
 *   <li>The {@code currentSchema} parameter of the JDBC URL of every {@link JdbcConnectionDetails} bean,
 *       the effective source Spring Boot builds the {@code DataSource} from, when it is present. It is
 *       read as the PostgreSQL driver reads it: from the query after the first {@code ?}, split on
 *       {@code &}, the last occurrence winning, the value URL-decoded (so {@code %20} and {@code +} are
 *       spaces), and a parameter without {@code =} meaning an empty value. A URL without the parameter
 *       passes, such as the one Testcontainers {@code @ServiceConnection} registers in the integration
 *       tests, which then rely on {@code spring.datasource.hikari.schema}.</li>
 *   <li>Each entry of {@code spring.flyway.schemas}. The entries of a comma-separated value, the form
 *       {@code application.yml} uses, arrive trimmed, so there only an empty or blank one can fail; an
 *       indexed entry ({@code spring.flyway.schemas[0]}, or a YAML list) arrives as written. An empty
 *       list passes, because Flyway then uses the connection's schema, which the two checks above cover;
 *       through {@code application.yml} the list is empty only when
 *       {@code spring.datasource.hikari.schema} is empty too.</li>
 * </ul>
 * The variable itself is not read: these are the values the pool and Flyway receive, and a command-line
 * property outranks the variable, so a run that sets them explicitly has a schema whatever
 * {@code DB_SCHEMA} holds. A failed check stops startup with an {@link InvalidSchemaException}, an
 * {@link IllegalStateException} that names every failing key under its fault (empty or blank, or a space
 * or control character at either end), the type of the connection details when their URL fails, and
 * {@code DB_SCHEMA}. The message never carries a value or the JDBC URL. Its own type lets the generator's
 * startup failure reporter recognise it without matching message text.
 *
 * <p><b>When.</b> The check runs after a details bean is initialized and before the {@code DataSource}
 * that depends on it is created, so before any connection is opened and before Flyway runs.
 *
 * <p>The class carries no profile or condition: it applies in the web context and in the
 * {@code generator} profile, which runs with no web server and with Flyway disabled but still connects
 * through the pool. It is found by component scanning from {@code CustomerMasterApplication}. It takes no
 * constructor arguments and receives the {@link Environment} through {@link EnvironmentAware} before it
 * processes any bean, so registering it creates no other bean early, and it is {@link PriorityOrdered},
 * so it is registered in the first group of post-processors and also sees a details bean another
 * post-processor creates.
 */
@Component
public class DataSourceSchemaGuard implements BeanPostProcessor, PriorityOrdered, EnvironmentAware {

    /** The pool's schema property, which {@code application.yml} sets from {@code DB_SCHEMA}. */
    private static final String HIKARI_SCHEMA_KEY = "spring.datasource.hikari.schema";

    /** Flyway's schema list property, which {@code application.yml} sets from {@code DB_SCHEMA}. */
    private static final String FLYWAY_SCHEMAS_KEY = "spring.flyway.schemas";

    /** The PostgreSQL driver's URL parameter that selects the session's schema; its name is case-sensitive. */
    private static final String CURRENT_SCHEMA_PARAMETER = "currentSchema";

    /** The environment the checked properties are bound from, as Spring Boot binds them for the pool and Flyway. */
    private Environment environment;

    /**
     * Creates the guard, which takes no collaborators so it can be registered among the first
     * post-processors without creating any other bean early.
     */
    public DataSourceSchemaGuard() {
        // The context supplies the environment through setEnvironment before the guard sees any bean.
    }

    /**
     * Receives the application's environment, before the guard processes any bean.
     *
     * @param environment the application's environment
     * @throws NullPointerException if {@code environment} is null
     */
    @Override
    public void setEnvironment(Environment environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    /**
     * Checks the schema settings when a {@link JdbcConnectionDetails} bean is initialized and returns
     * every bean unchanged.
     *
     * @param bean     the initialized bean
     * @param beanName the bean's name
     * @return {@code bean}
     * @throws InvalidSchemaException if {@code bean} is a {@link JdbcConnectionDetails} and a schema
     *                                setting is set but empty or blank, or begins or ends with a space or a
     *                                control character
     * @throws IllegalStateException  if {@code bean} is a {@link JdbcConnectionDetails} and no environment
     *                                was set, so the settings cannot be read
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
     * Fails when any schema setting the pool or Flyway receives is set but empty or blank, or names a
     * schema with a space or control character at either end, naming every such setting in one message,
     * grouped by fault.
     *
     * @param details the connection details the {@code DataSource} is built from
     * @throws InvalidSchemaException if a check fails; the message names the failing keys by fault, the
     *                                type of {@code details} when their URL fails, and {@code DB_SCHEMA},
     *                                never a value
     * @throws IllegalStateException  if no environment was set, so the settings cannot be read
     */
    private void verify(JdbcConnectionDetails details) {
        Assert.state(environment != null, "DataSourceSchemaGuard has no environment to read the schema settings from");
        Binder binder = Binder.get(environment);
        List<String> blank = new ArrayList<>(3);
        List<String> padded = new ArrayList<>(3);
        String hikariSchema = binder.bind(HIKARI_SCHEMA_KEY, String.class).orElse(null);
        if (hikariSchema != null) {
            classify(hikariSchema, HIKARI_SCHEMA_KEY, blank, padded);
        }
        String urlSchema = currentSchemaParameter(details.getJdbcUrl());
        if (urlSchema != null) {
            classify(urlSchema, "the " + CURRENT_SCHEMA_PARAMETER + " parameter of the JDBC URL"
                    + " (spring.datasource.url) in connection details " + details.getClass().getName(), blank, padded);
        }
        List<String> flywaySchemas = binder.bind(FLYWAY_SCHEMAS_KEY, Bindable.listOf(String.class))
                .orElse(List.of());
        String flywayEntry = "an entry of " + FLYWAY_SCHEMAS_KEY;
        if (flywaySchemas.stream().anyMatch(schema -> !StringUtils.hasText(schema))) {
            blank.add(flywayEntry);
        }
        if (flywaySchemas.stream().anyMatch(schema -> StringUtils.hasText(schema) && isPadded(schema))) {
            padded.add(flywayEntry);
        }
        if (!blank.isEmpty() || !padded.isEmpty()) {
            List<String> faults = new ArrayList<>(2);
            if (!blank.isEmpty()) {
                faults.add(joinAsSentence(blank) + (blank.size() == 1 ? " is" : " are") + " empty or blank");
            }
            if (!padded.isEmpty()) {
                faults.add(joinAsSentence(padded) + (padded.size() == 1 ? " begins or ends" : " begin or end")
                        + " with a space or a control character such as a tab");
            }
            boolean single = blank.size() + padded.size() == 1;
            throw new InvalidSchemaException("Database schema invalid: " + String.join(", and ", faults)
                    + ". application.yml sets " + (single ? "it" : "them") + " from the environment variable"
                    + " DB_SCHEMA, whose default customer_master applies only while the variable is unset: give"
                    + " DB_SCHEMA a schema name that neither begins nor ends with a space or a control character,"
                    + " or unset it. Startup stops before any database connection is opened, so no schema is"
                    + " created.");
        }
    }

    /**
     * Adds a set schema value's setting to the list of the fault it shows, if any.
     *
     * @param value   the value as the pool or the driver receives it, not null
     * @param setting the phrase that names the setting in the failure
     * @param blank   the settings that are empty or blank
     * @param padded  the settings that name a schema with a space or control character at either end
     */
    private static void classify(String value, String setting, List<String> blank, List<String> padded) {
        if (!StringUtils.hasText(value)) {
            blank.add(setting);
        } else if (isPadded(value)) {
            padded.add(setting);
        }
    }

    /**
     * Tells whether a value begins or ends with a character at or below U+0020: a space or a control
     * character, tab and line breaks included. These are the characters {@link String#trim()} removes,
     * which Spring Boot applies to each entry of a comma-separated list such as
     * {@code spring.flyway.schemas} but not to {@code spring.datasource.hikari.schema}, so a value with one
     * at either end reaches Flyway and the pool as two different schema names.
     *
     * @param value the value, not null
     * @return true if {@code value} differs from its trimmed form
     */
    private static boolean isPadded(String value) {
        return !value.equals(value.trim());
    }

    /**
     * Returns the value the PostgreSQL driver takes for {@value #CURRENT_SCHEMA_PARAMETER} from a JDBC URL.
     *
     * @param jdbcUrl the URL, possibly null
     * @return the URL-decoded value of the parameter's last occurrence, {@code ""} for an occurrence without
     *         {@code =}, or null if the URL is null, has no query or lacks the parameter
     */
    private static String currentSchemaParameter(String jdbcUrl) {
        int query = (jdbcUrl == null) ? -1 : jdbcUrl.indexOf('?');
        if (query < 0) {
            return null;
        }
        String value = null;
        for (String parameter : jdbcUrl.substring(query + 1).split("&")) {
            int equals = parameter.indexOf('=');
            String name = (equals < 0) ? parameter : parameter.substring(0, equals);
            if (CURRENT_SCHEMA_PARAMETER.equals(name)) {
                value = (equals < 0) ? "" : decoded(parameter.substring(equals + 1));
            }
        }
        return value;
    }

    /**
     * URL-decodes a query value as UTF-8, as the PostgreSQL driver does.
     *
     * @param raw the value as written in the URL
     * @return the decoded value, or {@code raw} itself when it holds a malformed escape, which the driver
     *         rejects with its own error when the pool connects
     */
    private static String decoded(String raw) {
        try {
            return URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            return raw;
        }
    }

    /**
     * Joins phrases as an English list: {@code "a"}, {@code "a and b"}, {@code "a, b and c"}.
     *
     * @param phrases one or more phrases
     * @return the joined phrases
     */
    private static String joinAsSentence(List<String> phrases) {
        int last = phrases.size() - 1;
        if (last == 0) {
            return phrases.get(0);
        }
        return String.join(", ", phrases.subList(0, last)) + " and " + phrases.get(last);
    }

    /**
     * The guard's failure: a schema setting fed from {@code DB_SCHEMA} is set but empty or blank, or
     * begins or ends with a space or a control character.
     *
     * <p>The message is one line that names every failing key under its fault, the type of the connection
     * details when their URL fails, and {@code DB_SCHEMA}, never a value or the JDBC URL. It stays an
     * {@link IllegalStateException}, the type Spring Boot and existing callers see; the subtype exists so
     * that the generator CLI, which prints this message as its one outcome line, recognises the failure by
     * type rather than by text.
     */
    public static final class InvalidSchemaException extends IllegalStateException {

        /** Serialization version. */
        private static final long serialVersionUID = 1L;

        /**
         * Creates the exception.
         *
         * @param message the one-line message naming the failing keys and {@code DB_SCHEMA}, never a value
         */
        public InvalidSchemaException(String message) {
            super(message);
        }
    }
}
