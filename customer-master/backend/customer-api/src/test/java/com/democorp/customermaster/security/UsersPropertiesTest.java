package com.democorp.customermaster.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;

/**
 * Specifies the binding-time validation of {@link UsersProperties}, the HTTP Basic users bound from
 * {@code customer-master.security.users[]}.
 *
 * <p><b>Password byte limit.</b> {@code SecurityConfig} BCrypt-encodes every password at startup, and
 * Spring Security's {@code BCrypt.hashpw} rejects a password longer than
 * {@value UsersProperties#MAX_PASSWORD_BYTES} UTF-8 bytes with an exception that names no setting.
 * Binding therefore rejects such a password first, through the derived property
 * {@code passwordWithinEncoderLimit}, so the failure names the indexed user
 * {@code customer-master.security.users[i]} and reports the rejected value {@code false}. Neither the
 * failure, nor any message in its cause chain, nor its printed stack trace contains the password. The
 * limit counts bytes, not characters: 24 euro signs (3 bytes each) bind, 25 do not. The encoder test
 * uses {@link SecurityConfig#passwordEncoder()} itself, so it proves the constant is the limit of the
 * encoder and cost factor the application runs with.
 *
 * <p><b>Rules kept.</b> A missing or blank password, the username length, pattern and
 * {@value UsersProperties#SYSTEM_USER} reservation, and case-insensitive uniqueness fail exactly as
 * before, on their own properties and never on the byte limit.
 *
 * <p>The runner registers {@link UsersProperties} through {@code @EnableConfigurationProperties}, as
 * {@code SecurityConfig} does, with {@link ValidationAutoConfiguration}, and binds two users in the
 * order {@code application.yml} declares them: {@code users[0]} the inquiry user, {@code users[1]} the
 * maintenance user whose password each case varies. Pure JUnit 5, AssertJ and
 * {@link ApplicationContextRunner}: no web server, no database, no Docker. Every password is a
 * fictitious repeated character.
 */
@DisplayName("UsersProperties: users validated when the configuration is bound")
final class UsersPropertiesTest {

    /** Prefix of the bound users list. */
    private static final String USERS = UsersProperties.PREFIX + ".users";

    /** Index of the maintenance user, whose settings each case varies. */
    private static final int MAINTENANCE_INDEX = 1;

    /** Indexed key of the maintenance user, as a binding failure names it. */
    private static final String MAINTENANCE_KEY = USERS + "[" + MAINTENANCE_INDEX + "]";

    /** Field path of the maintenance user's byte-limit check, relative to the prefix. */
    private static final String LIMIT_FIELD = "users[" + MAINTENANCE_INDEX + "].passwordWithinEncoderLimit";

    /** Field path of the maintenance user's password, relative to the prefix. */
    private static final String PASSWORD_FIELD = "users[" + MAINTENANCE_INDEX + "].password";

    /** Field path of the maintenance user's username, relative to the prefix. */
    private static final String USERNAME_FIELD = "users[" + MAINTENANCE_INDEX + "].username";

    /** Field path of the maintenance user's system-stamp check, relative to the prefix. */
    private static final String NOT_SYSTEM_USER_FIELD = "users[" + MAINTENANCE_INDEX + "].notSystemUser";

    /** Field path of the list-wide uniqueness check, relative to the prefix. */
    private static final String UNIQUE_FIELD = "usernamesUnique";

    /** Reason a binding failure gives for an over-long password. */
    private static final String LIMIT_MESSAGE =
            "password must be at most 72 bytes in UTF-8, the most BCrypt encodes";

    /** The euro sign, three bytes in UTF-8. */
    private static final String EURO = "\u20AC";

    /** Exactly {@value UsersProperties#MAX_PASSWORD_BYTES} ASCII bytes. */
    private static final String ASCII_72 = "a".repeat(72);

    /** One ASCII byte over the limit. */
    private static final String ASCII_73 = "a".repeat(73);

    /** 24 characters, exactly {@value UsersProperties#MAX_PASSWORD_BYTES} UTF-8 bytes. */
    private static final String EURO_24 = EURO.repeat(24);

    /** 25 characters, 75 UTF-8 bytes: within any character limit, over the byte limit. */
    private static final String EURO_25 = EURO.repeat(25);

    /** Binds and validates {@link UsersProperties} alone, with the application's validator. */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(UsersPropertiesRegistrar.class);

    /**
     * Registers {@link UsersProperties} the way {@code SecurityConfig} does, only where a runner names
     * it through {@code withUserConfiguration}.
     *
     * <p>It carries no stereotype: test classes are on the classpath when an application component
     * scan runs without a test-type exclude filter, and a scanned registrar would bind and validate the
     * users in a context that holds no {@code SecurityConfig}, such as the generator's.
     */
    @EnableConfigurationProperties(UsersProperties.class)
    static class UsersPropertiesRegistrar {
    }

    @Nested
    @DisplayName("Password byte limit: at most 72 UTF-8 bytes, reported without the password")
    class PasswordByteLimit {

