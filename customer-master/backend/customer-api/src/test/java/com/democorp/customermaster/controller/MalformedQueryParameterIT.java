package com.democorp.customermaster.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.democorp.customermaster.support.AbstractPostgresIT;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.lang.Nullable;

/**
 * Specifies the answer to a query parameter Tomcat cannot decode, a {@code %} not followed by two
 * hexadecimal digits in its name or value: 400 {@code application/problem+json} {@code APP0400}
 * "Request is not valid: cursor has an invalid value" with one {@code errors[]} item on the parameter,
 * the shape a {@code size=abc} type mismatch gets, rather than the 200 that Tomcat's silent drop of the
 * parameter would produce (the first page for a malformed cursor, the unfiltered list for a malformed
 * filter).
 *
 * <p><b>Controls.</b> Escaped percent signs keep their meaning: {@code cursor=%25%25%25} is the search
 * service's own "cursor is not valid" and {@code name=ZQ%25} a wildcard search. Security decides first:
 * an anonymous request with a malformed query is 401 {@code APP0401}, and the public message catalog
 * answers the same 400 as any other route.
 *
 * <p><b>Why a raw socket.</b> {@code java.net.URI}, and with it the JDK {@code HttpClient} behind
 * {@link AbstractPostgresIT}'s clients, rejects a malformed escape such as {@code %%%} before sending,
 * and its quoting constructors would send {@code %25} instead. Each request is therefore written to the
 * application's port byte for byte, as curl sends it, so Tomcat parses exactly the query under test.
 *
 * <p>The base context variant of {@link AbstractPostgresIT}: no mocked bean, no import, no nested
 * configuration.
 */
@DisplayName("Query parameters Tomcat cannot decode: 400 APP0400 on the parameter, never served as absent")
class MalformedQueryParameterIT extends AbstractPostgresIT {

    /** The members of an APP0400 that names a parameter, in the order the factory writes them. */
    private static final List<String> APP0400_MEMBERS =
            List.of("type", "title", "status", "detail", "instance", "code", "args", "errors");

    /** The members of an APP0400 that names no parameter. */
    private static final List<String> APP0400_UNNAMED_MEMBERS =
            List.of("type", "title", "status", "detail", "instance", "code", "args");

    /** Longest wait for the application's answer on a raw connection. */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    /** The end of an HTTP header section. */
    private static final byte[] HEADER_END = {'\r', '\n', '\r', '\n'};

    /**
     * Inserts three active customers after the base class has reset the database, so a dropped filter
     * would show as extra rows.
     */
    @BeforeEach
    void insertCustomers() {
        insertCustomer("AAAB", "ALPHA SUPPLY", "CA");
        insertCustomer("AAAC", "ZQ TEST CO", "NV");
        insertCustomer("AAAD", "MIDDLE CORP", "TX");
    }

    // ---------------------------------------------------------------------------------------------
    // Undecodable parameters
    // ---------------------------------------------------------------------------------------------

