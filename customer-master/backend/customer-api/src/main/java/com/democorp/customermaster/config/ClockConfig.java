package com.democorp.customermaster.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the application's one {@link Clock}, in UTC: the source of the {@code chgtime} stamp of
 * every API add and update and of every generated row. The column is {@code timestamptz(6)} and
 * holds an instant, which the browser shows in its own local time; the source stored local time
 * with no zone.
 *
 * <p><b>Stamps it supplies.</b> {@code CustomerMaintenanceService} stamps an add or update in the
 * same INSERT or UPDATE as the data, and {@code CustomerGeneratorRunner} stamps the load time on
 * every generated row, in place of the AddRecd and UpdateRecd stamps
 * [5250_Subfile/MTNCUSTR.SQLRPGLE:556,587] and LOADCUSTR's cleared one
 * [5250_Subfile/LOADCUSTR.SQLRPGLE:135,200]. The database's {@code CURRENT_TIMESTAMP}, not this
 * clock, supplies the V3 {@code chgtime} column default and the stamps of the V5 seed rows.
 *
 * <p><b>Rule for production code.</b> A timestamp is taken as {@code OffsetDateTime.now(clock)},
 * never from an argument-less {@code java.time} {@code now()} or a clock the caller builds itself.
 *
 * <p>The class carries no profile or condition, so the bean exists in the web, test and generator
 * contexts. A unit test passes {@code Clock.fixed(...)} to the consumer's constructor. A Spring
 * test context adds a {@code @Primary} {@code Clock} bean under another method name, such as
 * {@code fixedClock()}, because Spring Boot disables bean-definition overriding and a second bean
 * named {@code clock} fails the context.
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
