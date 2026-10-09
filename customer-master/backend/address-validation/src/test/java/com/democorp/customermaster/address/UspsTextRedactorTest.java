package com.democorp.customermaster.address;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Specifies {@link UspsTextRedactor}, which replaces the request echoes and credentials in
 * text the USPS service sends back. The credentials are fictitious placeholders; the
 * password {@code p'&q} carries an apostrophe and {@code &}, so that its raw, request-wire
 * ({@link UspsXmlCodec#attributeValue}), full-entity and URL-encoded forms all differ.
 * Plain JUnit 5 and AssertJ, no network.
 */
@DisplayName("UspsTextRedactor: request echoes and credentials in USPS text")
class UspsTextRedactorTest {

    private static final String USER_ID = "TESTUSER123";

    private static final String PASSWORD = "p'&q";

    /**
     * A base URL with a query of its own, so that an echoed Verify query follows it after
     * {@code &}.
     */
    private static final String BASE_URL = "https://gw.example.invalid/ShippingAPI.dll?key=1";

    /** Deadlock guard for the adversarial inputs, not a performance limit. */
    private static final Duration WATCHDOG = Duration.ofSeconds(30);

    private final UspsTextRedactor redactor = redactor(BASE_URL, USER_ID, PASSWORD);

    @Test
    @DisplayName("redactRequest replaces every recognized form of an echoed request and keeps all other text")
    void redactRequestReplacesEveryEchoedForm() {
        Map<String, String> redactions = new LinkedHashMap<>();
        redactions.put("Cut: <AddressValidateRequest USERID=\"X\"><Revision>1</Revision><Address ID=\"0\">"
                + "<Address2>8 ELMWOOD DR", "Cut: [request document]");
        redactions.put("Cut: &lt;AddressValidateRequest USERID=&quot;X&quot;&gt;&lt;Address2&gt;8 ELMWOOD DR",
                "Cut: [request document]");
        redactions.put("Sent &lt;AddressValidateRequest USERID=&quot;X&quot;&gt;&lt;/AddressValidateRequest&gt; twice",
                "Sent [request document] twice");
        redactions.put("<AddressValidateRequest>a</AddressValidateRequest> and "
                + "<AddressValidateRequest>b</AddressValidateRequest>.", "[request document] and [request document].");
        redactions.put("Doc <AddressValidateRequest USERID=\"X\">\n<Address2>8 ELMWOOD DR</Address2>\n"
                + "</AddressValidateRequest> end", "Doc [request document] end");
        // A value spelling the closing tag of the other form does not end the document.
        redactions.put("<AddressValidateRequest PASSWORD=\"a&lt;/AddressValidateRequest&gt;b\">"
                + "<Address2>8 ELMWOOD DR</Address2></AddressValidateRequest> end", "[request document] end");
        redactions.put("&lt;AddressValidateRequest PASSWORD=&quot;a&amp;lt;/AddressValidateRequest&amp;gt;b&quot;&gt;"
                + "&lt;Address2&gt;8 ELMWOOD DR&lt;/Address2&gt;&lt;/AddressValidateRequest&gt; end",
                "[request document] end");
        redactions.put("XML value %3CAddressValidateRequest+USERID%3D%22X%22%3E%3CAddress2%3E8+ELMWOOD+DR rejected",
                "XML value [request document] rejected");
        redactions.put("GET " + BASE_URL + "&amp;API=Verify&amp;XML=%3CAddressValidateRequest+USERID%3D%22X%22%3E"
                + "%3C%2FAddressValidateRequest%3E failed", "GET [request URL]&amp;[request query] failed");
        redactions.put("API=Verify&amp;XML=<AddressValidateRequest USERID=\"X\"><Address2>8 ELMWOOD DR</Address2>"
                + "</AddressValidateRequest>&amp;x=1 end", "[request query] end");
        redactions.put("q API=Verify&XML=%3cAddressValidateRequest+USERID%3d%22X%22%3e", "q [request query]");
        redactions.put("q API=Verify&XML=", "q [request query]");
        redactions.put("Endpoint " + BASE_URL + " is down", "Endpoint [request URL] is down");
        // A wholly percent-encoded query is replaced whole, together with the document
        // marker the first step left inside it.
        redactions.put("q API%3DVerify%26XML%3D%3CAddressValidateRequest+USERID%3D%22X%22%3E end",
                "q [request query] end");
        redactions.put(URLEncoder.encode(BASE_URL, StandardCharsets.UTF_8) + " API%3DVerify%26XML%3D1",
                "[request URL] [request query]");
        List<String> unchanged = List.of(
                "Address Not Found.",
                "Address 123 MAIN ST, ANYTOWN CA 90210 see https://example.invalid/x",
                "response root is not AddressValidateResponse",
                "API=Other&XML=1 and AddressValidateRequest without its bracket",
                "");

        redactions.forEach((text, redacted) ->
                assertThat(redactor.redactRequest(text)).as(text).isEqualTo(redacted));
        unchanged.forEach(text -> assertThat(redactor.redactRequest(text)).as(text).isEqualTo(text));
        assertThat(redactor.redactRequest(null)).isEmpty();

        // 64 KiB of input built to make a backtracking pattern explode: each must end at
        // once. The watchdog only keeps a broken pattern from hanging the build.
        assertTimeoutPreemptively(WATCHDOG, () -> {
            assertThat(redactor.redactRequest(
                    "<AddressValidateRequest" + "</AddressValidateRequest".repeat(2_700)))
                    .isEqualTo("[request document]");
            assertThat(redactor.redactRequest("&lt;AddressValidateRequest".repeat(2_500)))
                    .isEqualTo("[request document]");
            assertThat(redactor.redactRequest("API=Verify&XML=".repeat(4_300)))
                    .isEqualTo("[request query]");
            assertThat(redactor.redactRequest("%3CAddressValidateRequest ".repeat(2_500)))
                    .isEqualTo("[request document] ".repeat(2_500));
        });
    }

    @Test
    @DisplayName("redactRequest replaces, whole, a token whose decoded layers echo the document, the query or the base URL")
    void redactRequestReplacesEncodedEchoesWhole() {
        String query = "API=Verify&XML=1";
        String document = "<AddressValidateRequest USERID=\"X\"><Address2>8 ELMWOOD DR</Address2>";
        Map<String, String> redactions = new LinkedHashMap<>();
        redactions.put("q " + encoded(query, 1) + " end", "q [request query] end");
        redactions.put("q " + encoded(query, 2) + " end", "q [request query] end");
        redactions.put("q " + encoded(query, 3) + " end", "q [request query] end");
        redactions.put("q " + encoded("API=Verify&amp;XML=1", 2) + " end", "q [request query] end");
        redactions.put("doc " + encoded(document, 2) + " end", "doc [request document] end");
        redactions.put("doc " + encoded(UspsTextRedactor.xmlEscape(document), 2) + " end", "doc [request document] end");
        redactions.put("doc " + encoded(document, 3), "doc [request document]");
        redactions.put("at " + encoded(BASE_URL, 1) + " now", "at [request URL] now");
        redactions.put("at " + encoded(BASE_URL, 2) + " now", "at [request URL] now");
        // A mixed encoding: the scheme separator encoded, the rest as configured.
        redactions.put("at " + BASE_URL.replace("://", ":%2F%2F") + " now", "at [request URL] now");
        // The echoed request URL, encoded once more: the document inside it decides.
        redactions.put("Rejected " + encoded(BASE_URL + "&API=Verify&XML=" + encoded(document, 1), 1),
                "Rejected [request document]");
        // Five layers: still encoded after the fourth decoding, so it cannot be checked.
        redactions.put("x " + encoded("a=b", 5) + " y", "x [encoded text] y");
        redactions.put("%" + "25".repeat(5), "[encoded text]");
        List<String> unchanged = List.of(
                "100% sure", "%41BC", "50%-off and 5%", "%zz %4 %", "%E2%82%AC 5 net",
                // Four layers decode completely, and the text echoes nothing.
                "x " + encoded("a=b", 4) + " y",
                "%" + "25".repeat(4),
                // The element name in another case, and an encoded word outside any echo.
                "%61ddressvalidaterequest Address%20Not%20Found.");

        redactions.forEach((text, redacted) ->
                assertThat(redactor.redactRequest(text)).as(text).isEqualTo(redacted));
        unchanged.forEach(text -> assertThat(redactor.redactRequest(text)).as(text).isEqualTo(text));
        // redact and redactDescription run the same step.
        assertThat(redactor.redact("q " + encoded(query, 2) + " end")).isEqualTo("q [request query] end");
        assertThat(redactor.redactDescription("x " + encoded("a=b", 5) + " y")).isEqualTo("x [encoded text] y");

        // 64 KiB tokens: each is decoded at most five times, so each call ends at once.
        // The watchdog only keeps a broken scan from hanging the build.
        assertTimeoutPreemptively(WATCHDOG, () -> {
            assertThat(redactor.redactRequest("%25".repeat(21_000))).isEqualTo("%25".repeat(21_000));
            assertThat(redactor.redactRequest("%" + "25".repeat(32_000))).isEqualTo("[encoded text]");
            assertThat(redactor.redactRequest("%41 ".repeat(16_000))).isEqualTo("%41 ".repeat(16_000));
            assertThat(redactor.redactRequest("%[request query]".repeat(4_000))).isEqualTo("%[request query]".repeat(4_000));
            assertThat(redactor.redact("%2".repeat(32_000))).isEqualTo("%2".repeat(32_000));
        });
    }

    @Test
    @DisplayName("percentDecode decodes each escape leniently: either hex case, UTF-8 runs, U+FFFD for malformed bytes, + kept")
    void percentDecodeIsLenient() {
        String plain = "Address Not Found.";

        assertThat(UspsTextRedactor.percentDecode("a%41%62c")).isEqualTo("aAbc");
        assertThat(UspsTextRedactor.percentDecode("%3a%3A%2f%2F")).isEqualTo(":://");
        assertThat(UspsTextRedactor.percentDecode("%C3%A9%F0%9F%98%80")).isEqualTo("é\uD83D\uDE00");
        assertThat(UspsTextRedactor.percentDecode("%c3%a9")).isEqualTo("é");
        assertThat(UspsTextRedactor.percentDecode("a%C3b%FF")).isEqualTo("a\uFFFDb\uFFFD");
        assertThat(UspsTextRedactor.percentDecode("a+b%2B")).isEqualTo("a+b+");
        assertThat(UspsTextRedactor.percentDecode("100% %4 %G1 %%41")).isEqualTo("100% %4 %G1 %A");
        // A non-ASCII digit is no hex digit.
        assertThat(UspsTextRedactor.percentDecode("%\u0663\u0663")).isEqualTo("%\u0663\u0663");
        assertThat(UspsTextRedactor.percentDecode(plain)).isSameAs(plain);
    }

    @Test
    @DisplayName("decodedLayers decodes until nothing changes, at most four times, and reports a token still encoded")
    void decodedLayersStopsAtFourLayers() {
        assertThat(UspsTextRedactor.MAX_DECODE_LAYERS).isEqualTo(4);
        assertThat(UspsTextRedactor.decodedLayers("a%252541"))
                .isEqualTo(new UspsTextRedactor.DecodedLayers(List.of("a%2541", "a%41", "aA"), false));
        assertThat(UspsTextRedactor.decodedLayers("x%25252541"))
                .isEqualTo(new UspsTextRedactor.DecodedLayers(List.of("x%252541", "x%2541", "x%41", "xA"), false));
        assertThat(UspsTextRedactor.decodedLayers("x%2525252541"))
                .isEqualTo(new UspsTextRedactor.DecodedLayers(
                        List.of("x%25252541", "x%252541", "x%2541", "x%41"), true));
        assertThat(UspsTextRedactor.decodedLayers("100% sure"))
                .isEqualTo(new UspsTextRedactor.DecodedLayers(List.of(), false));
    }

    @Test
    @DisplayName("mask replaces each credential in its raw, request-wire, full-entity and URL-encoded forms")
    void maskReplacesEveryCredentialForm() {
        String wire = UspsXmlCodec.attributeValue(PASSWORD);
        assertThat(wire).isEqualTo("p'&amp;q");
        String entities = UspsTextRedactor.xmlEscape(PASSWORD);
        assertThat(entities).isEqualTo("p&apos;&amp;q");
        List<String> forms = List.of(USER_ID, PASSWORD, wire, entities,
                URLEncoder.encode(USER_ID, StandardCharsets.UTF_8),
                URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8),
                URLEncoder.encode(wire, StandardCharsets.UTF_8),
                URLEncoder.encode(entities, StandardCharsets.UTF_8));

        forms.forEach(form -> assertThat(redactor.mask("x " + form + " y"))
                .as(form)
                .isEqualTo("x " + AddressValidationProperties.MASK + " y"));
        assertThat(redactor.mask("Address Not Found.")).isEqualTo("Address Not Found.");
        assertThat(redactor.mask(null)).isEmpty();
    }

    @Test
    @DisplayName("mask replaces every percent-encoding of each credential form: either hex case, a space as + or %20, partial")
    void maskReplacesEveryPercentEncoding() {
        // Fictitious credentials: a space and '&' in the password, '"' and an apostrophe in the user id.
        String password = "p a&b";
        String userId = "q\"r's";
        UspsTextRedactor encoded = redactor(BASE_URL, userId, password);
        String wire = UspsXmlCodec.attributeValue(password);
        assertThat(wire).isEqualTo("p a&amp;b").isEqualTo(UspsTextRedactor.xmlEscape(password));
        String userIdWire = UspsXmlCodec.attributeValue(userId);
        assertThat(userIdWire).isEqualTo("q&quot;r's");
        String userIdEntities = UspsTextRedactor.xmlEscape(userId);
        assertThat(userIdEntities).isEqualTo("q&quot;r&apos;s");
        List<String> forms = List.of(
                password, wire, userId, userIdWire, userIdEntities,
                URLEncoder.encode(password, StandardCharsets.UTF_8),
                URLEncoder.encode(wire, StandardCharsets.UTF_8),
                URLEncoder.encode(userId, StandardCharsets.UTF_8),
                URLEncoder.encode(userIdWire, StandardCharsets.UTF_8),
                URLEncoder.encode(userIdEntities, StandardCharsets.UTF_8),
                // A space as %20.
                "p%20a%26b", "p%20a%26amp%3Bb",
                // Lowercase hex.
                "p+a%26amp%3bb", "p%20a%26amp%3bb", "q%26quot%3br%26apos%3bs",
                // Mixed-case hex.
                "q%26quot%3Br%26apos%3bs", "p+a%26amp%3Bb",
                // Partly encoded.
                "p a%26b", "p+a&b", "p a&amp%3bb", "q%22r's", "q&quot;r%27s",
                // Unreserved characters percent-encoded.
                "%70 a&b", "%70%20%61%26%62", "%71\"r's");
        List<String> unchanged = List.of(
                "Address Not Found.", "pa b", "p a&c", "P A&B", "p a&B", "q a&b", "Q\"R'S",
                // %2B is a literal plus, never a space, and its decoding p+a!b is no form either.
                "p%2Ba!b");

        forms.forEach(form -> assertThat(encoded.mask("x " + form + " y"))
                .as(form)
                .isEqualTo("x " + AddressValidationProperties.MASK + " y"));
        assertThat(encoded.mask("Rejected p%20a%26b and p+a%26amp%3bb")).isEqualTo("Rejected **** and ****");
        unchanged.forEach(text -> assertThat(encoded.mask(text)).as(text).isEqualTo(text));
        // In place, %2B is a literal plus, never a space; decoded, p%2Ba%26b is p+a&b, the
        // partly encoded form above encoded once more, so the token is masked whole.
        assertThat(encoded.mask("x p%2Ba%26b y")).isEqualTo("x " + AddressValidationProperties.MASK + " y");
        assertThat(encoded.mask(null)).isEmpty();
    }

    @Test
    @DisplayName("mask matches the UTF-8 bytes of a multi-byte code point in either hex case, literal characters case-sensitively")
    void maskMatchesMultiByteCodePointsInEitherHexCase() {
        // Fictitious password: 'é' is two UTF-8 bytes (C3 A9), the emoji four (F0 9F 98 80).
        UspsTextRedactor accented = redactor(BASE_URL, "", "Zé;\uD83D\uDE00");
        List<String> forms = List.of(
                "Zé;\uD83D\uDE00",
                "%5A%C3%A9%3B%F0%9F%98%80",
                "%5a%c3%a9%3b%f0%9f%98%80",
                "Z%c3%A9%3B%F0%9f%98%80",
                "Zé%3b\uD83D\uDE00",
                "Z%C3%A9;%F0%9F%98%80");
        List<String> unchanged = List.of(
                "zé;\uD83D\uDE00", "ZÉ;\uD83D\uDE00", "Z%C3%89;\uD83D\uDE00", "Zé;", "Z%C3;\uD83D\uDE00");

        forms.forEach(form -> assertThat(accented.mask("x " + form + " y"))
                .as(form)
                .isEqualTo("x " + AddressValidationProperties.MASK + " y"));
        unchanged.forEach(text -> assertThat(accented.mask(text)).as(text).isEqualTo(text));
    }

    @Test
    @DisplayName("mask replaces, whole, a token whose decoded layers hold a credential form; in-place masks stay as they were")
    void maskReplacesMultiplyEncodedCredentialTokensWhole() {
        // Fictitious password; '&' and '"' make its raw and request-wire forms differ.
        String password = "pl&ce\"holder";
        UspsTextRedactor quoted = redactor(BASE_URL, USER_ID, password);
        String wire = UspsXmlCodec.attributeValue(password);
        assertThat(wire).isEqualTo("pl&amp;ce&quot;holder");
        String doublyEncodedAttribute = "PASSWORD%253D%2522pl%2526amp%253Bce%2526quot%253Bholder%2522";
        assertThat(doublyEncodedAttribute).isEqualTo(encoded("PASSWORD=\"" + wire + "\"", 2));
        List<String> tokens = List.of(doublyEncodedAttribute,
                encoded(password, 2), encoded(wire, 2), encoded(wire, 3), encoded(wire, 5),
                // The user id with its T percent-encoded, then the attribute encoded once more.
                "USERID%3D%22%2554ESTUSER123%22");
        // The apostrophe password of the other cases, in its request-wire and full-entity forms.
        List<String> apostropheTokens = List.of(encoded(PASSWORD, 2),
                encoded(UspsXmlCodec.attributeValue(PASSWORD), 2), encoded(UspsTextRedactor.xmlEscape(PASSWORD), 3));
        List<String> unchanged = List.of(
                "100% sure", "%41BC", "x %2541 y", encoded("pl&ce\"holdeR", 2),
                // A form whose percent-encoding the in-place step already masked keeps its other characters.
                "Rejected ****%2Fx");

        tokens.forEach(token -> assertThat(quoted.mask("Rejected " + token + " now"))
                .as(token)
                .isEqualTo("Rejected " + AddressValidationProperties.MASK + " now"));
        apostropheTokens.forEach(token -> assertThat(redactor.mask("Rejected " + token + " now"))
                .as(token)
                .isEqualTo("Rejected " + AddressValidationProperties.MASK + " now"));
        unchanged.forEach(text -> assertThat(quoted.mask(text)).as(text).isEqualTo(text));
        // The in-place step runs first and keeps its output for raw and singly encoded forms.
        assertThat(quoted.mask(USER_ID + "%2Fx and " + encoded(wire, 1) + "!"))
                .isEqualTo("****%2Fx and ****!");
        assertThat(quoted.redact("Rejected " + doublyEncodedAttribute)).isEqualTo("Rejected ****");
        assertThat(quoted.redactDescription("Rejected " + doublyEncodedAttribute)).isEqualTo("Rejected ****");

        // The watchdog only keeps a broken scan from hanging the build.
        assertTimeoutPreemptively(WATCHDOG, () -> {
            assertThat(quoted.mask("%25".repeat(21_000))).isEqualTo("%25".repeat(21_000));
            assertThat(quoted.mask("%" + "25".repeat(32_000))).isEqualTo("%" + "25".repeat(32_000));
            assertThat(quoted.mask(encoded(wire, 2).repeat(1_500))).isEqualTo(AddressValidationProperties.MASK);
        });
    }

    @Test
    @DisplayName("mask ends at once on 64 KiB runs of %25 or %2: '%' is the one character whose two alternatives start alike")
    void maskStaysLinearOnAdversarialInput() {
        UspsTextRedactor percent = redactor(BASE_URL, "%".repeat(64), "%25".repeat(30));

        // The watchdog only keeps a broken pattern from hanging the build.
        assertTimeoutPreemptively(WATCHDOG, () -> {
            assertThat(percent.mask("%25".repeat(21_000) + "!"))
                    .isEqualTo(AddressValidationProperties.MASK.repeat(700) + "!");
            assertThat(percent.mask("%2".repeat(32_000))).isEqualTo("%2".repeat(32_000));
        });
    }

    @Test
    @DisplayName("mask replaces the longer credential first, so one contained in the other is masked whole")
    void maskReplacesLongestFormFirst() {
        UspsTextRedactor nested = redactor(BASE_URL, "AB12", "XAB12Y");

        assertThat(nested.mask("XAB12Y and AB12")).isEqualTo("**** and ****");
    }

    @Test
    @DisplayName("blank credentials mask nothing")
    void blankCredentialsMaskNothing() {
        UspsTextRedactor none = redactor(BASE_URL, "", "  ");

        assertThat(none.mask("No match for  /  .")).isEqualTo("No match for  /  .");
    }

    @Test
    @DisplayName("redact replaces a request echo whole, then masks the credentials outside it")
    void redactRedactsRequestThenMasks() {
        String text = "Rejected <AddressValidateRequest USERID=\"" + USER_ID + "\" PASSWORD=\"p'&amp;q\">"
                + "</AddressValidateRequest> for " + USER_ID + " at " + BASE_URL;

        assertThat(redactor.redact(text)).isEqualTo("Rejected [request document] for **** at [request URL]");
        assertThat(redactor.redact("Invalid City.")).isEqualTo("Invalid City.");
        assertThat(redactor.redact(null)).isEmpty();
    }

    @Test
    @DisplayName("redactDescription shows a documented description as sent although a short credential occurs in it; mask still masks it")
    void redactDescriptionShowsDocumentedDescriptionsAsSent() {
        // Fictitious one-character password, which "Address" holds twice.
        UspsTextRedactor shortPassword = redactor(BASE_URL, USER_ID, "s");

        assertThat(shortPassword.redactDescription("Address Not Found.")).isEqualTo("Address Not Found.");
        // No 's' in it: masked as redact masks it, which changes nothing.
        assertThat(shortPassword.redactDescription("Invalid City.")).isEqualTo("Invalid City.");
        UspsWebToolsAddressValidationClient.DOCUMENTED_DESCRIPTIONS.forEach(documented ->
                assertThat(shortPassword.redactDescription(documented)).as(documented).isEqualTo(documented));
        // mask and redact, which fault reasons use, mask every occurrence.
        assertThat(shortPassword.mask("Address Not Found.")).isEqualTo("Addre******** Not Found.");
        assertThat(shortPassword.redact("Address Not Found.")).isEqualTo("Addre******** Not Found.");
    }

    @Test
    @DisplayName("redactDescription withholds whole an undocumented description holding a short credential in any form mask matches")
    void redactDescriptionWithholdsUndocumentedDescriptionHoldingShortCredential() {
        // Fictitious credentials: a one-character password or user id.
        UspsTextRedactor shortPassword = redactor(BASE_URL, USER_ID, "s");
        UspsTextRedactor shortUserId = redactor(BASE_URL, "S", "");
        // '<' differs in its raw, full-entity and URL-encoded forms; ';' encodes with a hex letter.
        UspsTextRedactor angle = redactor(BASE_URL, USER_ID, "<");
        UspsTextRedactor semicolon = redactor(BASE_URL, USER_ID, ";");
        String withheld = UspsTextRedactor.DESCRIPTION_WITHHELD_MARKER;

        assertThat(withheld).isEqualTo("[description withheld]");
        assertThat(shortPassword.redactDescription("Rejected pass s")).isEqualTo(withheld);
        assertThat(shortPassword.redactDescription("Rejected %73")).isEqualTo(withheld);
        assertThat(shortUserId.redactDescription("Unknown user S")).isEqualTo(withheld);
        assertThat(shortUserId.redactDescription("Unknown user %53")).isEqualTo(withheld);
        assertThat(shortUserId.redactDescription("Invalid State Code.")).isEqualTo("Invalid State Code.");
        assertThat(shortUserId.redactDescription("Rejected")).isEqualTo("Rejected");
        assertThat(angle.redactDescription("Rejected &lt;")).isEqualTo(withheld);
        assertThat(angle.redactDescription("Rejected %26lt%3B")).isEqualTo(withheld);
        assertThat(angle.redactDescription("Rejected %3C")).isEqualTo(withheld);
        assertThat(semicolon.redactDescription("Rejected a%3bb")).isEqualTo(withheld);
        assertThat(semicolon.redactDescription("Rejected a%3Bb")).isEqualTo(withheld);
        // mask, which fault reasons use, still masks each occurrence in place.
        assertThat(shortPassword.mask("Rejected pass s")).isEqualTo("Rejected pa******** ****");
        assertThat(shortUserId.mask("Unknown user %53")).isEqualTo("Unknown user ****");
    }

    @Test
    @DisplayName("a credential shorter than four code points is short: abc and two emoji are; abcd is masked as redact masks it")
    void shortCredentialIsCountedInCodePoints() {
        // Fictitious passwords. The two emoji are four chars but two code points.
        String twoEmoji = "\uD83D\uDE00\uD83D\uDE01";
        UspsTextRedactor three = redactor(BASE_URL, USER_ID, "abc");
        UspsTextRedactor four = redactor(BASE_URL, USER_ID, "abcd");
        UspsTextRedactor emoji = redactor(BASE_URL, USER_ID, twoEmoji);
        String withheld = UspsTextRedactor.DESCRIPTION_WITHHELD_MARKER;

        assertThat(UspsTextRedactor.SHORT_CREDENTIAL_CODE_POINTS).isEqualTo(4);
        assertThat(twoEmoji).hasSize(4);
        assertThat(twoEmoji.codePointCount(0, twoEmoji.length())).isEqualTo(2);
        assertThat(three.redactDescription("x abc y")).isEqualTo(withheld);
        assertThat(three.redactDescription("Address Not Found.")).isEqualTo("Address Not Found.");
        assertThat(four.redactDescription("x abcd y")).isEqualTo("x **** y").isEqualTo(four.redact("x abcd y"));
        assertThat(emoji.redactDescription("x " + twoEmoji + " y")).isEqualTo(withheld);
        assertThat(emoji.redactDescription("x %F0%9F%98%80%f0%9f%98%81 y")).isEqualTo(withheld);
        assertThat(emoji.mask("x " + twoEmoji + " y")).isEqualTo("x **** y");
    }

    @Test
    @DisplayName("with no short credential redactDescription returns exactly what redact returns")
    void redactDescriptionEqualsRedactWithoutShortCredential() {
        String echo = "Rejected <AddressValidateRequest USERID=\"" + USER_ID + "\" PASSWORD=\"p'&amp;q\">"
                + "</AddressValidateRequest> for " + USER_ID + " at " + BASE_URL;
        List<String> texts = List.of(echo, "Address Not Found.", "Invalid City.",
                "Rejected " + PASSWORD + " and " + URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8), "");
        // Blank credentials are ignored, never short.
        UspsTextRedactor none = redactor(BASE_URL, "", "  ");

        assertThat(redactor.redactDescription(echo)).isEqualTo("Rejected [request document] for **** at [request URL]");
        texts.forEach(text -> assertThat(redactor.redactDescription(text)).as(text).isEqualTo(redactor.redact(text)));
        assertThat(redactor.redactDescription(null)).isEmpty();
        assertThat(none.redactDescription("No match for  /  .")).isEqualTo("No match for  /  .");
    }

    @Test
    @DisplayName("redactDescription replaces the request echoes first: a short credential inside an echo alone is not withheld")
    void redactDescriptionReplacesRequestEchoesFirst() {
        // Fictitious one-character passwords: 'z' occurs in no marker, 's' in "[request document]".
        UspsTextRedactor shortZ = redactor(BASE_URL, USER_ID, "z");
        UspsTextRedactor shortS = redactor(BASE_URL, USER_ID, "s");
        String echoZ = "Rejected <AddressValidateRequest USERID=\"" + USER_ID + "\" PASSWORD=\"z\">"
                + "</AddressValidateRequest> at " + BASE_URL;
        String echoS = "Rejected <AddressValidateRequest PASSWORD=\"s\"></AddressValidateRequest> pass s";

        assertThat(shortZ.redactDescription(echoZ)).isEqualTo("Rejected [request document] at [request URL]");
        assertThat(shortS.redactDescription(echoS)).isEqualTo(UspsTextRedactor.DESCRIPTION_WITHHELD_MARKER);
    }

    @Test
    @DisplayName("xmlEscape writes every predefined entity, the apostrophe included")
    void xmlEscapeWritesEveryPredefinedEntity() {
        assertThat(UspsTextRedactor.xmlEscape("a&b<c>d\"e'f")).isEqualTo("a&amp;b&lt;c&gt;d&quot;e&apos;f");
        assertThat(UspsTextRedactor.xmlEscape("")).isEmpty();
    }

    @Test
    @DisplayName("the settings are required")
    void settingsAreRequired() {
        assertThatNullPointerException().isThrownBy(() -> new UspsTextRedactor(null)).withMessage("usps");
    }

    private static UspsTextRedactor redactor(String baseUrl, String userId, String password) {
        return new UspsTextRedactor(new AddressValidationProperties.Usps(
                baseUrl, userId, password, Duration.ofSeconds(5), Duration.ofSeconds(5)));
    }

    /** {@code text} URL-encoded {@code times} times, as an echo that re-encodes it carries it. */
    private static String encoded(String text, int times) {
        String result = text;
        for (int time = 0; time < times; time++) {
            result = URLEncoder.encode(result, StandardCharsets.UTF_8);
        }
        return result;
    }
}
