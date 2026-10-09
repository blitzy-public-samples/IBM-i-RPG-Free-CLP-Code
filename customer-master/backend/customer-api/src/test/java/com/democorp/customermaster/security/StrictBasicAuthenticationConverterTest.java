package com.democorp.customermaster.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

/**
 * Specifies {@link SecurityConfig.StrictBasicAuthenticationConverter}: a Basic header must separate
 * the scheme from its token with a space, and a password may not exceed the
 * {@value UsersProperties#MAX_PASSWORD_BYTES} UTF-8 bytes BCrypt verifies; every other decision is
 * Spring's own. Pure JUnit 5 and AssertJ over Spring's servlet mock: no context, no server.
 */
@DisplayName("StrictBasicAuthenticationConverter: malformed or over-long Basic credentials are rejected")
final class StrictBasicAuthenticationConverterTest {

    private static final String USER = "maint";

    private static final String PASSWORD = "maint-pw";

    /** Three UTF-8 bytes. */
    private static final String EURO = "\u20ac";

    /** Two UTF-8 bytes. */
    private static final String E_ACUTE = "\u00e9";

    private final SecurityConfig.StrictBasicAuthenticationConverter converter =
            new SecurityConfig.StrictBasicAuthenticationConverter();

    @Test
    void requestWithoutAuthorizationHeaderStaysAnonymous() {
        assertThat(converter.convert(new MockHttpServletRequest())).isNull();
    }

    @ParameterizedTest(name = "Authorization <{0}>")
    @ValueSource(strings = {"Bearer x", "Digest username=\"maint\"", "Bas", ""})
    void headerOfAnotherSchemeStaysAnonymous(String header) {
        assertThat(converter.convert(withAuthorization(header))).isNull();
    }

    @ParameterizedTest(name = "scheme <{0}>")
    @ValueSource(strings = {"Basic ", "basic ", "BASIC ", "bAsIc "})
    void basicSchemeIsCaseInsensitive(String scheme) {
        assertCredentials(converter.convert(withAuthorization(scheme + token(USER, PASSWORD))), PASSWORD);
    }

    @Test
    void surroundingWhitespaceIsIgnored() {
        UsernamePasswordAuthenticationToken converted =
                converter.convert(withAuthorization(" \tBasic " + token(USER, PASSWORD) + " \t"));

        assertCredentials(converted, PASSWORD);
        assertThat(converted.getDetails()).isInstanceOf(WebAuthenticationDetails.class);
    }

    @ParameterizedTest(name = "scheme <{0}> before the token")
    @ValueSource(strings = {"Basic!", "BasicX", "Basic\t", "Basic", "basic:", "BASIC\u00a0"})
    void schemeNotFollowedByASpaceIsRejected(String scheme) {
        assertRejected(scheme + token(USER, PASSWORD));
    }

    @ParameterizedTest(name = "Authorization <{0}>")
    @ValueSource(strings = {"Basic", "Basic   ", "Basic !!!", "Basic  bWFpbnQ6bWFpbnQtcHc="})
    void emptyOrUndecodableTokenIsRejected(String header) {
        assertRejected(header);
    }

    @Test
    void tokenWithoutColonIsRejected() {
        String noColon = Base64.getEncoder().encodeToString(USER.getBytes(StandardCharsets.UTF_8));

        assertRejected("Basic " + noColon);
    }

    @Test
    void asciiPasswordOfExactlyTheBcryptLimitIsAccepted() {
        String password = "p".repeat(UsersProperties.MAX_PASSWORD_BYTES);

        assertThat(utf8Length(password)).isEqualTo(UsersProperties.MAX_PASSWORD_BYTES);
        assertCredentials(converter.convert(basic(USER, password)), password);
    }

    @Test
    void asciiPasswordOneByteOverTheBcryptLimitIsRejected() {
        String password = "p".repeat(UsersProperties.MAX_PASSWORD_BYTES + 1);

        assertRejected("Basic " + token(USER, password));
    }

    @Test
    void multibytePasswordOfExactlyTheBcryptLimitIsAccepted() {
        String password = EURO.repeat(UsersProperties.MAX_PASSWORD_BYTES / 3);

        assertThat(utf8Length(password)).isEqualTo(UsersProperties.MAX_PASSWORD_BYTES);
        assertCredentials(converter.convert(basic(USER, password)), password);
    }

    @ParameterizedTest(name = "suffix <{0}>")
    @ValueSource(strings = {"x", E_ACUTE, EURO})
    void multibytePasswordOfTheLimitPlusASuffixIsRejected(String suffix) {
        String password = EURO.repeat(UsersProperties.MAX_PASSWORD_BYTES / 3) + suffix;

        assertRejected("Basic " + token(USER, password));
    }

    @Test
    void passwordWhoseLastCharacterCrossesTheBcryptLimitIsRejected() {
        String password = "p".repeat(UsersProperties.MAX_PASSWORD_BYTES - 1) + E_ACUTE;

        assertThat(password).hasSize(UsersProperties.MAX_PASSWORD_BYTES);
        assertThat(utf8Length(password)).isEqualTo(UsersProperties.MAX_PASSWORD_BYTES + 1);
        assertRejected("Basic " + token(USER, password));
    }

    private static MockHttpServletRequest withAuthorization(String header) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, header);
        return request;
    }

    private static MockHttpServletRequest basic(String username, String password) {
        return withAuthorization("Basic " + token(username, password));
    }

    private static String token(String username, String password) {
        byte[] credentials = (username + ":" + password).getBytes(StandardCharsets.UTF_8);
        return Base64.getEncoder().encodeToString(credentials);
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static void assertCredentials(UsernamePasswordAuthenticationToken converted, String password) {
        assertThat(converted).isNotNull();
        assertThat(converted.getName()).isEqualTo(USER);
        assertThat(converted.getCredentials()).isEqualTo(password);
        assertThat(converted.isAuthenticated()).isFalse();
    }

    /**
     * Asserts a rejection whose message names neither the user nor any part of the password.
     *
     * @param header the {@code Authorization} header value
     */
    private void assertRejected(String header) {
        MockHttpServletRequest request = withAuthorization(header);

        assertThatExceptionOfType(BadCredentialsException.class)
                .isThrownBy(() -> converter.convert(request))
                .withMessageNotContainingAny(USER, PASSWORD, "pp", EURO);
    }
}
