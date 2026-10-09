package com.democorp.customermaster.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.democorp.customermaster.domain.State;
import com.democorp.customermaster.support.AbstractPostgresIT;
import com.democorp.customermaster.support.DatabaseCleaner;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.transaction.interceptor.TransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Proves that every {@link StateRepository} read runs in the read-only transaction of AAP 0.4.8
 * ("Search, get by id, states: Read-only"), and that the statements still return the V2 rows in
 * PMTSTATER's orders [5250_Subfile/PMTSTATER.SQLRPGLE:150-162].
 *
 * <p><b>Why.</b> Spring Data gives a declared query method a transaction only when an annotation or a
 * matching base-repository method supplies one. {@code searchOrderByName}, {@code searchOrderByCode}
 * and the default {@code search} have no base counterpart, so without the interface-level
 * {@code @Transactional(readOnly = true)} they would run in autocommit, with no Spring transaction.
 *
 * <p><b>How.</b> Two independent checks over the repository bean of the base context:
 * <ul>
 *   <li>the declared attribute: the repository proxy's own {@link TransactionInterceptor} resolves a
 *       read-only, {@code REQUIRED}, default-isolation attribute for every repository method;</li>
 *   <li>the running transaction: a test-local proxy reuses the bean's target and advisors unchanged,
 *       with one probe placed directly after the {@link TransactionInterceptor}. The probe sees what the
 *       query sees: Spring's transaction state on the thread, and PostgreSQL's
 *       {@code transaction_read_only} and {@code transaction_isolation} on the bound connection.
 *       The bean itself, and so the cached context, is never modified.</li>
 * </ul>
 * The class uses the base context with no {@code @MockitoBean}, {@code @Import} or nested
 * configuration, so it adds no context variant. It reads only {@code states}, which the base class
 * never empties.
 */
class StateRepositoryIT extends AbstractPostgresIT {

    /** The repository methods the state cache and the State picker call. */
    private static final List<String> STATE_READS =
            List.of("findAll", "search", "searchOrderByCode", "searchOrderByName");

    /** PostgreSQL's {@code transaction_isolation} at the default READ COMMITTED level. */
    private static final String READ_COMMITTED = "read committed";

    /** PostgreSQL's {@code transaction_read_only} inside a read-only transaction. */
    private static final String READ_ONLY_ON = "on";

    /** The application's repository bean, the Spring Data proxy {@code StateService} calls. */
    @Autowired
    private StateRepository stateRepository;

    /**
     * Checks the declared attribute of every repository method, as the proxy's transaction interceptor
     * resolves it at call time: read-only, {@code PROPAGATION_REQUIRED} (a caller's transaction is
     * joined) and the default isolation, which is PostgreSQL's READ COMMITTED.
     */
    @Test
    void everyRepositoryMethodDeclaresReadOnlyTransaction() {
        Advised proxy = repositoryProxy();
        TransactionAttributeSource attributes = transactionInterceptor(proxy).getTransactionAttributeSource();
        assertThat(attributes).as("transaction attribute source of the repository proxy").isNotNull();
        Class<?> targetClass = proxy.getTargetSource().getTargetClass();

        List<Method> methods = Arrays.stream(StateRepository.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic() && !Modifier.isStatic(method.getModifiers()))
                .toList();
        assertThat(methods).extracting(Method::getName).containsExactlyInAnyOrderElementsOf(STATE_READS);

        for (Method method : methods) {
            TransactionAttribute attribute = attributes.getTransactionAttribute(method, targetClass);
            assertThat(attribute).as("transaction attribute of %s", method.getName()).isNotNull();
            assertThat(attribute.isReadOnly()).as("read-only %s", method.getName()).isTrue();
            assertThat(attribute.getPropagationBehavior()).as("propagation of %s", method.getName())
                    .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
            assertThat(attribute.getIsolationLevel()).as("isolation of %s", method.getName())
                    .isEqualTo(TransactionDefinition.ISOLATION_DEFAULT);
        }
    }

    /**
     * Calls each repository method through the probed proxy and checks that every statement runs in an
     * active read-only READ COMMITTED transaction, that the statement {@code search} delegates to joins
     * the transaction {@code search} opened, and that no transaction remains once a call returns.
     */
    @Test
    void everyStateReadRunsInsideActiveReadOnlyTransaction() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                .as("transaction open before the calls").isFalse();
        List<Observation> observations = new ArrayList<>();
        StateRepository probed = probedRepository(observations);

