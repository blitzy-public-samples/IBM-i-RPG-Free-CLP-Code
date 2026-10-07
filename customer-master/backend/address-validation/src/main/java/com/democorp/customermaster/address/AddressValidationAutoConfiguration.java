package com.democorp.customermaster.address;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Registers exactly one {@link AddressValidationClient} bean, chosen by
 * {@code customer-master.address.client}, and binds {@link AddressValidationProperties}.
 *
 * <h2>Source replaced</h2>
 * On IBM i, callers reached the address service through the binding directory ADRVAL_BND,
 * created by {@code CRTBNDDIR BNDDIR(*CURLIB/ADRVAL_BND)} and filled by
 * {@code ADDBNDDIRE BNDDIR(ADRVAL_BND) OBJ((USADRVAL *SRVPGM *DEFER))}
 * [USPS_Address/CRTBNDDIR.CLLE:4-7]: the USADRVAL service program
 * [USPS_Address/USADRVAL.SQLRPGLE:49-139] was resolved when the caller was bound, and there
 * was only ever one implementation. Here bind-time resolution becomes dependency injection:
 * customer-api declares a Maven dependency on this module, and this auto-configuration
 * contributes the one client bean its {@code AddressStandardizationService} injects.
 *
 * <h2>Selection</h2>
 * <table>
 *   <caption>The client bean registered for each value of {@code customer-master.address.client}</caption>
 *   <tr><th>{@code customer-master.address.client} ({@code ADDRESS_VALIDATION_CLIENT})</th>
 *       <th>Bean registered</th></tr>
 *   <tr><td>{@code stub}, or the property absent</td>
 *       <td>{@link StubAddressValidationClient}: deterministic fixtures, no network. The
 *           default for local runs, Docker Compose and every test suite.</td></tr>
 *   <tr><td>{@code usps}, with {@code customer-master.address.usps.user-id} set</td>
 *       <td>{@link UspsWebToolsAddressValidationClient}: the USPS Web Tools {@code Verify}
 *           XML API at {@code customer-master.address.usps.base-url}.</td></tr>
 *   <tr><td>any value, when the context already holds an {@link AddressValidationClient}</td>
 *       <td>Neither. Both bean methods back off, so an application bean or a test double
 *           such as {@code @MockitoBean AddressValidationClient} replaces them entirely.</td></tr>
 * </table>
 *
 * <h2>Behaviour notes</h2>
 * <ul>
 *   <li><b>Case.</b> {@link ConditionalOnProperty#havingValue()} is compared with the
 *       configured text ignoring letter case only, so {@code STUB} and {@code USPS} select
 *       the same beans as {@code stub} and {@code usps}. {@link AddressValidationProperties}
 *       binds the selector with the same comparison, so a value that binds always matches
 *       exactly one condition.</li>
 *   <li><b>Unknown value.</b> Any other text matches neither condition: an unknown value
 *       such as {@code foo}, an empty value, or {@code stub} and {@code usps} written with
 *       surrounding blanks or separators, such as {@code " stub "} or {@code us-ps}.
 *       {@link AddressValidationProperties} is always bound here and rejects each of them
 *       with a message naming {@code customer-master.address.client}, never the value, so
 *       startup fails instead of running without a client.</li>
 *   <li><b>Missing USPS user id.</b> {@code client=usps} with a blank
 *       {@code customer-master.address.usps.user-id} fails startup while the properties are
 *       bound, before the client could be created (fail fast).</li>
 *   <li><b>Disabled standardization.</b> {@code customer-master.address.enabled=false} does
 *       not remove the client bean; customer-api reads
 *       {@link AddressValidationProperties#enabled()} and skips the call.</li>
 *   <li><b>No web condition.</b> The configuration is not limited to web applications. The
 *       generator's non-web context may create the stub, which is harmless: it reads its
 *       bundled fixtures once and opens no connection.</li>
 *   <li><b>Shutdown.</b> {@link UspsWebToolsAddressValidationClient} is
 *       {@link AutoCloseable}; Spring infers {@code close()} as its destroy method and shuts
 *       its HTTP client down when the context closes.</li>
 * </ul>
 *
 * <h2>Registration</h2>
 * The class is listed, by this fully qualified name, in
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
 * Its package lies under customer-api's component-scan root
 * {@code com.democorp.customermaster}, and {@code @SpringBootApplication}'s
 * {@code AutoConfigurationExcludeFilter} skips auto-configuration classes during that scan,
 * so the class is processed exactly once, with auto-configuration ordering and conditions.
 * For the same reason it carries {@link AutoConfiguration} only, never a
 * {@code Configuration}, {@code Component} or {@code ComponentScan} annotation, and none of
 * the classes it creates carries a stereotype annotation. It is also the only registrar of
 * {@link AddressValidationProperties}: the application declares no properties scan, so the
 * settings are bound and validated only in a context that contains this class.
 *
 * <p>Example, the selection as a test sees it:
 * <pre>{@code
 * new ApplicationContextRunner()
 *         .withConfiguration(AutoConfigurations.of(AddressValidationAutoConfiguration.class))
 *         .withPropertyValues("customer-master.address.client=usps",
 *                             "customer-master.address.usps.user-id=TESTUSER")
 *         .run(context -> assertThat(context)
 *                 .hasSingleBean(UspsWebToolsAddressValidationClient.class)
 *                 .doesNotHaveBean(StubAddressValidationClient.class));
 * }</pre>
 */
@AutoConfiguration
@EnableConfigurationProperties(AddressValidationProperties.class)
public class AddressValidationAutoConfiguration {

    /**
     * Creates the auto-configuration. Spring instantiates it; it holds no state.
     */
    public AddressValidationAutoConfiguration() {
        // Stateless configuration class: every bean is created by a factory method below.
    }

    /**
     * The real client for the USPS Web Tools {@code Verify} XML API, registered when
     * {@code customer-master.address.client} is {@code usps} and no other
     * {@link AddressValidationClient} bean exists.
     *
     * <p>USPS retired the Web Tools APIs on 2026-01-25. Complete the pre-enablement checks in
     * {@code customer-master/docs/developer-guide.md} before selecting this client.
     *
     * @param properties the bound module settings; their constructor has already rejected a
     *                   blank {@code usps.user-id}, a non-positive timeout and an invalid
     *                   base URL
     * @return the Web Tools client, closed by Spring when the context closes
     */
    @Bean
    @ConditionalOnProperty(prefix = AddressValidationProperties.PREFIX, name = "client", havingValue = "usps")
    @ConditionalOnMissingBean(AddressValidationClient.class)
    public UspsWebToolsAddressValidationClient uspsWebToolsAddressValidationClient(
            AddressValidationProperties properties) {
        return new UspsWebToolsAddressValidationClient(properties);
    }

    /**
     * The deterministic, offline stub, registered when {@code customer-master.address.client}
     * is {@code stub} or absent and no other {@link AddressValidationClient} bean exists.
     *
     * @return the stub over the fixtures bundled with this module
     * @throws IllegalStateException if the bundled fixtures are missing or invalid, which
     *                               fails startup
     */
    @Bean
    @ConditionalOnProperty(prefix = AddressValidationProperties.PREFIX, name = "client", havingValue = "stub",
            matchIfMissing = true)
    @ConditionalOnMissingBean(AddressValidationClient.class)
    public StubAddressValidationClient stubAddressValidationClient() {
        return new StubAddressValidationClient();
    }
}
