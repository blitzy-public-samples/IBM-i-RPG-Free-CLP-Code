package com.democorp.customermaster.repository;

import java.util.Collection;
import java.util.List;

import com.democorp.customermaster.domain.CustomerId;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration;

/**
 * Spring Data JDBC configuration of customer-api: registers the converters that store a
 * {@link CustomerId} in the {@code custmast.custid char(4)} column and read it back.
 *
 * <p>Replaces the CHAR(4) CUSTID handling of {@code 5250_Subfile/MTNCUSTR.SQLRPGLE}, where
 * {@code CUSTMAST_ds extname('CUSTMAST')} carries {@code CUSTID} as plain 4-character text
 * [5250_Subfile/MTNCUSTR.SQLRPGLE:61], {@code ReadRecd} selects by {@code CUSTID = :pID}
 * [5250_Subfile/MTNCUSTR.SQLRPGLE:316-333] and {@code AddRecd} inserts the record
 * [5250_Subfile/MTNCUSTR.SQLRPGLE:558-560]. The column is {@code CustID CHAR(4)} in
 * [5250_Subfile/Custmast2.sql:10] and {@code custid char(4)} in migration V3. In the target the
 * key is the {@link CustomerId} value type, and these converters keep it a 4-character string in
 * the database:
 * <ul>
 *   <li>writing: {@link CustomerId#toString()}, the 4 characters, for the {@code INSERT}, the
 *       {@code WHERE custid = ?} of {@code findById} and the versioned {@code UPDATE};</li>
 *   <li>reading: {@link CustomerId#parse(String)}, the shared codec, which rejects anything
 *       outside {@code ^[A-Z0-9]{4}$}. Nothing is trimmed or padded, because a {@code char(4)}
 *       value always reads back as exactly 4 characters and V3's {@code custmast_custid_ck}
 *       admits only that format.</li>
 * </ul>
 *
 * <p><b>Values only.</b> The converters map values and nothing else. Column names come from the
 * {@code @Column} annotations on {@code Customer}, {@code Address} and {@code State}, and the
 * default {@code NamingStrategy} stays in place: this class declares no naming strategy, mapping
 * context or converter bean of its own, and holds no schema name and no business rule. Base-36
 * parsing lives only in {@link CustomerId}.
 *
 * <p><b>Interaction with Spring Boot.</b> Boot's {@code JdbcRepositoriesAutoConfiguration} still
 * enables the JDBC repositories ({@code CustomerRepository}, {@code StateRepository}); only its
 * own {@code AbstractJdbcConfiguration} subclass backs off because this bean exists, so no
 * {@code @EnableJdbcRepositories} is declared here. The dialect is resolved from the connection
 * by {@link AbstractJdbcConfiguration#jdbcDialect}, which yields {@code JdbcPostgresDialect}. That
 * dialect already contributes the {@code java.sql.Timestamp} to {@code OffsetDateTime} reading
 * converter (at UTC) that {@code Customer.chgTime} over {@code timestamptz} needs, so no time
 * converter is added here.
 *
 * <p>Thread safety: the converters are stateless enum singletons.
 */
@Configuration(proxyBeanMethods = false)
public class JdbcConfig extends AbstractJdbcConfiguration {

    /**
     * Creates the configuration. Spring instantiates it; it holds no state of its own.
     */
    public JdbcConfig() {
        super();
    }

    /**
     * Names the package scanned for {@code @Table} entities that form the mapping context's
     * initial entity set.
     *
     * <p>The inherited default is this class's own package, {@code repository}, which holds no
     * entity. Boot's configuration, which backs off once this class exists, scanned the
     * application package instead. Naming the domain package keeps {@code Customer} and
     * {@code State} registered, and their mappings checked, when the context starts; it changes
     * no column name and no naming rule.
     *
     * @return the package of {@link CustomerId}, {@code com.democorp.customermaster.domain}
     */
    @Override
    protected Collection<String> getMappingBasePackages() {
        return List.of(CustomerId.class.getPackageName());
    }

    /**
     * Registers the {@link CustomerId} writing and reading converters. Spring Data JDBC adds them
     * to the dialect's store converters in its {@code JdbcCustomConversions} bean.
     *
     * @return the two converters, writing first
     */
    @Override
    protected List<?> userConverters() {
        return List.of(CustomerIdToStringConverter.INSTANCE, StringToCustomerIdConverter.INSTANCE);
    }

    /**
     * Writes a {@link CustomerId} as its 4-character text, the value bound to {@code custid}.
     * Spring Data never passes {@code null} to a converter.
     */
    @WritingConverter
    enum CustomerIdToStringConverter implements Converter<CustomerId, String> {

        /** The only instance. */
        INSTANCE;

        /**
         * Returns the stored form of the id.
         *
         * @param source the id
         * @return its 4 characters, for example {@code "EEEF"}
         */
        @Override
        public String convert(CustomerId source) {
            return source.toString();
        }
    }

    /**
     * Reads the 4-character text of {@code custid} as a {@link CustomerId} through the shared
     * codec. Spring Data never passes {@code null} to a converter.
     */
    @ReadingConverter
    enum StringToCustomerIdConverter implements Converter<String, CustomerId> {

        /** The only instance. */
        INSTANCE;

        /**
         * Parses the stored text, as read, without trimming or padding.
         *
         * @param source the column value, exactly 4 characters
         * @return the id
         * @throws IllegalArgumentException if {@code source} does not match
         *                                  {@code ^[A-Z0-9]{4}$}
         */
        @Override
        public CustomerId convert(String source) {
            return CustomerId.parse(source);
        }
    }
}
