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
 * <p>Replaces the binding directory ADRVAL_BND [USPS_Address/CRTBNDDIR.CLLE:4-7], through
 * which callers were bound to the one USADRVAL service program
 * [USPS_Address/USADRVAL.SQLRPGLE:49-139]: customer-api depends on this module, and its
 * {@code AddressStandardizationService} injects the bean registered here.
 *
 * <p><b>Selection.</b> Each bean method below states the selector value it applies to. Both
 * back off when the context already holds an {@link AddressValidationClient}, so an
 * application bean or a test double such as {@code @MockitoBean AddressValidationClient}
 * replaces them entirely. {@link AddressValidationProperties} is always bound here and
 * accepts only selector text that matches exactly one {@link ConditionalOnProperty}
 * condition, as {@link AddressValidationProperties.Client} states, so any other value fails
 * startup instead of leaving the context without a client. {@code client=usps} with a
 * blank {@code customer-master.address.usps.user-id} also fails startup, while the
 * properties are bound and before the client is created. With
 * {@code customer-master.address.enabled=false} the bean stays registered and customer-api
 * skips the call. There is no web condition: the generator's non-web context may create
 * the stub, which only reads its bundled fixtures and opens no connection.
 *
 * <p><b>Registration.</b> The class is listed in
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
 * Its package lies under customer-api's component-scan root, and
 * {@code AutoConfigurationExcludeFilter} skips auto-configuration classes during that scan,
 * so the class is processed exactly once, with auto-configuration ordering and conditions.
 * It therefore carries {@link AutoConfiguration} only, never a {@code Configuration},
 * {@code Component} or {@code ComponentScan} annotation, and no other class of this module
 * carries a stereotype annotation. It is also the only registrar of
 * {@link AddressValidationProperties}, so the settings are bound and validated only in a
 * context that contains this class.
 */
@AutoConfiguration
@EnableConfigurationProperties(AddressValidationProperties.class)
public class AddressValidationAutoConfiguration {

    /**
     * Creates the auto-configuration. Spring instantiates it; it holds no state.
     */
    public AddressValidationAutoConfiguration() {
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
