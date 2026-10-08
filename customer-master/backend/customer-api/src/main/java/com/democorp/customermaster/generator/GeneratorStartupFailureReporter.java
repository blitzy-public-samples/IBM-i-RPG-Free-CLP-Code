package com.democorp.customermaster.generator;

import com.democorp.customermaster.config.DataSourceCredentialsGuard.MissingCredentialsException;
import java.io.PrintStream;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringBootExceptionReporter;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

/**
 * Reports a generator start that fails for want of its database as one line, keeping the generator
 * CLI's contract: every failure exits with status 1 and prints one line naming its cause, as
 * {@code Cannot allocate CUSTMAST} or {@code Unknown option --<name>} do.
 *
 * <p><b>Why it exists.</b> The generator context needs the database while it starts: the JDBC dialect
 * is detected from a live connection, and {@code DataSourceCredentialsGuard} checks the credentials
 * before the pool exists. A wrong password, an unknown host, a refused port, a missing database or an
 * empty {@code DB_USER} therefore fails the context refresh, before {@link CustomerGeneratorRunner}
 * exists to print its line. Spring Boot would log {@code Application run failed} with a stack trace of
 * about 200 lines and the cause at its bottom.
 *
 * <p><b>What it reports.</b> Only in a context whose environment has profile
 * {@value CustomerGeneratorRunner#PROFILE} active, and only for a failure whose cause graph, including
 * {@link SQLException#getNextException()}, holds one of:
 * <ul>
 *   <li>a {@link MissingCredentialsException}: prints the guard's own message;</li>
 *   <li>a {@link CannotGetJdbcConnectionException}, or an {@link SQLException} with SQLSTATE class
 *       {@code 08} (connection exception) or {@code 28} (invalid authorization): prints
 *       {@code Cannot connect to database <host:port>: <reason>}. The address is the authority of the
 *       resolved {@value #URL_PROPERTY} only, never its path, query or user information, and it is left
 *       out when the URL has no parseable authority. The reason is the first available of: the
 *       server's class-28 message (such as {@code FATAL: password authentication failed for user
 *       "customermaster"}), {@code unknown host <name>}, the socket failure ({@code connection refused},
 *       {@code connect timed out}), the deepest {@link SQLException}'s message (such as
 *       {@code FATAL: database "nodb" does not exist}), and the root cause's message.</li>
 * </ul>
 * The line passes through {@link CustomerGeneratorRunner#oneLine(String)}, goes to {@code System.out}
 * like the runner's lines, and carries no credential: no message it is built from holds the password,
 * and the URL contributes its authority alone. The full exception is logged at DEBUG.
 *
 * <p><b>What it leaves to Spring Boot.</b> Every other failure returns {@code false}, so the next
 * reporter, Spring Boot's {@code FailureAnalyzers}, still reports it: an invalid option value such as
 * {@code --count=0} keeps its {@code APPLICATION FAILED TO START} description. The web context is never
 * handled here. The exit status stays 1 either way: {@code SpringApplication.run} rethrows the failure
 * out of {@code main}, and a reported failure is only marked as logged so that it is not printed again.
 *
 * <p><b>Registration.</b> Spring Boot instantiates every {@link SpringBootExceptionReporter} listed in
 * {@code META-INF/spring.factories} with the failed context, which is {@code null} when the start failed
 * before the context was created, and asks them in order. {@link Order} with
 * {@link Ordered#HIGHEST_PRECEDENCE} puts this one first. It never throws: anything unexpected while
 * reporting returns {@code false}, which leaves the failure to Spring Boot's normal handling.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class GeneratorStartupFailureReporter implements SpringBootExceptionReporter {

    /** The property whose authority names the database in the printed line. */
    static final String URL_PROPERTY = "spring.datasource.url";

    /**
     * The only URL form whose authority is printed:
     * {@code jdbc:postgresql://host[:port][,host[:port]...]/...}.
     */
    static final String URL_PREFIX = "jdbc:postgresql://";

    /** The start of every connection-failure line. */
    static final String CONNECT_FAILURE = "Cannot connect to database";

    /** SQLSTATE class {@code 08}, connection exception, such as 08001 for a connection not established. */
    private static final String CONNECTION_EXCEPTION_CLASS = "08";

    /** SQLSTATE class {@code 28}, invalid authorization specification, such as 28P01 for a bad password. */
    private static final String INVALID_AUTHORIZATION_CLASS = "28";

    /** At most this many throwables of a cause graph are inspected, so a cyclic graph ends. */
    private static final int MAX_CAUSE_DEPTH = 64;

    /** One host with an optional port: a name or IPv4 address, or a bracketed IPv6 address. */
    private static final String HOST_PORT = "(?:[A-Za-z0-9._-]+|\\[[0-9A-Fa-f:.]+\\])(?::\\d{1,5})?";

    /** The authority pgjdbc accepts: one or more comma-separated hosts, each with an optional port. */
    private static final Pattern AUTHORITY = Pattern.compile(HOST_PORT + "(?:," + HOST_PORT + ")*");

    /** Receives the full exception of a reported failure, at DEBUG. */
    private static final Logger LOG = LoggerFactory.getLogger(GeneratorStartupFailureReporter.class);

    /** The context whose start failed; {@code null} when the start failed before it was created. */
    private final ConfigurableApplicationContext context;

    /** Receives the one outcome line; {@code System.out} in production. */
    private final PrintStream out;

    /**
     * Creates the reporter, printing to {@code System.out}. Spring Boot calls this constructor for every
     * failed start of the application.
     *
     * @param context the context whose start failed, or {@code null} when none was created
     */
    public GeneratorStartupFailureReporter(ConfigurableApplicationContext context) {
        this(context, System.out);
    }

    /**
     * Creates the reporter with an explicit output stream, for tests that capture the outcome line.
     *
     * @param context the context whose start failed, or {@code null} when none was created
     * @param out     the stream that receives the outcome line
     * @throws NullPointerException if {@code out} is {@code null}
     */
    GeneratorStartupFailureReporter(ConfigurableApplicationContext context, PrintStream out) {
        this.context = context;
        this.out = Objects.requireNonNull(out, "out");
    }

    /**
     * Prints the one line for a database startup failure of the generator.
     *
     * @param failure the exception the start failed with
     * @return {@code true} when the line was printed, so Spring Boot logs nothing more about the failure;
     *         {@code false} for any other failure or context, which leaves it to the next reporter
     */
    @Override
    public boolean reportException(Throwable failure) {
        try {
            if (failure == null || context == null) {
                return false;
            }
            final Environment environment = context.getEnvironment();
            if (!environment.matchesProfiles(CustomerGeneratorRunner.PROFILE)) {
                return false;
            }
            final Optional<String> line = describe(failure, environment);
            if (line.isEmpty()) {
                return false;
            }
            LOG.debug("generator.startup failed: {}", line.get(), failure);
            out.println(CustomerGeneratorRunner.oneLine(line.get()));
            out.flush();
            return true;
        } catch (RuntimeException unexpected) {
            LOG.debug("generator.startup failure left to Spring Boot's report", unexpected);
            return false;
        }
    }

    /**
     * Builds the line for a database startup failure.
     *
     * @param failure     the exception the start failed with, or {@code null}
     * @param environment the failed context's environment, source of {@value #URL_PROPERTY}
     * @return the guard's message for missing credentials, {@code Cannot connect to database
     *         [<authority>]: <reason>} for a connection failure, otherwise (and for {@code null}) empty
     */
    static Optional<String> describe(Throwable failure, Environment environment) {
        final CauseScan scan = CauseScan.of(failure);
        if (scan.missingCredentials != null) {
            return Optional.of(CustomerGeneratorRunner.messageOf(scan.missingCredentials));
        }
        if (!scan.connectionFailure) {
            return Optional.empty();
        }
        final String where = authority(datasourceUrl(environment))
                .map(authority -> " " + authority)
                .orElse("");
        return Optional.of(CONNECT_FAILURE + where + ": " + scan.reason(failure));
    }

    /**
     * Extracts the hosts and ports of a PostgreSQL JDBC URL, and nothing else.
     *
     * <p>Examples: {@code jdbc:postgresql://db:5432/customermaster?currentSchema=x} gives {@code db:5432};
     * {@code jdbc:postgresql://a:5432,b/x} gives {@code a:5432,b}; a URL without the
     * {@value #URL_PREFIX} prefix, with an {@code @} before its path, or whose authority is not a list of
     * {@code host[:port]} gives empty.
     *
     * @param url the resolved datasource URL, or {@code null}
     * @return the authority, before any {@code /}, {@code ?} or {@code #}; empty when it cannot be parsed
     *         or user information may precede the path, so that no credential is ever printed
     */
    static Optional<String> authority(String url) {
        if (url == null || !url.startsWith(URL_PREFIX)) {
            return Optional.empty();
        }
        final String rest = url.substring(URL_PREFIX.length());
        final int slash = rest.indexOf('/');
        final String head = slash < 0 ? rest : rest.substring(0, slash);
        if (head.indexOf('@') >= 0) {
            // User information, or a query value holding '@', comes before the path: print none of it.
            return Optional.empty();
        }
        int end = head.length();
        for (char delimiter : new char[] {'?', '#'}) {
            final int at = head.indexOf(delimiter);
            if (at >= 0 && at < end) {
                end = at;
            }
        }
        final String authority = head.substring(0, end);
        return AUTHORITY.matcher(authority).matches() ? Optional.of(authority) : Optional.empty();
    }

    /**
     * Reads the resolved datasource URL.
     *
     * @param environment the failed context's environment
     * @return the URL, or {@code null} when it is unset or a placeholder in it cannot be resolved
     */
    private static String datasourceUrl(Environment environment) {
        try {
            return environment.getProperty(URL_PROPERTY);
        } catch (IllegalArgumentException unresolvable) {
            return null;
        }
    }

    /**
     * Whether an SQLSTATE belongs to a class.
     *
     * @param sql        the exception
     * @param stateClass the two-character class
     * @return {@code true} when the exception's SQLSTATE has five characters and starts with the class
     */
    private static boolean hasStateClass(SQLException sql, String stateClass) {
        final String state = sql.getSQLState();
        return state != null && state.length() == 5 && state.startsWith(stateClass);
    }

    /**
     * Lowercases the first letter of a sentence-case message, so {@code Connection refused} reads as
     * {@code connection refused} after the colon; an acronym such as {@code SSL} stays as it is.
     *
     * @param text a non-empty single-line message
     * @return the message with its first letter lowercased when the second character is not uppercase
     */
    private static String decapitalize(String text) {
        if (text.length() > 1
                && Character.isUpperCase(text.charAt(0))
                && !Character.isUpperCase(text.charAt(1))) {
            return text.substring(0, 1).toLowerCase(Locale.ROOT) + text.substring(1);
        }
        return text;
    }

    /**
     * What a failure's cause graph holds: each throwable once, at most {@value #MAX_CAUSE_DEPTH} of
     * them, following {@link Throwable#getCause()} and {@link SQLException#getNextException()}.
     */
    private static final class CauseScan {

        /** The credentials guard's failure, if any. */
        private MissingCredentialsException missingCredentials;

        /** Whether the graph holds a connection failure. */
        private boolean connectionFailure;

        /** The first {@link SQLException} of SQLSTATE class 28, if any. */
        private SQLException authorization;

        /** The first unknown host, if any. */
        private UnknownHostException unknownHost;

        /** The first socket failure, such as a refused or timed-out connect, if any. */
        private Exception socket;

        /** The {@link SQLException} farthest from the failure, if any. */
        private SQLException deepestSql;

        /** The depth of {@link #deepestSql}. */
        private int deepestSqlDepth = -1;

        /** Creates an empty scan; {@link #of(Throwable)} fills it. */
        private CauseScan() {
            // Fields start empty and are set while the graph is walked.
        }

        /**
         * Scans a failure's cause graph breadth first.
         *
         * @param failure the failure, or {@code null}, which holds nothing
         * @return what the graph holds
         */
        static CauseScan of(Throwable failure) {
            final CauseScan scan = new CauseScan();
            final Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            final Deque<Throwable> pending = new ArrayDeque<>();
            final Deque<Integer> depths = new ArrayDeque<>();
            if (failure != null) {
                pending.add(failure);
                depths.add(0);
            }
            while (!pending.isEmpty() && visited.size() < MAX_CAUSE_DEPTH) {
                final Throwable current = pending.poll();
                final int depth = depths.poll();
                if (!visited.add(current)) {
                    continue;
                }
                scan.inspect(current, depth);
                if (current instanceof SQLException sql && sql.getNextException() != null) {
                    pending.add(sql.getNextException());
                    depths.add(depth + 1);
                }
                if (current.getCause() != null) {
                    pending.add(current.getCause());
                    depths.add(depth + 1);
                }
            }
            return scan;
        }

        /**
         * Records what one throwable of the graph contributes.
         *
         * @param current the throwable
         * @param depth   its distance from the failure
         */
        private void inspect(Throwable current, int depth) {
            if (current instanceof MissingCredentialsException missing && missingCredentials == null) {
                missingCredentials = missing;
            }
            if (current instanceof CannotGetJdbcConnectionException) {
                connectionFailure = true;
            }
            if (current instanceof SQLException sql) {
                if (hasStateClass(sql, INVALID_AUTHORIZATION_CLASS)) {
                    connectionFailure = true;
                    if (authorization == null) {
                        authorization = sql;
                    }
                } else if (hasStateClass(sql, CONNECTION_EXCEPTION_CLASS)) {
                    connectionFailure = true;
                }
                if (depth > deepestSqlDepth) {
                    deepestSql = sql;
                    deepestSqlDepth = depth;
                }
            }
            if (current instanceof UnknownHostException host && unknownHost == null) {
                unknownHost = host;
            }
            final boolean socketFailure = current instanceof SocketException
                    || current instanceof SocketTimeoutException;
            if (socketFailure && socket == null) {
                socket = (Exception) current;
            }
        }

        /**
         * Picks the clearest reason for a connection failure.
         *
         * @param failure the failure, whose root-cause message is the last resort
         * @return a non-blank single-line reason
         */
        String reason(Throwable failure) {
            if (authorization != null) {
                return CustomerGeneratorRunner.messageOf(authorization);
            }
            if (unknownHost != null) {
                final String host = unknownHost.getMessage() == null
                        ? ""
                        : CustomerGeneratorRunner.oneLine(unknownHost.getMessage());
                return host.isEmpty() ? "unknown host" : "unknown host " + host;
            }
            if (socket != null) {
                final String text = socket.getMessage() == null
                        ? ""
                        : CustomerGeneratorRunner.oneLine(socket.getMessage());
                return text.isEmpty() ? socket.getClass().getSimpleName() : decapitalize(text);
            }
            if (deepestSql != null) {
                return CustomerGeneratorRunner.messageOf(deepestSql);
            }
            return CustomerGeneratorRunner.rootMessage(failure);
        }
    }
}
