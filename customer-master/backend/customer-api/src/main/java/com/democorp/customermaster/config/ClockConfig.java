package com.democorp.customermaster.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the application's one {@link Clock}, in UTC, from which every stored timestamp is taken.
 *
 * <p>The bean replaces these IBM i time sources:
 * <ul>
 *   <li>MTNCUSTR AddRecd stamps a new customer with {@code CHGTIME = %timestamp()}.</li>
 *   <li>MTNCUSTR UpdateRecd stamps a change with {@code CHGTIME = CURRENT TIMESTAMP} inside the
 *       UPDATE itself.</li>
 *   <li>LOADCUSTR leaves {@code CHGTIME} at the cleared, lowest timestamp. The generator stamps
 *       the load start instead.</li>
 * </ul>
 * The source stores local time with no zone. The target column {@code chgtime} is
 * {@code timestamptz(6)} and holds an instant, which the browser shows in its own local time, so
 * the clock is UTC.
 *
 * <p><b>Contract for production code.</b> Every timestamp comes from this bean, as
 * {@code OffsetDateTime.now(clock)}, and never from an argument-less {@code now()} or a clock the
 * caller builds itself:
 * <ul>
 *   <li>{@code CustomerMaintenanceService} sets {@code chgTime} on add and update, written in the
 *       same INSERT or UPDATE as the data, so the stamp commits or rolls back with the write.</li>
 *   <li>{@code CustomerGeneratorRunner} takes the load start from it, passes that value to
 *       {@code CustomerDataGenerator} as every generated row's {@code chgtime}; the elapsed time
 *       it prints is an interval, not a timestamp, and is measured with the monotonic
 *       {@code System.nanoTime()}. {@code CustomerLoader} writes the rows it is given and reads no
 *       clock.</li>
 * </ul>
 *
 * <p>The class carries no profile or condition: the bean exists in the web context, in the
 * {@code test} profile and in the {@code generator} profile, which runs with no web server. It is
 * found by component scanning from {@code CustomerMasterApplication}.
 *
 * <p><b>Fixed clocks in tests.</b> Spring Boot disables bean-definition overriding by default, so a
 * second bean named {@code clock} fails the context. A test that needs a fixed time declares a bean
 * under a different method name, such as {@code fixedClock()}, and marks it {@code @Primary}:
 * <pre>{@code
 * @TestConfiguration
 * static class FixedClockConfig {
 *     @Bean
 *     @Primary
 *     Clock fixedClock() {
 *         return Clock.fixed(Instant.parse("2026-10-05T14:03:09Z"), ZoneOffset.UTC);
 *     }
 * }
 * }</pre>
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    /**
     * The system clock in UTC. The bean name is {@code clock}.
     *
     * @return {@link Clock#systemUTC()}
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
