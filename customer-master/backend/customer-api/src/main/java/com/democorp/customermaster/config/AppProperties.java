package com.democorp.customermaster.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Typed {@code customer-master.*} settings for search, maintenance and their transactions,
 * validated when the application starts.
 *
 * <p>Registered only by {@code CustomerMasterApplication} through
 * {@code @EnableConfigurationProperties}. The {@code customer-master.security.*},
 * {@code customer-master.generator.*} and {@code customer-master.address.*} settings belong to
 * {@code UsersProperties}, {@code GeneratorProperties} and {@code AddressValidationProperties}.
 * The prefix stays lenient for those siblings, but a key under {@code customer-master.db} or
 * {@code customer-master.search} that this class does not bind, such as a misspelt
 * {@code db.lock-timout}, fails startup naming the key ({@link AppPropertiesUnknownKeyAdvisor}).
 *
 * <p>The values replace these IBM i constants and conditions:
 * <ul>
 *   <li>{@code search.default-size} (12) replaces PMTCUSTR's subfile page size,
 *       {@code SFLPAGESIZE 12}.</li>
 *   <li>{@code search.max-rows} (9999) replaces PMTCUSTR's subfile cap, {@code MAXSFLRECDS 9999},
 *       which ends the list with message DEM0006.</li>
 *   <li>{@code db.lock-timeout} (5s) bounds the lock wait of an add or update, which then fails
 *       with DEM1001, the message MTNCUSTR sends for its row-lock condition (SQLSTATE 57033).</li>
 * </ul>
 * {@code search.max-size} (100) has no source counterpart: it is the largest page a client may
 * request.
 *
 * <p>{@code CustomerMaintenanceService} reads {@link #db()} for its add and update transactions,
 * and {@code CustomerSearchService} reads {@link #search()}. The generator's {@code CustomerLoader}
 * keeps its own fixed lock timeout and does not read this class.
 *
 * @param db     database settings ({@code customer-master.db.*})
 * @param search customer search settings ({@code customer-master.search.*})
 */
@Validated
@ConfigurationProperties("customer-master")
public record AppProperties(@DefaultValue @Valid Db db, @DefaultValue @Valid Search search) {

    /**
     * Database settings.
     *
     * @param lockTimeout {@code customer-master.db.lock-timeout} ({@code DB_LOCK_TIMEOUT}): the
     *                    {@code SET LOCAL lock_timeout} of add and update transactions; a lock
     *                    wait that exceeds it becomes 409 DEM1001
     */
    public record Db(@DefaultValue("5s") @NotNull Duration lockTimeout) {
    }

    /**
     * Customer search settings.
     *
     * <p>Validated when the application starts, so {@code CustomerSearchService} relies on them
     * without further checks:
     * <ul>
     *   <li>every value is at least 1;</li>
     *   <li>{@code default-size} is not greater than {@code max-size}, so a request without
     *       {@code size} gets a page that an explicit {@code size} could also ask for
     *       ({@link #isDefaultSizeWithinMaxSize()});</li>
     *   <li>{@code max-size} is at most {@code Integer.MAX_VALUE - 1} (2147483646), so the
     *       look-ahead {@code LIMIT size + 1} of every page stays within an {@code int}.</li>
     * </ul>
     *
     * @param defaultSize {@code customer-master.search.default-size}: rows per page when the
     *                    request gives no {@code size} (PMTCUSTR {@code SFLPAGESIZE}); 1 to
     *                    {@code max-size}
     * @param maxSize     {@code customer-master.search.max-size}: the largest {@code size} a
     *                    request may ask for; 1 to 2147483646
     * @param maxRows     {@code customer-master.search.max-rows}: rows served across all pages of
     *                    one search before it stops with DEM0006 (PMTCUSTR {@code MAXSFLRECDS})
     */
    public record Search(
            @DefaultValue("12") @Min(1) int defaultSize,
            @DefaultValue("100") @Min(1) @Max(Integer.MAX_VALUE - 1) int maxSize,
            @DefaultValue("9999") @Min(1) int maxRows) {

        /**
         * Checks that the default page size is one an explicit {@code size} could also ask for.
         * The message names both settings and neither value.
         *
         * @return {@code true} when {@code defaultSize <= maxSize}
         */
        @AssertTrue(message = "customer-master.search.default-size must not be greater than "
                + "customer-master.search.max-size")
        public boolean isDefaultSizeWithinMaxSize() {
            return defaultSize <= maxSize;
        }
    }
}
