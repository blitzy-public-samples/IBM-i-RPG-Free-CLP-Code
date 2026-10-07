package com.democorp.customermaster.config;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindHandlerAdvisor;
import org.springframework.boot.context.properties.bind.AbstractBindHandler;
import org.springframework.boot.context.properties.bind.BindContext;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.DataObjectPropertyName;
import org.springframework.boot.context.properties.bind.UnboundConfigurationPropertiesException;
import org.springframework.boot.context.properties.source.ConfigurationProperty;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.IterableConfigurationPropertySource;
import org.springframework.boot.context.properties.source.UnboundElementsSourceFilter;
import org.springframework.context.annotation.Role;
import org.springframework.stereotype.Component;

/**
 * Fails startup on a {@code customer-master.db.*} or {@code customer-master.search.*} setting that
 * {@link AppProperties} does not bind, such as the misspelt {@code customer-master.db.lock-timout},
 * which would otherwise leave the default in force without a word.
 *
 * <p><b>Scope.</b> The check covers only the subtrees {@code AppProperties} binds, one per record
 * component: today {@code customer-master.db} and {@code customer-master.search}. The rest of the
 * {@code customer-master} prefix stays lenient, because {@code customer-master.address.*},
 * {@code .security.*} and {@code .generator.*} belong to other properties classes, each bound only
 * in a context that holds its owner. Boot's own strict mode
 * ({@code @ConfigurationProperties(ignoreUnknownFields = false)}) checks the whole prefix and
 * would reject those siblings, which {@code AppProperties} never binds, so it is not used.
 *
 * <p><b>Sources.</b> Like Boot's strict mode, the check skips the {@code systemEnvironment} and
 * {@code systemProperties} property sources ({@link UnboundElementsSourceFilter}): a process
 * inherits variables and JVM properties it was never configured with, and environment-variable
 * names cannot be mapped back to one property name reliably. {@code application.yml}, its profile
 * files, command-line arguments and every other source are checked.
 *
 * <p><b>Failure.</b> The unbound settings are thrown as one
 * {@link UnboundConfigurationPropertiesException}, which Boot's failure analyzer reports with each
 * offending key, its value and its origin (file and line, or command-line argument), for example:
 * <pre>{@code
 * Binding to target [Bindable@... type = ...config.AppProperties, ...] failed:
 *
 *     Property: customer-master.db.lock-timout
 *     Value: "500ms"
 *     Origin: "customer-master.db.lock-timout" from property source "commandLineArgs"
 *     Reason: The elements [customer-master.db.lock-timout] were left unbound.
 * }</pre>
 * Unknown keys are reported before the bean-validation result of the same bind, because a
 * misspelt key is often what leaves a default that then fails validation.
 *
 * <p><b>Registration.</b> A component found by the component scan of
 * {@code CustomerMasterApplication}, so it is present in every context that registers
 * {@code AppProperties}, the generator's included. Boot reads the advisor beans once, at the first
 * configuration-properties bind of a context, so this bean depends on nothing and is an
 * infrastructure bean, which may be created before the bean post-processors without being reported
 * as ineligible for them. Each {@link #apply(BindHandler)} returns a fresh handler, because Boot
 * asks for one per bind, and the handler is inert unless the bind target is {@code AppProperties}.
 */
@Component
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
public class AppPropertiesUnknownKeyAdvisor implements ConfigurationPropertiesBindHandlerAdvisor {

    /**
     * Creates the advisor. It takes no collaborators, so Boot can create it at the first
     * configuration-properties bind of a context.
     */
    public AppPropertiesUnknownKeyAdvisor() {
        // Stateless: every bind gets its own handler from apply.
    }

    /**
     * Wraps the handler of one configuration-properties bind.
     *
     * @param bindHandler the handler Boot built for the bind
     * @return a new handler that checks the {@code AppProperties} subtrees and otherwise delegates
     */
    @Override
    public BindHandler apply(BindHandler bindHandler) {
        return new OwnedKeysBindHandler(bindHandler);
    }

    /**
     * Records every name bound under {@code AppProperties} and, when its root bind finishes,
     * rejects the names under its subtrees that no source value was bound to. Holds the state of
     * one bind, on one thread.
     */
    static final class OwnedKeysBindHandler extends AbstractBindHandler {

