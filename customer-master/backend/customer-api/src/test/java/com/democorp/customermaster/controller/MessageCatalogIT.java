package com.democorp.customermaster.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;

import com.democorp.customermaster.messages.MessageCatalog;
import com.democorp.customermaster.support.AbstractPostgresIT;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Proves the message catalog contract, over HTTP ({@code GET /api/messages}) and in process
 * ({@link MessageCatalog}): exactly 22 keys with the exact texts, the three message-text corrections,
 * the endpoint serving the bean's own data, the literal {@code {n}} substitution rule, and the
 * rejection of unknown codes and duplicate keys. This is the only catalog test.
 *
 * <p><b>Source.</b> The 17 {@code DEMnnnn} texts come from the {@code ADDMSGD} commands of
 * {@code 5250_Subfile/CRTMSGF.CLLE}, lines 12 to 47. A CL {@code +} continuation drops the leading
 * blanks of the next line, so {@code MSG('Press Enter to +} / {@code update. F12 to Cancel.')} reads
 * "Press Enter to update. F12 to Cancel.". Each {@code &1} becomes {@code {0}}. Three source typos are
 * corrected and nothing else changes: DEM0007 "in invalid" becomes "is invalid", DEM0009 loses its
 * double space after "add.", and DEM1002 "Rewiew" becomes "Review" with its double space removed. The
 * five {@code APPnnnn} keys cover conditions the IBM i programs never reach. DEM0008 is carried
 * although nothing raises it, because the catalog is data.
 *
 * <p><b>Substitution.</b> The rule is literal replacement of {@code {n}} by the n-th argument in one
 * pass, not {@link java.text.MessageFormat}; the browser applies the identical rule to the texts this
 * endpoint serves. The assertions call {@link MessageCatalog#text(String, Object...)} and never
 * re-implement the rule here.
 *
 * <p><b>Context.</b> The test runs in the base context of {@link AbstractPostgresIT}: no
 * {@code @MockitoBean}, no {@code @Import} and no nested configuration, so it adds no context variant.
 * It reads no customer data, so the database reset the base performs before each test does not affect
 * it.
 */
class MessageCatalogIT extends AbstractPostgresIT {

    /** The public endpoint that serves the whole catalog. */
    private static final String MESSAGES = "/api/messages";

    /** The number of keys: the 17 CUSTMSGF ids plus the five APP keys. */
    private static final int KEY_COUNT = 22;

    /**
     * Every key with its exact text, in catalog file order. These texts are fixed by the message
     * inventory of the specification and are asserted literally, never derived from the catalog.
     */
    private static final Map<String, String> EXPECTED = expectedTexts();

    /** The application's single message catalog bean, the server's one source of texts. */
    @Autowired
    private MessageCatalog catalog;

    @Test
    void servesExactlyTheTwentyTwoKeys() {
        Map<String, String> served = fetchCatalog();

        for (Map.Entry<String, String> expected : EXPECTED.entrySet()) {
            assertThat(served)
                    .as("text served for %s", expected.getKey())
                    .containsEntry(expected.getKey(), expected.getValue());
        }
        assertThat(served.keySet()).containsExactlyInAnyOrderElementsOf(EXPECTED.keySet());
        assertThat(served).hasSize(KEY_COUNT);
        assertThat(EXPECTED).hasSize(KEY_COUNT);
    }

    @Test
    void textCorrectionsApplied() {
        Map<String, String> served = fetchCatalog();

        assertThat(served.get("DEM0007"))
                .as("DEM0007 source typo 'in invalid' corrected")
                .isNotNull()
                .doesNotContain("in invalid")
                .contains("is invalid");
        assertThat(served.get("DEM1002"))
                .as("DEM1002 source typo 'Rewiew' and double space corrected")
                .isNotNull()
                .doesNotContain("Rewiew")
                .doesNotContain("  ")
                .contains("Review data.");
        assertThat(served.get("DEM0009"))
                .as("DEM0009 source double space after 'add.' corrected")
                .isNotNull()
                .doesNotContain("  ");
        assertThat(served)
                .as("DEM0008 carried although nothing raises it")
                .containsEntry("DEM0008", "Use F4 only in field followed by +");
    }

    @Test
    void httpCatalogEqualsBean() {
        Map<String, String> served = fetchCatalog();
        Map<String, String> bean = catalog.all();

        assertThat(served).containsExactlyInAnyOrderEntriesOf(bean);
        assertThat(served).hasSameSizeAs(bean);
        // The controller documents that the wire keeps the catalog file order, which Jackson takes from
        // the map's iteration order.
        assertThat(served.keySet()).containsExactlyElementsOf(bean.keySet());
    }

    @Test
    void literalSubstitution() {
        assertThat(catalog.text("DEM0004", "X")).isEqualTo("X is not a valid option at this time.");
        assertThat(catalog.text("DEM0502", "Name")).isEqualTo("Name: Must not be blank");
        // MessageFormat would treat the apostrophe as a quote and drop it.
        assertThat(catalog.text("DEM9898", "Can't find it")).isEqualTo("USPS: Can't find it");
        // One pass: text inserted from an argument is never substituted again.
        assertThat(catalog.text("DEM9898", "{0}")).isEqualTo("USPS: {0}");
        assertThat(catalog.text("DEM0000")).isEqualTo("Press Enter to update. F12 to Cancel.");
    }

    @Test
    void unknownCodeRejected() {
        assertThatThrownBy(() -> catalog.text("NOPE0000"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NOPE0000");
    }

    @Test
    void fromReaderRejectsDuplicateKey() {
        assertThatThrownBy(() -> MessageCatalog.fromReader(new StringReader("DEM0001=a\nDEM0001=b\n")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DEM0001");

        MessageCatalog fixture = MessageCatalog.fromReader(new StringReader("B=two\nA=one {0}\n"));

        assertThat(fixture.all().keySet()).containsExactly("B", "A");
        assertThat(fixture.text("A", "x")).isEqualTo("one x");
    }

    /**
     * Reads the catalog anonymously, as the SPA does before sign-in, and asserts the response shape.
     *
     * @return the served message id to text map, in the order the JSON object lists it
     */
    private Map<String, String> fetchCatalog() {
        ResponseEntity<String> response = anonymous().get().uri(MESSAGES).retrieve().toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        MediaType contentType = response.getHeaders().getContentType();
        assertThat(contentType).as("content type of %s", MESSAGES).isNotNull();
        assertThat(contentType.isCompatibleWith(MediaType.APPLICATION_JSON))
                .as("content type of %s is JSON: %s", MESSAGES, contentType)
                .isTrue();

        JsonNode body = json(response);
        assertThat(body.isObject()).as("body of %s is a JSON object: %s", MESSAGES, body).isTrue();

        Map<String, String> served = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> field : body.properties()) {
            assertThat(field.getValue().isTextual())
                    .as("value of %s is a JSON string: %s", field.getKey(), field.getValue())
                    .isTrue();
            served.put(field.getKey(), field.getValue().textValue());
        }
        return served;
    }

    /**
     * Builds the expected catalog: the 17 CUSTMSGF texts as CRTMSGF writes them, with {@code &1} as
     * {@code {0}} and the three typos corrected, followed by the five APP keys.
     *
     * @return key to exact text, in catalog file order
     */
    private static Map<String, String> expectedTexts() {
        Map<String, String> texts = new LinkedHashMap<>();
        texts.put("DEM0000", "Press Enter to update. F12 to Cancel.");
        texts.put("DEM0002", "No records match the selection criteria");
        texts.put("DEM0003", "Key is not active now");
        texts.put("DEM0004", "{0} is not a valid option at this time.");
        texts.put("DEM0005", "Use F4 only if + is on field");
        texts.put("DEM0006", "Too many records. Change the selection criteria.");
        texts.put("DEM0007", "State selection field is invalid.");
        texts.put("DEM0008", "Use F4 only in field followed by +");
        texts.put("DEM0009", "Press Enter to add. Press F12 to cancel");
        texts.put("DEM0501", "{0}: Must be Y or N");
        texts.put("DEM0502", "{0}: Must not be blank");
        texts.put("DEM0503", "State invalid. Can use F4 to prompt.");
        texts.put("DEM0599", "Customer deleted. Exit & redo search.");
        texts.put("DEM1001", "Customer being updated by another user or job.");
        texts.put("DEM1002", "Someone else changed record. Review data.");
        texts.put("DEM9898", "USPS: {0}");
        texts.put("DEM9999", "Program Error! Please contact IT now.");
        texts.put("APP0400", "Request is not valid: {0}");
        texts.put("APP0401", "Sign in required.");
        texts.put("APP0403", "You are not authorized to perform this action.");
        texts.put("APP0502", "Address service is unavailable. Try again later.");
        texts.put("APP0503", "No customer ids are left. Contact IT.");
        return texts;
    }
}