    /**
     * Each undecodable parameter of the search and the state list is a 400 APP0400 on that parameter,
     * with and without {@code X-Requested-With}, and the body never quotes the value sent.
     *
     * @param target the request target, sent unchanged
     * @param field the parameter the problem must name
     */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
        "/api/customers?cursor=%%%|cursor",
        "/api/customers?name=ZQ%&size=2|name",
        "/api/customers?state=C%|state",
        "/api/customers?size=1%|size",
        "/api/customers?includeInactive=tr%ue|includeInactive",
        "/api/customers?name=%ZZ|name",
        "/api/customers?state=%ZZ|state",
        "/api/customers?cursor=%ZZ|cursor",
        "/api/customers?size=%ZZ|size",
        "/api/states?nameContains=car%|nameContains",
        "/api/states?nameContains=%ZZ|nameContains"
    })
    @DisplayName("an undecodable value: 400 APP0400 with errors[0].field on its parameter")
    void undecodableValueIsAFieldError(String target, String field) throws IOException {
        for (boolean xhr : List.of(true, false)) {
            RawResponse response = get(target, INQUIRY_USER, INQUIRY_PASSWORD, xhr);

            JsonNode problem = assertNamedInvalid(response, field, pathOf(target));
            assertThat(response.body()).as("the value sent is never echoed").doesNotContain("%");
            assertThat(fieldNames(problem)).isEqualTo(APP0400_MEMBERS);
        }
    }

    @Test
    @DisplayName("the body has exactly the shape a size=abc type mismatch gets")
    void sameShapeAsTypeMismatch() throws IOException {
        JsonNode mismatch = problem(get("/api/customers?size=abc", INQUIRY_USER, INQUIRY_PASSWORD, true));
        JsonNode undecodable = problem(get("/api/customers?size=1%", INQUIRY_USER, INQUIRY_PASSWORD, true));

        assertThat(undecodable).isEqualTo(mismatch);
    }

    @Test
    @DisplayName("several undecodable parameters: the first in query order is named, alone")
    void firstUndecodableParameterIsNamed() throws IOException {
        RawResponse response = get("/api/customers?name=AB&city=%G1&state=%", INQUIRY_USER, INQUIRY_PASSWORD,
                true);

        JsonNode problem = assertNamedInvalid(response, "city", "/api/customers");
        assertThat(problem.path("errors")).hasSize(1);
    }

    @Test
    @DisplayName("an undecodable parameter name: APP0400 naming no parameter, with no errors member")
    void undecodableNameNamesNoField() throws IOException {
        RawResponse response = get("/api/customers?na%ZZme=x", INQUIRY_USER, INQUIRY_PASSWORD, true);

        JsonNode problem = assertUnnamedInvalid(response, "parameter has an invalid value");
        assertThat(response.body()).doesNotContain("%");
        assertThat(problem.path("instance").asText()).isEqualTo("/api/customers");
    }

    @Test
    @DisplayName("a parameter with a value but no name: APP0400 'parameter without a name', no errors")
    void namelessParameterIsRejected() throws IOException {
        RawResponse response = get("/api/customers?=x", INQUIRY_USER, INQUIRY_PASSWORD, true);

        assertUnnamedInvalid(response, "parameter without a name");
    }

    @Test
    @DisplayName("the public message catalog rejects an undecodable parameter the same way")
    void publicEndpointRejectsUndecodableParameter() throws IOException {
        assertNamedInvalid(get("/api/messages?x=%ZZ", null, null, true), "x", "/api/messages");

        RawResponse catalog = get("/api/messages", null, null, true);
        assertThat(catalog.status()).isEqualTo(200);
        assertThat(json(catalog).has("APP0400")).isTrue();
    }

    // ---------------------------------------------------------------------------------------------
    // Security first
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("anonymous with an undecodable cursor: still 401 APP0401, challenged only without XHR")
    void anonymousIsStillUnauthorized() throws IOException {
        RawResponse plain = get("/api/customers?cursor=%%%", null, null, false);
        RawResponse xhr = get("/api/customers?cursor=%%%", null, null, true);

        assertThat(plain.status()).isEqualTo(401);
        assertThat(problem(plain).path("code").asText()).isEqualTo("APP0401");
        assertThat(plain.header("WWW-Authenticate")).isEqualTo("Basic realm=\"customer-master\"");
        assertThat(xhr.status()).isEqualTo(401);
        assertThat(problem(xhr).path("code").asText()).isEqualTo("APP0401");
        assertThat(xhr.header("WWW-Authenticate")).isNull();
    }

    // ---------------------------------------------------------------------------------------------
    // Controls: decodable queries keep their answers
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("an escaped malformed cursor is still the search service's 'cursor is not valid'")
    void escapedMalformedCursorKeepsItsReason() throws IOException {
        RawResponse response = get("/api/customers?cursor=%25%25%25", INQUIRY_USER, INQUIRY_PASSWORD, true);

        JsonNode problem = problem(response);
        assertThat(response.status()).isEqualTo(400);
        assertThat(problem.path("code").asText()).isEqualTo("APP0400");
        assertThat(problem.path("detail").asText()).isEqualTo("Request is not valid: cursor is not valid");
        assertThat(problem.path("errors").get(0).path("field").asText()).isEqualTo("cursor");
    }

    @Test
    @DisplayName("an escaped % is a wildcard and filters; a plain search filters and lists")
    void decodableSearchesAreServed() throws IOException {
        assertThat(custIds(get("/api/customers?name=ZQ%25&size=2", INQUIRY_USER, INQUIRY_PASSWORD, true)))
                .containsExactly("AAAC");
        assertThat(custIds(get("/api/customers?name=ZQ&size=2", INQUIRY_USER, INQUIRY_PASSWORD, false)))
                .containsExactly("AAAC");
        assertThat(custIds(get("/api/customers?state=NV", INQUIRY_USER, INQUIRY_PASSWORD, true)))
                .containsExactly("AAAC");
        assertThat(custIds(get("/api/customers", INQUIRY_USER, INQUIRY_PASSWORD, true)))
                .containsExactly("AAAB", "AAAD", "AAAC");
    }

    @Test
    @DisplayName("a decodable state-list filter is served")
    void decodableStateFilterIsServed() throws IOException {
        RawResponse response = get("/api/states?nameContains=car", INQUIRY_USER, INQUIRY_PASSWORD, true);

        assertThat(response.status()).isEqualTo(200);
        List<String> names = new ArrayList<>();
        json(response).forEach(state -> names.add(state.path("name").asText()));
        assertThat(names).containsExactly("North Carolina", "South Carolina");
    }

    // ---------------------------------------------------------------------------------------------
    // Assertions
    // ---------------------------------------------------------------------------------------------

    /**
     * Asserts a 400 APP0400 whose reason, args and only {@code errors[]} item name {@code field}.
     *
     * @param response the response
     * @param field the parameter expected
     * @param instance the request path expected as {@code instance}
     * @return the problem
     */
    private JsonNode assertNamedInvalid(RawResponse response, String field, String instance) {
        JsonNode problem = problem(response);
        String reason = field + " has an invalid value";
        String detail = "Request is not valid: " + reason;
        assertThat(response.status()).isEqualTo(400);
        assertThat(problem.path("type").asText()).isEqualTo("urn:customer-master:problem:APP0400");
        assertThat(problem.path("title").asText()).isEqualTo("Bad Request");
        assertThat(problem.path("status").asInt()).isEqualTo(400);
        assertThat(problem.path("code").asText()).isEqualTo("APP0400");
        assertThat(problem.path("detail").asText()).isEqualTo(detail);
        assertThat(problem.path("instance").asText()).isEqualTo(instance);
        assertThat(problem.path("args")).hasSize(1);
        assertThat(problem.path("args").get(0).asText()).isEqualTo(reason);
        assertThat(problem.path("errors")).hasSize(1);
        JsonNode error = problem.path("errors").get(0);
        assertThat(error.path("field").asText()).isEqualTo(field);
        assertThat(error.path("code").asText()).isEqualTo("APP0400");
        assertThat(error.path("message").asText()).isEqualTo(detail);
        return problem;
    }

    /**
     * Asserts a 400 APP0400 with the given reason and no {@code errors} member.
     *
     * @param response the response
     * @param reason the reason expected
     * @return the problem
     */
    private JsonNode assertUnnamedInvalid(RawResponse response, String reason) {
        JsonNode problem = problem(response);
        assertThat(response.status()).isEqualTo(400);
        assertThat(problem.path("code").asText()).isEqualTo("APP0400");
        assertThat(problem.path("detail").asText()).isEqualTo("Request is not valid: " + reason);
        assertThat(problem.path("args").get(0).asText()).isEqualTo(reason);
        assertThat(fieldNames(problem)).isEqualTo(APP0400_UNNAMED_MEMBERS);
        return problem;
    }

    /**
     * Asserts a {@code application/problem+json} response and parses it.
     *
     * @param response the response
     * @return the problem
     */
    private JsonNode problem(RawResponse response) {
        String contentType = response.header("Content-Type");
        assertThat(contentType).as("Content-Type of %s: %s", response.status(), response.body()).isNotNull();
        assertThat(MediaType.APPLICATION_PROBLEM_JSON.isCompatibleWith(MediaType.parseMediaType(contentType)))
                .as("Content-Type %s of %s: %s", contentType, response.status(), response.body()).isTrue();
        return json(response);
    }

    /**
     * Parses a response body as JSON.
     *
     * @param response the response
     * @return the parsed body
     */
    private JsonNode json(RawResponse response) {
        try {
            return objectMapper.readTree(response.body());
        } catch (IOException e) {
            throw new AssertionError("Expected JSON with status " + response.status() + ": " + response.body(), e);
        }
    }

    /**
     * Returns the customer ids of a 200 search page in order.
     *
     * @param response the search response
     * @return the ids
     */
    private List<String> custIds(RawResponse response) {
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        List<String> ids = new ArrayList<>();
        json(response).path("items").forEach(item -> ids.add(item.path("custId").asText()));
        return ids;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static String pathOf(String target) {
        int query = target.indexOf('?');
        return query < 0 ? target : target.substring(0, query);
    }

    private void insertCustomer(String custId, String name, String state) {
        jdbcTemplate.update("INSERT INTO custmast (custid, name, addr, city, state, zip, active)"
                + " VALUES (?, ?, '1 MAIN ST', 'SPRINGFIELD', ?, '90210', 'Y')", custId, name, state);
    }

    // ---------------------------------------------------------------------------------------------
    // Raw HTTP/1.1
    // ---------------------------------------------------------------------------------------------

    /**
     * One HTTP response read from a raw connection.
     *
     * @param status the status code
     * @param headers the header fields, names in lower case; a repeated field keeps its last value
     * @param body the body, de-chunked and decoded as UTF-8
     */
    private record RawResponse(int status, Map<String, String> headers, String body) {

        /**
         * Returns a header field.
         *
         * @param name the field name, in any case
         * @return the value, or {@code null} when absent
         */
        @Nullable
        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    /**
     * Sends {@code GET target HTTP/1.1} to the application with the target exactly as given and reads
     * the whole response; the request asks for {@code Connection: close}, so the response ends at end of
     * stream.
     *
     * @param target the request target, written as US-ASCII bytes without any encoding
     * @param user the HTTP Basic username, or {@code null} for an anonymous request
     * @param password the password of {@code user}
     * @param xhr whether to send {@code X-Requested-With: XMLHttpRequest}
     * @return the response
     * @throws IOException when the connection fails
     */
    private RawResponse get(String target, @Nullable String user, @Nullable String password, boolean xhr)
            throws IOException {
        StringBuilder request = new StringBuilder()
                .append("GET ").append(target).append(" HTTP/1.1\r\n")
                .append("Host: localhost:").append(port).append("\r\n")
                .append("Connection: close\r\n");
        if (user != null) {
            String credentials = Base64.getEncoder()
                    .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
            request.append("Authorization: Basic ").append(credentials).append("\r\n");
        }
        if (xhr) {
            request.append(X_REQUESTED_WITH).append(": ").append(XML_HTTP_REQUEST).append("\r\n");
        }
        request.append("\r\n");
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout((int) READ_TIMEOUT.toMillis());
            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return parse(socket.getInputStream().readAllBytes());
        }
    }

    /**
     * Parses a complete HTTP/1.1 response.
     *
     * @param raw the bytes read until end of stream
     * @return the response
     * @throws IOException when the bytes are not a complete response
     */
    private static RawResponse parse(byte[] raw) throws IOException {
        int headerEnd = indexOf(raw, HEADER_END, 0);
        if (headerEnd < 0) {
            throw new IOException("No header section in " + raw.length + " bytes");
        }
        String[] lines = new String(raw, 0, headerEnd, StandardCharsets.ISO_8859_1).split("\r\n");
        String[] statusLine = lines[0].split(" ", 3);
        int status = Integer.parseInt(statusLine[1]);
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        lines[i].substring(colon + 1).trim());
            }
        }
        int bodyStart = headerEnd + HEADER_END.length;
        byte[] body = "chunked".equalsIgnoreCase(headers.get("transfer-encoding"))
                ? dechunk(raw, bodyStart)
                : Arrays.copyOfRange(raw, bodyStart, raw.length);
        return new RawResponse(status, headers, new String(body, StandardCharsets.UTF_8));
    }

    /**
     * Decodes a chunked body.
     *
     * @param raw the response bytes
     * @param start the offset of the first chunk-size line
     * @return the body
     * @throws IOException when the chunked framing is incomplete
     */
    private static byte[] dechunk(byte[] raw, int start) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int position = start;
        byte[] lineEnd = {'\r', '\n'};
        while (true) {
            int sizeEnd = indexOf(raw, lineEnd, position);
            if (sizeEnd < 0) {
                throw new IOException("Incomplete chunked body");
            }
            String sizeLine = new String(raw, position, sizeEnd - position, StandardCharsets.US_ASCII);
            int extension = sizeLine.indexOf(';');
            int size = Integer.parseInt((extension < 0 ? sizeLine : sizeLine.substring(0, extension)).trim(), 16);
            position = sizeEnd + lineEnd.length;
            if (size == 0) {
                return body.toByteArray();
            }
            if (position + size > raw.length) {
                throw new IOException("Chunk of " + size + " bytes is cut short");
            }
            body.write(raw, position, size);
            position += size + lineEnd.length;
        }
    }

    private static int indexOf(byte[] data, byte[] pattern, int from) {
        for (int i = from; i <= data.length - pattern.length; i++) {
            boolean match = true;
            for (int j = 0; j < pattern.length && match; j++) {
                match = data[i + j] == pattern[j];
            }
            if (match) {
                return i;
            }
        }
        return -1;
    }
}