        /** Skips {@code systemEnvironment} and {@code systemProperties}, as Boot's strict mode. */
        private final Function<ConfigurationPropertySource, Boolean> sourceFilter =
                new UnboundElementsSourceFilter();

        /** Names the binder bound a value to, relaxed-form aware. */
        private final Set<ConfigurationPropertyName> boundNames = new HashSet<>();

        /** The subtrees checked; empty, so the handler is inert, unless binding AppProperties. */
        private List<ConfigurationPropertyName> ownedSubtrees = List.of();

        /**
         * Creates the handler.
         *
         * @param parent the handler Boot built for the bind
         */
        OwnedKeysBindHandler(BindHandler parent) {
            super(parent);
        }

        @Override
        public <T> Bindable<T> onStart(
                ConfigurationPropertyName name, Bindable<T> target, BindContext context) {
            if (context.getDepth() == 0) {
                boundNames.clear();
                ownedSubtrees = AppProperties.class.equals(target.getType().resolve())
                        ? subtreesOf(name)
                        : List.of();
            }
            return super.onStart(name, target, context);
        }

        @Override
        public Object onSuccess(
                ConfigurationPropertyName name, Bindable<?> target, BindContext context,
                Object result) {
            if (!ownedSubtrees.isEmpty()) {
                boundNames.add(name);
            }
            return super.onSuccess(name, target, context, result);
        }

        /**
         * Rethrows the unknown-key failure this handler raised, so no parent handler can absorb it;
         * any other failure goes to the parent.
         */
        @Override
        public Object onFailure(
                ConfigurationPropertyName name, Bindable<?> target, BindContext context,
                Exception error) throws Exception {
            if (!ownedSubtrees.isEmpty()
                    && error instanceof UnboundConfigurationPropertiesException) {
                throw error;
            }
            return super.onFailure(name, target, context, error);
        }

        @Override
        public void onFinish(
                ConfigurationPropertyName name, Bindable<?> target, BindContext context,
                Object result) throws Exception {
            if (context.getDepth() == 0 && !ownedSubtrees.isEmpty()) {
                rejectUnboundKeys(context);
            }
            super.onFinish(name, target, context, result);
        }

        /**
         * Collects, from every checked source, the names under an owned subtree that were not
         * bound, keeping the highest-precedence source of a name given twice.
         *
         * @param context the root bind's context
         * @throws UnboundConfigurationPropertiesException if any such name exists
         */
        private void rejectUnboundKeys(BindContext context) {
            Set<ConfigurationProperty> unbound = new TreeSet<>();
            for (ConfigurationPropertySource source : context.getSources()) {
                if (source instanceof IterableConfigurationPropertySource iterable
                        && Boolean.TRUE.equals(sourceFilter.apply(source))) {
                    for (ConfigurationPropertyName candidate : iterable) {
                        if (isUnboundOwnedName(candidate)) {
                            ConfigurationProperty property =
                                    iterable.getConfigurationProperty(candidate);
                            if (property != null) {
                                unbound.add(property);
                            }
                        }
                    }
                }
            }
            if (!unbound.isEmpty()) {
                throw new UnboundConfigurationPropertiesException(unbound);
            }
        }

        /**
         * Tells whether a source name lies under an owned subtree and received no binding.
         *
         * @param candidate a name a source holds
         * @return {@code true} for a key {@code AppProperties} should own but does not know
         */
        private boolean isUnboundOwnedName(ConfigurationPropertyName candidate) {
            if (boundNames.contains(candidate)) {
                return false;
            }
            for (ConfigurationPropertyName subtree : ownedSubtrees) {
                if (subtree.isAncestorOf(candidate)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Lists the subtrees {@code AppProperties} binds under its prefix, one per record component
         * in its dashed form: {@code customer-master.db} and {@code customer-master.search}.
         *
         * @param prefix the root name of the bind, {@code customer-master}
         * @return the subtree names
         */
        private static List<ConfigurationPropertyName> subtreesOf(
                ConfigurationPropertyName prefix) {
            return Arrays.stream(AppProperties.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .map(DataObjectPropertyName::toDashedForm)
                    .map(prefix::append)
                    .toList();
        }
    }
}
