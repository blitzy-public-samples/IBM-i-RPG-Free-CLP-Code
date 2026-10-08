package com.democorp.customermaster.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.democorp.customermaster.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Keeps the committed API contract, {@code customer-master/openapi/customer-master-api.yaml}, equal to
 * the document springdoc serves at {@value #API_DOCS_YAML}. The frontend generates
 * {@code src/api/schema.d.ts} from the committed file ({@code npm run gen:api}), so a controller or DTO
 * change that is not re-snapshotted fails here rather than drifting silently from the client types.
 *
 * <p>The document describes the REST resources that replace the IBM i program interfaces: the search
 * list and its I/M/S mode parameter (PMTCUSTR {@code pParmType}, {@code pCustID}), the detail
 * maintenance call (MTNCUSTR {@code pID}, {@code pMaintain}) and the state prompt's return slot
 * (PMTSTATER {@code pState}).
 *
 * <p><b>Updating the snapshot.</b> An intended API change is recorded by rerunning with
 * {@code -Dopenapi.snapshot.update=true}, for example
 * {@code ./mvnw -B -pl customer-api -am verify -Dit.test=OpenApiSnapshotIT -Dtest=none
 * -Dsurefire.failIfNoSpecifiedTests=false -Dopenapi.snapshot.update=true}, which rewrites the file and
 * passes; the rewritten file is then reviewed and committed. Without the flag the test only compares.
 *
 * <p><b>System properties</b>, set by the Failsafe {@code systemPropertyVariables} of
 * {@code customer-api/pom.xml}:
 * <ul>
 *   <li>{@value #SNAPSHOT_PATH_PROPERTY}: the snapshot file. When absent, as in an IDE run, the path
 *       {@value #DEFAULT_SNAPSHOT_PATH} is resolved against the working directory, which is the
 *       {@code customer-api} module directory under Failsafe.</li>
 *   <li>{@value #SNAPSHOT_UPDATE_PROPERTY}: {@code true} rewrites the snapshot instead of comparing.</li>
 * </ul>
 *
 * <p><b>Comparison.</b> Only line endings are normalized ({@link #normalize(String)}), so a Windows
 * checkout that converts the file to CRLF still matches. No other content is stripped or rewritten:
 * the document is port-independent by construction, because {@code CustomerMasterApplication}
 * declares the springdoc server as {@code /}, and {@code springdoc.writer-with-order-by-keys} makes the
 * key order deterministic. {@link #liveDocumentDescribesTheApiAndNoHostPort()} guards both properties,
 * so a document that names the random test port, or an empty document, is never accepted as the
 * snapshot.
 *
 * <p><b>Context.</b> The test runs on the base context of {@link AbstractPostgresIT}, with no
 * {@code @MockitoBean}, {@code @Import} or property override, so the snapshot is the document the
 * application serves in production configuration and this class adds no context variant. The document
 * is fetched anonymously, because {@value #API_DOCS_YAML} is a public path ({@code SecurityConfig}).
 */
class OpenApiSnapshotIT extends AbstractPostgresIT {

    private static final Logger LOG = LoggerFactory.getLogger(OpenApiSnapshotIT.class);

    /** The YAML form of the springdoc document ({@code springdoc.api-docs.path} plus {@code .yaml}). */
    private static final String API_DOCS_YAML = "/v3/api-docs.yaml";

    private static final String SNAPSHOT_PATH_PROPERTY = "openapi.snapshot.path";

    private static final String SNAPSHOT_UPDATE_PROPERTY = "openapi.snapshot.update";

    private static final String DEFAULT_SNAPSHOT_PATH = "../../openapi/customer-master-api.yaml";

    /** The command that regenerates the snapshot, quoted in failure messages. */
    private static final String UPDATE_COMMAND = "./mvnw -B verify -D" + SNAPSHOT_UPDATE_PROPERTY + "=true";

    /** Every resource path of the API contract the document must describe. */
    private static final List<String> API_PATHS = List.of(
            "/api/customers",
            "/api/customers/review",
            "/api/customers/{custId}",
            "/api/messages",
            "/api/session",
            "/api/states");

    private static final String SAME_ORIGIN_SERVER = "/";

    private static final String LOCALHOST_PORT = "localhost:";

    @Test
    void liveDocumentMatchesTheCommittedSnapshot() throws IOException {
        Path snapshot = snapshotPath();
        String live = normalize(fetchLiveDocument());

        if (Boolean.parseBoolean(System.getProperty(SNAPSHOT_UPDATE_PROPERTY, "false"))) {
            writeSnapshot(snapshot, live);
            return;
        }

        assertThat(Files.isRegularFile(snapshot))
                .as("OpenAPI snapshot not found at %s; generate it with %s", snapshot, UPDATE_COMMAND)
                .isTrue();
        String committed = normalize(Files.readString(snapshot, StandardCharsets.UTF_8));

        // The description is built only on failure; it names the first differing line, because the
        // full values AssertJ prints next to it are about a thousand lines each.
        assertThat(live)
                .as(() -> String.format("Live %s differs from %s. If the API change is intended, rerun with "
                                + "-D%s=true and commit the file. %s", API_DOCS_YAML, snapshot,
                        SNAPSHOT_UPDATE_PROPERTY, firstDifference(committed, live)))
                .isEqualTo(committed);
    }

    @Test
    void liveDocumentDescribesTheApiAndNoHostPort() {
        String live = fetchLiveDocument();
        Map<String, Object> document = parseYaml(live);

        assertThat(document.get("paths"))
                .as("the %s document must have a paths object", API_DOCS_YAML)
                .isInstanceOf(Map.class);
        Set<Object> paths = Set.copyOf(((Map<?, ?>) document.get("paths")).keySet());
        assertThat(paths)
                .as("resource paths described by %s", API_DOCS_YAML)
                .containsAll(API_PATHS);
        assertThat(document.get("servers"))
                .as("the servers of %s must be the same-origin server only", API_DOCS_YAML)
                .isEqualTo(List.of(Map.of("url", SAME_ORIGIN_SERVER)));
        assertThat(live)
                .as("%s must not depend on the test server's host and port (random port %s)",
                        API_DOCS_YAML, port)
                .doesNotContain(LOCALHOST_PORT);
    }

    /**
     * Fetches {@value #API_DOCS_YAML} anonymously and decodes it as UTF-8. The body is read as bytes
     * and decoded explicitly, because the YAML media type may carry no charset, in which case a
     * {@code String} conversion would default to ISO-8859-1 and garble characters such as the en dash
     * in parameter descriptions.
     *
     * @return the document exactly as served
     * @throws AssertionError if the status is not 200, the body is empty, or the body is not valid UTF-8
     */
    private String fetchLiveDocument() {
        ResponseEntity<byte[]> response = anonymous().get().uri(API_DOCS_YAML)
                .retrieve().toEntity(byte[].class);
        byte[] body = response.getBody();

        assertThat(response.getStatusCode())
                .as("GET %s answered %s with body: %s", API_DOCS_YAML, response.getStatusCode(),
                        body == null ? "<none>" : new String(body, StandardCharsets.UTF_8))
                .isEqualTo(HttpStatus.OK);
        assertThat(body)
                .as("GET %s must return a document", API_DOCS_YAML)
                .isNotNull()
                .isNotEmpty();
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new AssertionError("GET " + API_DOCS_YAML + " returned a body that is not valid UTF-8", e);
        }
    }

    /**
     * Normalizes line endings only: every CRLF becomes LF, and the text ends with exactly one LF.
     *
     * @param text a document, live or committed
     * @return the text with LF line endings and a single final newline
     */
    static String normalize(String text) {
        String lf = text.replace("\r\n", "\n");
        int end = lf.length();
        while (end > 0 && lf.charAt(end - 1) == '\n') {
            end--;
        }
        return lf.substring(0, end) + "\n";
    }

    /**
     * Describes the first line at which two normalized documents differ.
     *
     * @param committed the normalized snapshot
     * @param live      the normalized live document
     * @return {@code "First difference at line N: committed <...>, live <...>"}, where a document that
     *         ends before line N shows {@code <end of document>}, or {@code "No line differs."}
     */
    static String firstDifference(String committed, String live) {
        String[] committedLines = committed.split("\n", -1);
        String[] liveLines = live.split("\n", -1);
        int lines = Math.max(committedLines.length, liveLines.length);
        for (int i = 0; i < lines; i++) {
            String expected = i < committedLines.length ? committedLines[i] : null;
            String actual = i < liveLines.length ? liveLines[i] : null;
            if (expected == null || !expected.equals(actual)) {
                return String.format("First difference at line %d: committed %s, live %s", i + 1,
                        expected == null ? "<end of document>" : "<" + expected + ">",
                        actual == null ? "<end of document>" : "<" + actual + ">");
            }
        }
        return "No line differs.";
    }

    private static Path snapshotPath() {
        return Path.of(System.getProperty(SNAPSHOT_PATH_PROPERTY, DEFAULT_SNAPSHOT_PATH))
                .toAbsolutePath()
                .normalize();
    }

    /**
     * Writes the normalized live document to the snapshot as UTF-8, creating missing parent
     * directories. A snapshot that already equals the live document after line-ending normalization
     * is left untouched, so a CRLF checkout keeps its line endings and its timestamp.
     *
     * @param snapshot the snapshot file
     * @param live     the normalized live document
     * @throws IOException if a directory cannot be created or the file cannot be read or written
     */
    private static void writeSnapshot(Path snapshot, String live) throws IOException {
        if (Files.isRegularFile(snapshot)
                && live.equals(normalize(Files.readString(snapshot, StandardCharsets.UTF_8)))) {
            LOG.info("OpenAPI snapshot is up to date: {}", snapshot);
            return;
        }
        Path parent = snapshot.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(snapshot, live, StandardCharsets.UTF_8);
        LOG.info("OpenAPI snapshot written from {}: {}", API_DOCS_YAML, snapshot);
    }

    /**
     * Parses a YAML document with SnakeYAML's safe constructor, which builds only plain maps, lists
     * and scalars.
     *
     * @param yaml the document text
     * @return the top-level mapping
     * @throws AssertionError if the document is not a YAML mapping
     */
    private static Map<String, Object> parseYaml(String yaml) {
        Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        assertThat(root)
                .as("the %s document must be a YAML mapping", API_DOCS_YAML)
                .isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> document = (Map<String, Object>) root;
        return document;
    }
}
