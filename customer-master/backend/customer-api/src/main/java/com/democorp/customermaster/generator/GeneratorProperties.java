package com.democorp.customermaster.generator;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindHandlerAdvisor;
import org.springframework.boot.context.properties.bind.AbstractBindHandler;
import org.springframework.boot.context.properties.bind.BindContext;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.validation.annotation.Validated;

/**
 * Typed options of the test-data generator, bound from {@value #PREFIX} and validated when the
 * {@code generator} profile context starts.
 *
 * <p><b>What it replaces.</b> LOADCUST2 receives the row count as {@code PARM(&NUM)}, declared
 * {@code TYPE(*DEC) LEN(15 5)}, and submits {@code CALL PGM(LOADCUSTR) PARM((&NUM))}
 * ({@code 5250_Subfile/LOADCUST2.CLLE}, lines 5-6 and 19). LOADCUSTR declares it as
 * {@code parm_recds packed(15 : 5)} and assigns it to {@code p_recds int(10)} before its
 * {@code for nRecds = 1 to p_recds} loop ({@code 5250_Subfile/LOADCUSTR.SQLRPGLE}, lines 21-24 and
 * 132-134). The source has no start id, file or seed parameter: it always numbers rows from
 * {@code '1001'}, always reads table {@code CSZ}, and draws from Db2 {@code RANDOM()}.
 *
 * <p><b>Binding.</b> The option bridge in {@code application-generator.yml} fills these keys,
 * because Spring Boot exposes {@code --count=N} as the flat property {@code count}. A command-line
 * flag ({@code --count}, {@code --start-id}, {@code --csz-file}, {@code --seed}) wins over its
 * {@code GENERATOR_*} environment variable, which wins over the default; a fully qualified
 * {@code --customer-master.generator.count=N} also works, because command-line properties outrank
 * the profile file. An empty value means "not given": {@code startId} binds as {@code ""},
 * {@code seed} as {@code null}, and an empty or whitespace-only {@code count} as the default
 * through {@code BlankCountAdvisor}.
 *
 * <p><b>Validation.</b> Binding validates the options: a value outside the ranges and formats
 * below, such as a {@code startId} of blanks only, or a non-blank {@code count} or a {@code seed}
 * that is not a number, fails context startup with a message naming the key, and the generator
 * process exits with a non-zero status before it touches the database. Whether {@code startId}
 * plus {@code count} fits the id space is a cross-field rule that {@code CustomerGeneratorRunner}
 * checks after binding.
 *
 * <p><b>Registration.</b> Only {@code CustomerGeneratorRunner}, which exists only under the
 * {@code generator} profile, registers the record, together with {@code BlankCountAdvisor}, so the
 * web application and the test contexts never bind it.
 *
 * @param count   {@code customer-master.generator.count}: rows to generate, 1..{@value #MAX_COUNT};
 *                default 300, the size of the {@code Custmast.sql} seed, also when the resolved
 *                value is empty or whitespace only
 * @param startId {@code customer-master.generator.start-id}: first customer id, four characters of
 *                {@code [A-Z0-9]}; null or empty selects the automatic start that
 *                {@code CustomerGeneratorRunner} chooses
 * @param cszFile {@code customer-master.generator.csz-file}: location of the city/state/ZIP CSV,
 *                a {@code classpath:} resource or a file such as the mounted {@code /data/csz.csv},
 *                resolved by {@code CszSource}; default {@value #DEFAULT_CSZ_FILE}
 * @param seed    {@code customer-master.generator.seed}: seed of the random generator; null for an
 *                unseeded run
 */
