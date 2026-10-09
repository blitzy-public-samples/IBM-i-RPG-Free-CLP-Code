package com.democorp.customermaster.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The HTTP Basic users of the Customer Master API, bound from {@value #PREFIX} and validated when
 * the web application starts. Each user holds exactly one {@link Role}, whose documentation records how
 * the source's modes became roles, and its username is the change stamp {@link CurrentUser#name()}
 * reports.
 *
 * <p><b>Binding.</b> {@code application.yml} declares one inquiry and one maintenance user whose
 * username and password come from the {@code CM_INQUIRY_*} and {@code CM_MAINTENANCE_*} environment
 * variables with empty defaults, so the web application refuses to start until they are supplied.
 * This record carries no default user and no password; local values come from Compose or from the
 * test profile.
 *
 * <p><b>Guards.</b> Any violation fails startup with a {@code BindValidationException} naming the
 * property:
 * <ul>
 *   <li>at least one user ({@code @NotEmpty});</li>
 *   <li>a username of 1 to {@value #MAX_USERNAME_LENGTH} characters, the width of
 *       {@code custmast.chguser varchar(18)} ({@code @Size});</li>
 *   <li>a username matching {@value #USERNAME_PATTERN} ({@code @Pattern});</li>
 *   <li>no username {@value #SYSTEM_USER} ({@link User#isNotSystemUser()}), stated explicitly although
 *       the pattern already excludes {@code *}, so no signed-in user can make a change look like a
 *       system load;</li>
 *   <li>usernames unique, ignoring case ({@link #isUsernamesUnique()});</li>
 *   <li>a non-blank password and a role ({@code @NotBlank}, {@code @NotNull}; an unknown role name
 *       already fails enum conversion);</li>
 *   <li>a password of at most {@value #MAX_PASSWORD_BYTES} UTF-8 bytes, checked here so an over-long
 *       one fails naming its setting rather than later inside {@code SecurityConfig}
 *       ({@link User#isPasswordWithinEncoderLimit()}).</li>
 * </ul>
 *
 * <p><b>Passwords stay private.</b> A failed length check reports the value {@code false}, never the
 * password, and {@link User#toString()} masks it, so binding-failure reports and log lines never print
 * it. {@code SecurityConfig} BCrypt-encodes each password once, at startup.
 *
 * <p><b>Registration.</b> The record carries no stereotype annotation, and the application declares
 * no {@code @ConfigurationPropertiesScan}. {@code SecurityConfig}, which exists only in a servlet web
 * application, is its only registrar, so the non-web generator profile never binds or validates it
 * and invalid {@code customer-master.security.*} values cannot stop a data load. No class outside that
 * conditional configuration may depend on it.
 *
 * @param users the configured users, in configuration order; {@code null} only when none is bound,
 *              which validation reports. A bound list is copied and unmodifiable.
 */
@Validated
@ConfigurationProperties(UsersProperties.PREFIX)
public record UsersProperties(@NotEmpty @Valid List<@NotNull User> users) {

    /** Configuration prefix of the users list. */
    public static final String PREFIX = "customer-master.security";

    /** Width of {@code custmast.chguser}, which receives the authenticated username. */
    public static final int MAX_USERNAME_LENGTH = 18;

    /** Characters a username may contain. */
    public static final String USERNAME_PATTERN = "^[A-Za-z0-9._-]+$";

    /** Change stamp reserved for the seed rows and the test-data generator. */
    public static final String SYSTEM_USER = "*SYSTEM*";

    /**
     * Longest password BCrypt encodes, in UTF-8 bytes. Spring Security's {@code BCrypt.hashpw}
     * rejects a longer one with {@code "password cannot be more than 72 bytes"}, so a character
     * outside ASCII counts for two to four of these bytes.
     */
    public static final int MAX_PASSWORD_BYTES = 72;

    /**
     * Copies the bound list into an unmodifiable one.
     *
     * <p>A {@code null} list stays {@code null}, so {@code @NotEmpty} reports it, and a {@code null}
     * element is kept, so the element constraint reports it instead of the copy failing with a
     * {@link NullPointerException}.
     */
    public UsersProperties {
        users = users == null ? null : Collections.unmodifiableList(new ArrayList<>(users));
    }

    /**
     * Whether no two users share a username, ignoring case.
     *
     * <p>Validated as the bean property {@code usernamesUnique}. Missing users and missing usernames
     * are skipped, because their own constraints report them. Lower-casing uses {@link Locale#ROOT};
     * usernames are ASCII by {@value #USERNAME_PATTERN}, so every pair the in-memory user store would
     * treat as one name is caught here.
     *
     * @return {@code true} when every configured username is distinct ignoring case
     */
    @AssertTrue(message = "usernames must be unique")
    public boolean isUsernamesUnique() {
        if (users == null) {
            return true;
        }
        Set<String> seen = new HashSet<>();
        for (User user : users) {
            if (user == null || user.username() == null) {
                continue;
            }
            if (!seen.add(user.username().toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    /**
     * One configured user.
     *
     * @param username the sign-in name and the value stamped into {@code chguser}: 1 to
     *                 {@value UsersProperties#MAX_USERNAME_LENGTH} characters matching
     *                 {@value UsersProperties#USERNAME_PATTERN}, never
     *                 {@value UsersProperties#SYSTEM_USER}
     * @param password the password as configured; non-blank, at most
     *                 {@value UsersProperties#MAX_PASSWORD_BYTES} bytes in UTF-8, encoded by
     *                 {@code SecurityConfig} at startup and never printed by {@link #toString()}
     * @param role     the single role granted to the user
     */
    public record User(
            @NotBlank @Size(min = 1, max = MAX_USERNAME_LENGTH) @Pattern(regexp = USERNAME_PATTERN)
            String username,
            @NotBlank String password,
            @NotNull Role role) {

        /**
         * Whether the username is not the reserved system stamp, ignoring case and surrounding
         * blanks.
         *
         * <p>Validated as the bean property {@code notSystemUser}. A missing username passes here,
         * because {@code @NotBlank} reports it.
         *
         * @return {@code true} unless the username is {@value UsersProperties#SYSTEM_USER}
         */
        @AssertTrue(message = "username must not be " + SYSTEM_USER)
        public boolean isNotSystemUser() {
            return username == null || !SYSTEM_USER.equalsIgnoreCase(username.trim());
        }

        /**
         * Whether the password fits BCrypt's input limit of
         * {@value UsersProperties#MAX_PASSWORD_BYTES} UTF-8 bytes.
         *
         * <p>Validated as the bean property {@code passwordWithinEncoderLimit} rather than as a
         * constraint on {@code password}, because a binding-failure report prints the rejected value
         * of the property it names: here that value is {@code false}, never the password. A missing
         * password passes here, because {@code @NotBlank} reports it. The bytes are counted with
         * {@link StandardCharsets#UTF_8}, the encoding {@code BCrypt.hashpw} applies.
         *
         * @return {@code true} unless the password is longer than
         *     {@value UsersProperties#MAX_PASSWORD_BYTES} bytes in UTF-8
         */
        @AssertTrue(message = "password must be at most " + MAX_PASSWORD_BYTES
                + " bytes in UTF-8, the most BCrypt encodes")
        public boolean isPasswordWithinEncoderLimit() {
            return password == null
                    || password.getBytes(StandardCharsets.UTF_8).length <= MAX_PASSWORD_BYTES;
        }

        /**
         * Describes the user with the password masked, so a binding-failure report or a log line that
         * prints the user or the whole users list never reveals the password.
         *
         * @return for example {@code User[username=sales, password=****, role=MAINTENANCE]}
         */
        @Override
        public String toString() {
            return "User[username=" + username + ", password=****, role=" + role + "]";
        }
    }
}