        @Test
        @DisplayName("72 ASCII bytes bind")
        void seventyTwoAsciiBytesBind() {
            assertBinds(ASCII_72);
        }

        @Test
        @DisplayName("24 euro signs, 72 UTF-8 bytes, bind")
        void twentyFourEuroSignsBind() {
            assertThat(EURO_24).hasSize(24);
            assertThat(EURO_24.getBytes(StandardCharsets.UTF_8)).hasSize(UsersProperties.MAX_PASSWORD_BYTES);
            assertBinds(EURO_24);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.democorp.customermaster.security.UsersPropertiesTest#overLimitPasswords")
        @DisplayName("an over-limit password fails startup on users[1], with the value false and the 72-byte reason")
        void overLimitPasswordFailsNamingTheIndexedUser(String description, String password) {
            runner.withPropertyValues(users("sales", password)).run(context -> {
                BindValidationException failure = bindValidationFailure(context);
                List<FieldError> errors = fieldErrors(failure);
                assertThat(errors).singleElement().satisfies(error -> {
                    assertThat(error.getObjectName()).isEqualTo(UsersProperties.PREFIX);
                    assertThat(error.getField()).isEqualTo(LIMIT_FIELD);
                    assertThat(error.getObjectName() + "." + error.getField()).startsWith(MAINTENANCE_KEY + ".");
                    assertThat(error.getRejectedValue()).isEqualTo(Boolean.FALSE);
                    assertThat(error.getDefaultMessage()).isEqualTo(LIMIT_MESSAGE);
                });
                assertThat(failure.getMessage()).contains(LIMIT_FIELD).contains(LIMIT_MESSAGE);
            });
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.democorp.customermaster.security.UsersPropertiesTest#overLimitPasswords")
        @DisplayName("no error, message or stack trace of the failure contains the password")
        void failureNeverContainsThePassword(String description, String password) {
            runner.withPropertyValues(users("sales", password)).run(context -> {
                assertThat(context).hasFailed();
                Throwable failure = context.getStartupFailure();
                assertThat(stackTraceOf(failure)).contains(LIMIT_FIELD).doesNotContain(password);
                for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                    assertThat(String.valueOf(cause.getMessage())).doesNotContain(password);
                }
                for (ObjectError error : bindValidationFailure(context).getValidationErrors().getAllErrors()) {
                    assertThat(error.toString()).doesNotContain(password);
                    assertThat(String.valueOf(error.getDefaultMessage())).doesNotContain(password);
                    if (error instanceof FieldError fieldError) {
                        assertThat(String.valueOf(fieldError.getRejectedValue())).doesNotContain(password);
                    }
                }
            });
        }

        @Test
        @DisplayName("a missing password passes the byte check, which leaves it to @NotBlank")
        void missingPasswordPassesTheByteCheck() {
            assertThat(new UsersProperties.User("sales", null, Role.MAINTENANCE).isPasswordWithinEncoderLimit())
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("Encoder: the limit is the one SecurityConfig's BCrypt encoder enforces")
    class EncoderLimit {

        /** The encoder bean the application declares, at its own cost factor. */
        private final PasswordEncoder encoder = new SecurityConfig().passwordEncoder();

        @Test
        @DisplayName("72 ASCII bytes and 24 euro signs encode and verify")
        void encoderAcceptsTheLimit() {
            for (String password : List.of(ASCII_72, EURO_24)) {
                assertThatCode(() -> encoder.encode(password)).doesNotThrowAnyException();
                assertThat(encoder.matches(password, encoder.encode(password))).isTrue();
            }
        }

        @Test
        @DisplayName("one byte more is rejected by the encoder, so the binding limit is exact")
        void encoderRejectsOneByteMore() {
            assertThatIllegalArgumentException().isThrownBy(() -> encoder.encode(ASCII_73))
                    .withMessage("password cannot be more than 72 bytes");
            assertThatIllegalArgumentException().isThrownBy(() -> encoder.encode(EURO_25))
                    .withMessage("password cannot be more than 72 bytes");
        }
    }

    @Nested
    @DisplayName("Rules kept: blank passwords and username rules fail on their own properties")
    class ExistingRules {

        @Test
        @DisplayName("a missing password fails on users[1].password only")
        void missingPasswordFailsOnPassword() {
            runner.withPropertyValues(
                    USERS + "[0].username=inquiry",
                    USERS + "[0].password=inquiry-demo",
                    USERS + "[0].role=INQUIRY",
                    MAINTENANCE_KEY + ".username=sales",
                    MAINTENANCE_KEY + ".role=MAINTENANCE").run(context ->
                    assertThat(failingFields(context)).containsExactly(PASSWORD_FIELD));
        }

        @Test
        @DisplayName("a blank password fails on users[1].password only")
        void blankPasswordFailsOnPassword() {
            runner.withPropertyValues(users("sales", "")).run(context ->
                    assertThat(failingFields(context)).containsExactly(PASSWORD_FIELD));
        }

        @ParameterizedTest(name = "username \"{0}\"")
        @MethodSource("com.democorp.customermaster.security.UsersPropertiesTest#invalidUsernames")
        @DisplayName("an invalid username fails on its own rules, never on the byte limit")
        void invalidUsernameFailsOnItsOwnRules(String username, Set<String> expectedFields) {
            runner.withPropertyValues(users(username, "sales-demo")).run(context ->
                    assertThat(failingFields(context)).isEqualTo(expectedFields));
        }

        @Test
        @DisplayName("usernames equal ignoring case fail on usernamesUnique")
        void duplicateUsernamesFailOnUniqueness() {
            runner.withPropertyValues(users("INQUIRY", "sales-demo")).run(context ->
                    assertThat(failingFields(context)).containsExactly(UNIQUE_FIELD));
        }
    }

    /**
     * Over-limit passwords: ASCII one byte over, and few characters but many bytes.
     *
     * @return the description and the password of each case
     */
    static Stream<Arguments> overLimitPasswords() {
        return Stream.of(
                Arguments.of("73 ASCII bytes", ASCII_73),
                Arguments.of("25 euro signs, 75 UTF-8 bytes", EURO_25));
    }

    /**
     * Usernames each existing rule rejects, with the fields their failure names.
     *
     * @return the username and the expected failing fields of each case
     */
    static Stream<Arguments> invalidUsernames() {
        return Stream.of(
                Arguments.of("s".repeat(UsersProperties.MAX_USERNAME_LENGTH + 1), Set.of(USERNAME_FIELD)),
                Arguments.of("sa les", Set.of(USERNAME_FIELD)),
                Arguments.of(UsersProperties.SYSTEM_USER, Set.of(USERNAME_FIELD, NOT_SYSTEM_USER_FIELD)),
                Arguments.of("*system*", Set.of(USERNAME_FIELD, NOT_SYSTEM_USER_FIELD)));
    }

    /**
     * The two users {@code application.yml} declares: the inquiry user, then a maintenance user with the
     * given username and password.
     *
     * @param maintenanceUsername the username of {@code users[1]}
     * @param maintenancePassword the password of {@code users[1]}
     * @return the property values binding both users
     */
    private static String[] users(String maintenanceUsername, String maintenancePassword) {
        return new String[] {
                USERS + "[0].username=inquiry",
                USERS + "[0].password=inquiry-demo",
                USERS + "[0].role=INQUIRY",
                MAINTENANCE_KEY + ".username=" + maintenanceUsername,
                MAINTENANCE_KEY + ".password=" + maintenancePassword,
                MAINTENANCE_KEY + ".role=MAINTENANCE"};
    }

    /**
     * Asserts that the users bind with the maintenance password unchanged.
     *
     * @param password the password of {@code users[1]}
     */
    private void assertBinds(String password) {
        runner.withPropertyValues(users("sales", password)).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(UsersProperties.class);
            UsersProperties.User maintenance =
                    context.getBean(UsersProperties.class).users().get(MAINTENANCE_INDEX);
            assertThat(maintenance.password()).isEqualTo(password);
            assertThat(maintenance.isPasswordWithinEncoderLimit()).isTrue();
        });
    }