@Validated
@ConfigurationProperties(GeneratorProperties.PREFIX)
public record GeneratorProperties(
        @Min(1) @Max(GeneratorProperties.MAX_COUNT) @DefaultValue("300") int count,
        @Pattern(regexp = "^$|^[A-Z0-9]{4}$") String startId,
        @DefaultValue(GeneratorProperties.DEFAULT_CSZ_FILE) String cszFile,
        Long seed) {

    /** Configuration prefix of the generator options. */
    public static final String PREFIX = "customer-master.generator";

    /**
     * The bundled city/state/ZIP sample, {@code src/main/resources/generator/csz-sample.csv}. It
     * equals the default of the {@code csz-file} bridge in {@code application-generator.yml}.
     */
    public static final String DEFAULT_CSZ_FILE = "classpath:generator/csz-sample.csv";

    /**
     * The largest row count: the size of the 4-character base-36 id space, 36<sup>4</sup>
     * ({@code CustomerId.CAPACITY}). A load of this size must start at {@code AAAA}.
     */
    public static final int MAX_COUNT = 1_679_616;

    /**
     * Whether an explicit start id was given.
     *
     * @return {@code true} when {@link #startId()} is non-null and not blank; {@code false} selects
     *         the automatic start
     */
    public boolean hasStartId() {
        return startId != null && !startId.isBlank();
    }

    /**
     * The resource location of the city/state/ZIP CSV to read.
     *
     * @return {@link #DEFAULT_CSZ_FILE} when {@link #cszFile()} is null or blank, otherwise
     *         {@code cszFile} with surrounding whitespace removed
     */
    public String cszLocation() {
        if (cszFile == null || cszFile.isBlank()) {
            return DEFAULT_CSZ_FILE;
        }
        return cszFile.trim();
    }

    /**
     * Makes a blank {@code customer-master.generator.count} mean "not given", so that the record's
     * {@code @DefaultValue("300")} applies.
     *
     * <p><b>Why.</b> The bridge {@code ${count:${GENERATOR_COUNT:300}}} falls back only when a name
     * is absent, so a present but empty {@code GENERATOR_COUNT} (or {@code --count=}) resolves to
     * {@code ""}. Spring's String-to-Number conversion maps that to null, and a null cannot be
     * assigned to the primitive {@code int count}, so binding would fail before the constructor
     * default is reached.
     *
     * <p><b>How.</b> The handler acts in {@code onStart} of that one key only. It binds the key as
     * a {@code String} through {@link BindContext#getBinder()}, that is with the binder's own
     * property sources, precedence and placeholder resolution, so it sees exactly the value the
     * count would be converted from (the raw configuration property still holds the unresolved
     * bridge). When that value is empty or whitespace only it returns null, which binds nothing,
     * and the value-object binder then uses the default. Every other value, key and properties
     * class binds unchanged: a non-blank {@code abc} still fails conversion, and {@code 0} or
     * {@code 1679617} still fail validation.
     *
     * <p><b>Registration.</b> {@code CustomerGeneratorRunner} imports this class next to its
     * {@code @EnableConfigurationProperties(GeneratorProperties.class)}, so the advisor exists only
     * in the {@code generator} profile context, the only one that binds this record. It is a
     * dependency-free class rather than a {@code @Bean} method of the runner, because the runner
     * itself needs the bound record, while advisors are looked up as soon as the context binds its
     * first properties class.
     */
    static final class BlankCountAdvisor implements ConfigurationPropertiesBindHandlerAdvisor {

        /** The one key this advisor handles. */
        static final ConfigurationPropertyName COUNT =
                ConfigurationPropertyName.of(PREFIX + ".count");

        @Override
        public BindHandler apply(BindHandler bindHandler) {
            return new BlankCountBindHandler(bindHandler);
        }

        /** Skips {@link #COUNT} when its resolved value is blank and delegates everything else. */
        private static final class BlankCountBindHandler extends AbstractBindHandler {

            BlankCountBindHandler(BindHandler parent) {
                super(parent);
            }

            @Override
            public <T> Bindable<T> onStart(ConfigurationPropertyName name, Bindable<T> target,
                    BindContext context) {
                if (COUNT.equals(name) && isBlank(name, context)) {
                    return null;
                }
                return super.onStart(name, target, context);
            }

            private static boolean isBlank(ConfigurationPropertyName name, BindContext context) {
                BindResult<String> resolved =
                        context.getBinder().bind(name, Bindable.of(String.class));
                return resolved.isBound() && resolved.get().isBlank();
            }
        }
    }
}