        assertThat(probed.findAll()).hasSize(DatabaseCleaner.STATE_COUNT);
        assertThat(probed.searchOrderByName("%%")).hasSize(DatabaseCleaner.STATE_COUNT);
        assertThat(probed.searchOrderByCode("%%")).hasSize(DatabaseCleaner.STATE_COUNT);
        assertThat(probed.search("%CAR%", "name")).extracting(State::name)
                .containsExactly("North Carolina", "South Carolina");

        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                .as("transaction left open after the calls").isFalse();
        assertThat(observations).extracting(Observation::method).containsExactly(
                "findAll", "searchOrderByName", "searchOrderByCode", "search", "searchOrderByName");
        for (Observation observation : observations) {
            assertThat(observation.springActive()).as("Spring transaction in %s", observation.method())
                    .isTrue();
            assertThat(observation.springReadOnly()).as("Spring read-only flag in %s", observation.method())
                    .isTrue();
            assertThat(observation.databaseReadOnly())
                    .as("transaction_read_only in %s", observation.method()).isEqualTo(READ_ONLY_ON);
            assertThat(observation.databaseIsolation())
                    .as("transaction_isolation in %s", observation.method()).isEqualTo(READ_COMMITTED);
        }
        Observation outerSearch = observations.get(3);
        Observation delegatedStatement = observations.get(4);
        assertThat(delegatedStatement.connectionHolder())
                .as("the statement search delegates to joins its transaction")
                .isSameAs(outerSearch.connectionHolder());
        assertThat(observations.get(0).connectionHolder())
                .as("separate calls run in separate transactions")
                .isNotSameAs(observations.get(1).connectionHolder());
    }

    /**
     * Checks that both fixed statements still run and keep PMTSTATER's orders: "By Name" for a
     * "Name Contains" filter, and "By Code" over every state in the base-36 order of
     * {@code customer_sort}.
     */
    @Test
    void searchReturnsStatesInRequestedOrder() {
        List<State> byName = stateRepository.search("%CAR%", "name");
        assertThat(byName).extracting(state -> state.state().trim()).containsExactly("NC", "SC");
        assertThat(byName).extracting(State::name).containsExactly("North Carolina", "South Carolina");

        List<State> byCode = stateRepository.search("%%", "code");
        assertThat(byCode).hasSize(DatabaseCleaner.STATE_COUNT);
        assertThat(byCode).extracting(state -> state.state().trim()).startsWith("AA", "AE", "AK");
    }

    /**
     * Returns the repository bean as the Spring Data proxy it is.
     *
     * @return the bean's proxy configuration
     */
    private Advised repositoryProxy() {
        assertThat(stateRepository).as("StateRepository bean").isInstanceOf(Advised.class);
        return (Advised) stateRepository;
    }

    /**
     * Returns the transaction interceptor Spring Data placed on the repository proxy.
     *
     * @param proxy the repository proxy
     * @return its one {@link TransactionInterceptor}
     * @throws AssertionError if the proxy carries none
     */
    private static TransactionInterceptor transactionInterceptor(Advised proxy) {
        return Arrays.stream(proxy.getAdvisors())
                .map(Advisor::getAdvice)
                .filter(TransactionInterceptor.class::isInstance)
                .map(TransactionInterceptor.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("StateRepository proxy has no TransactionInterceptor"));
    }

    /**
     * Builds a test-local copy of the repository proxy with the bean's target, interfaces and advisors,
     * in their order, plus a probe directly after the transaction interceptor. A default method such as
     * {@code search} is invoked on the proxy it was called through, so the statement it delegates to
     * passes the probe as well.
     *
     * @param observations receives one entry per call that reaches the probe, in call order
     * @return the probed repository
     * @throws AssertionError if the bean carries no transaction interceptor
     */
    private StateRepository probedRepository(List<Observation> observations) {
        Advised proxy = repositoryProxy();
        TransactionInterceptor transactions = transactionInterceptor(proxy);
        MethodInterceptor probe = invocation -> {
            observations.add(observe(invocation.getMethod().getName()));
            return invocation.proceed();
        };
        ProxyFactory factory = new ProxyFactory();
        factory.setTargetSource(proxy.getTargetSource());
        factory.setInterfaces(proxy.getProxiedInterfaces());
        for (Advisor advisor : proxy.getAdvisors()) {
            factory.addAdvisor(advisor);
            if (advisor.getAdvice() == transactions) {
                factory.addAdvice(probe);
            }
        }
        return (StateRepository) factory.getProxy(StateRepository.class.getClassLoader());
    }

    /**
     * Records the transaction state the next repository statement runs in. The query runs on the
     * connection bound to the current transaction, or on a fresh autocommit connection when there is
     * none, in which case PostgreSQL reports {@code transaction_read_only = off}.
     *
     * @param method the repository method being called
     * @return what Spring and PostgreSQL report at that point
     */
    private Observation observe(String method) {
        List<String> settings = jdbcTemplate.queryForObject(
                "SELECT current_setting('transaction_read_only'), current_setting('transaction_isolation')",
                (rs, rowNum) -> List.of(rs.getString(1), rs.getString(2)));
        assertThat(settings).as("transaction settings in %s", method).isNotNull();
        return new Observation(
                method,
                TransactionSynchronizationManager.isActualTransactionActive(),
                TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
                settings.get(0),
                settings.get(1),
                TransactionSynchronizationManager.getResource(dataSource));
    }

    /**
     * The transaction state one repository call observed before its statement ran.
     *
     * @param method            the repository method
     * @param springActive      whether a Spring transaction was active on the thread
     * @param springReadOnly    whether that transaction was marked read-only
     * @param databaseReadOnly  PostgreSQL's {@code transaction_read_only} on the bound connection
     * @param databaseIsolation PostgreSQL's {@code transaction_isolation} on the bound connection
     * @param connectionHolder  the connection holder bound to the application datasource, or
     *                          {@code null} when none was bound; identity tells transactions apart
     */
    private record Observation(
            String method,
            boolean springActive,
            boolean springReadOnly,
            String databaseReadOnly,
            String databaseIsolation,
            Object connectionHolder) {
    }
}