    /**
     * The {@link BindValidationException} in the cause chain of the context's startup failure.
     *
     * @param context a context that must have failed to start
     * @return the binding validation failure
     */
    private static BindValidationException bindValidationFailure(AssertableApplicationContext context) {
        assertThat(context).hasFailed();
        for (Throwable cause = context.getStartupFailure(); cause != null; cause = cause.getCause()) {
            if (cause instanceof BindValidationException bindValidationException) {
                return bindValidationException;
            }
        }
        throw new AssertionError("No BindValidationException in the startup failure",
                context.getStartupFailure());
    }

    /**
     * Every field error of the binding failure, in report order.
     *
     * @param failure the binding validation failure
     * @return its field errors
     */
    private static List<FieldError> fieldErrors(BindValidationException failure) {
        return failure.getValidationErrors().getAllErrors().stream()
                .filter(FieldError.class::isInstance)
                .map(FieldError.class::cast)
                .toList();
    }

    /**
     * The distinct fields the binding failure of the context names.
     *
     * @param context a context that must have failed to start
     * @return the field paths, relative to {@value UsersProperties#PREFIX}
     */
    private static Set<String> failingFields(AssertableApplicationContext context) {
        BindValidationException failure = bindValidationFailure(context);
        assertThat(failure.getValidationErrors().getAllErrors()).allMatch(FieldError.class::isInstance);
        return fieldErrors(failure).stream().map(FieldError::getField).collect(Collectors.toSet());
    }

    /**
     * The printed stack trace of a failure, every cause included.
     *
     * @param failure the failure to print
     * @return the text {@link Throwable#printStackTrace()} would print
     */
    private static String stackTraceOf(Throwable failure) {
        StringWriter text = new StringWriter();
        failure.printStackTrace(new PrintWriter(text));
        return text.toString();
    }
}
