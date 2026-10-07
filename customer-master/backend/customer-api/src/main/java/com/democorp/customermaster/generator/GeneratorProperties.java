package com.democorp.customermaster.generator;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
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
 * {@code '1001'}, always reads table {@code CSZ}, and draws from Db2 {@code RANDOM()}. Here:
 * <ul>
 *   <li>{@link #count()} is the row count, an integer in 1..{@value #MAX_COUNT}, defaulting to
 *       300, the size of the {@code Custmast.sql} seed. The source has no default because the CL
 *       parameter is required.</li>
 *   <li>{@link #startId()} overrides the first customer id. Null or empty, which is how the bridge
 *       binds an option that was not given, means "automatic": {@code CustomerGeneratorRunner}
 *       then starts at LOADCUSTR's {@code 1001}, or at {@code AAAA} when the count exceeds the
 *       385,245 ids that remain from {@code 1001}.</li>
 *   <li>{@link #cszFile()} names the city/state/ZIP CSV that replaces table {@code CSZ}; by default
 *       the bundled sample {@value #DEFAULT_CSZ_FILE}.</li>
 *   <li>{@link #seed()} makes the random data reproducible; null means an unseeded generator.</li>
 * </ul>
 *
 * <p><b>Binding contract.</b> The keys are filled by the option bridge in
 * {@code application-generator.yml}, because Spring Boot exposes {@code --count=N} as the flat
 * property {@code count}, not as {@code customer-master.generator.count}:
 * <pre>{@code
 * customer-master:
 *   generator:
 *     count: ${count:${GENERATOR_COUNT:300}}
 *     start-id: ${start-id:${GENERATOR_START_ID:}}
 *     csz-file: ${csz-file:${GENERATOR_CSZ_FILE:classpath:generator/csz-sample.csv}}
 *     seed: ${seed:${GENERATOR_SEED:}}
 * }</pre>
 * Precedence is therefore: command-line flag ({@code --count}, {@code --start-id},
 * {@code --csz-file}, {@code --seed}), then the {@code GENERATOR_COUNT}, {@code GENERATOR_START_ID},
 * {@code GENERATOR_CSZ_FILE} or {@code GENERATOR_SEED} environment variable, then the default. A
 * fully qualified {@code --customer-master.generator.count=N} also works, because command-line
 * properties outrank the profile file. An empty bridge value means "not given": {@code startId}
 * binds as {@code ""}, and {@code seed} binds as {@code null}, because Spring's String-to-Number
 * conversion maps an empty string to null. {@code CustomerGeneratorRunner} rejects every other
 * command-line option before any write.
 *
 * <p><b>Validation.</b> Validation is part of binding: a {@code count} outside
 * 1..{@value #MAX_COUNT}, a {@code startId} that is neither empty nor four characters of
 * {@code [A-Z0-9]} (for example a lower-case {@code b000}, or blanks only), or a {@code count} or
 * {@code seed} that is not a number fails context startup, and the generator process exits with a
 * non-zero status before it touches the database. Whether {@code startId} plus {@code count} fits
 * the id space is a cross-field rule that {@code CustomerGeneratorRunner} checks after binding.
 *
 * <p><b>Registration.</b> The record carries no stereotype annotation, and the application declares
 * no {@code @ConfigurationPropertiesScan}. {@code CustomerGeneratorRunner}, which exists only under
 * the {@code generator} profile, is its only registrar through
 * {@code @EnableConfigurationProperties(GeneratorProperties.class)}, so the web application and the
 * test contexts never bind or validate it.
 *
 * @param count   {@code customer-master.generator.count}: rows to generate, 1..{@value #MAX_COUNT};
 *                default 300
 * @param startId {@code customer-master.generator.start-id}: first customer id, four characters of
 *                {@code [A-Z0-9]}; null or empty selects the automatic start
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
}
