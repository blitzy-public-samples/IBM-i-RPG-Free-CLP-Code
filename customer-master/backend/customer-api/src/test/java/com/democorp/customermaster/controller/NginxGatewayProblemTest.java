package com.democorp.customermaster.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.democorp.customermaster.messages.MessageCatalog;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.lang.Nullable;

/**
 * Holds the two error bodies the Compose {@code frontend}'s nginx writes itself under {@code /api/} to
 * the bodies {@link ProblemFactory} builds.
 *
 * <p><b>Why.</b> Every error body the API sends comes from {@link ProblemFactory}. When no API answer
 * exists for nginx to forward, because {@code app} is unreachable or its answer outlasts the
 * {@code proxy_read_timeout} cutoff, nginx answers 502 or 504 from the named locations
 * {@code @api_bad_gateway} and {@code @api_gateway_timeout} of {@code frontend/nginx.conf}, whose
 * bodies are text in that file. Each must equal what
 * {@code create(status, "DEM9999", List.of(), instance)} serializes to: the same members, values and
 * member order, the catalog's DEM9999 {@code detail} and the empty {@code args} included. A change to
 * the DEM9999 text or to the problem shape that is not carried into nginx.conf fails here.
 *
 * <p><b>{@code instance}.</b> nginx fills it from the map {@code $api_problem_instance} over
 * {@code $request_path_no_query}, the raw request path cut at its query, which is the
 * {@code getRequestURI()} that {@link ProblemFactory} receives. The test evaluates that map as nginx
 * does (exact keys, then regular expressions in order, then the default) with
 * {@link java.util.regex}, which reads its ASCII character classes, escapes and {@code \z} anchor as
 * PCRE does. The member must be present exactly when the path lies under {@code /api/} and holds only
 * characters a URI path allows as they are, so it is never broken JSON and always the path
 * {@link ProblemFactory} would report; any other path leaves the member out, and the body is then the
 * factory's body without {@code instance}. The bodies may reference no variable other than
 * {@code $api_problem_instance}, and the map's value none other than {@code $request_path_no_query}, so
 * nothing else from the request is echoed.
 *
 * <p><b>Wiring.</b> {@code location /api/} must route nginx's 502 and 504 to those named locations,
 * and each must answer {@code application/problem+json}.
 *
 * <p><b>Configuration file.</b> System property {@value #NGINX_CONF_PATH_PROPERTY} names it. When
 * absent, the path {@value #DEFAULT_NGINX_CONF_PATH} is resolved against the working directory, which
 * is the {@code customer-api} module directory under Surefire.
 *
 * <p>Pure JUnit 5 and AssertJ over the real factory, with the real catalog and a mapper carrying
 * Spring's {@code ProblemDetail} mixin, as Boot configures it: no Spring context, no database, no
 * Docker, no nginx.
 */
@DisplayName("nginx.conf: nginx's own 502 and 504 under /api/ are ProblemFactory's DEM9999 problems")
final class NginxGatewayProblemTest {

    /** System property naming the nginx configuration file. */
    private static final String NGINX_CONF_PATH_PROPERTY = "nginx.conf.path";

    /** The nginx configuration file relative to the {@code customer-api} module directory. */
    private static final String DEFAULT_NGINX_CONF_PATH = "../../frontend/nginx.conf";

    /** The catalog key of every body nginx writes itself. */
    private static final String PROGRAM_ERROR = "DEM9999";

    /** The variable nginx's bodies insert the {@code instance} member from. */
    private static final String INSTANCE_VARIABLE = "api_problem_instance";

    /** The source variable of the {@code instance} map: the raw request path without its query. */
    private static final String PATH_VARIABLE = "request_path_no_query";

    /** A request path under {@code /api/}, expected back as {@code instance}. */
    private static final String SAMPLE_PATH = "/api/customers/AAAD";

    /**
     * The characters RFC 3986 allows in a path as they are, apart from the {@code %} of an escape: the
     * unreserved characters, the sub-delimiters, {@code :}, {@code @} and the separator {@code /}.
     * {@link java.net.URI} accepts the same set in a path, so {@link ProblemFactory} keeps a path made
     * of them, and of well-formed escapes, unchanged.
     */
    private static final String PATH_CHARACTERS_AS_IS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~!$&'()*+,;=:@/";

    /** A variable reference in an nginx value: {@code $name} or {@code ${name}}. */
    private static final Pattern VARIABLE = Pattern.compile("\\$(?:\\{(\\w+)}|(\\w+))");

    /** A mapper carrying Spring's {@code ProblemDetail} mixin, so properties are top-level members. */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class);

    /** Reads bodies and rejects a member that appears twice, which a plain tree read would overwrite. */
    private static final ObjectMapper STRICT_READER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    /** The real factory, with the real catalog. */
    private static final ProblemFactory PROBLEMS = new ProblemFactory(new MessageCatalog(), MAPPER);

    /** The parsed top level of nginx.conf: the directives of the {@code http} block it is included in. */
    private static List<Directive> config;

    /** The {@code $api_problem_instance} map. */
    private static NginxMap instanceMap;

    /** The two statuses nginx answers itself under {@code /api/}, with their named locations. */
    enum Gateway {

        /** {@code app} is stopped, refuses connections or cannot be resolved. */
        BAD_GATEWAY(HttpStatus.BAD_GATEWAY, "@api_bad_gateway"),

        /** The connect or the API's answer outlasts its timeout. */
        GATEWAY_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "@api_gateway_timeout");

        /** The status nginx answers. */
        private final HttpStatus status;

        /** The named location that writes the body. */
        private final String location;

        /**
         * Pairs a status with its named location.
         *
         * @param status the status nginx answers
         * @param location the named location that writes the body
         */
        Gateway(HttpStatus status, String location) {
            this.status = status;
            this.location = location;
        }
    }

    /**
     * Reads and parses nginx.conf once, and finds the {@code instance} map.
     *
     * @throws IOException if the file cannot be read
     */
    @BeforeAll
    static void readConfig() throws IOException {
        Path file = Path.of(System.getProperty(NGINX_CONF_PATH_PROPERTY, DEFAULT_NGINX_CONF_PATH))
                .toAbsolutePath()
                .normalize();
        assertThat(file).as("nginx configuration (system property %s)", NGINX_CONF_PATH_PROPERTY)
                .isRegularFile();
        config = parse(Files.readString(file, StandardCharsets.UTF_8));
        Directive map = only(config, d -> d.isBlock("map")
                && d.args().equals(List.of("$" + PATH_VARIABLE, "$" + INSTANCE_VARIABLE)),
                "map $" + PATH_VARIABLE + " $" + INSTANCE_VARIABLE);
        instanceMap = NginxMap.of(map);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Gateway.class)
    @DisplayName("the body for a path under /api/ is ProblemFactory's, with the path as instance")
    void bodyWithInstanceIsProblemFactorysBody(Gateway gateway) throws IOException {
        assertThat(instanceMap.valueFor(SAMPLE_PATH)).isNotEmpty();

        assertSameProblem(nginxBody(gateway, SAMPLE_PATH), factoryBody(gateway, SAMPLE_PATH));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Gateway.class)
    @DisplayName("the body for a path holding a double quote is ProblemFactory's without instance")
    void bodyWithoutInstanceIsProblemFactorysBody(Gateway gateway) throws IOException {
        String quoted = "/api/x\"y";
        assertThat(instanceMap.valueFor(quoted)).isEmpty();

        assertSameProblem(nginxBody(gateway, quoted), factoryBody(gateway, null));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Gateway.class)
    @DisplayName("location /api/ routes the status to its named location, which answers problem+json")
    void statusReachesItsNamedLocationAsProblemJson(Gateway gateway) {
        Directive server = only(config, d -> d.isBlock("server"), "server");
        Directive api = only(server.children(), d -> d.isBlock("location") && d.args().equals(List.of("/api/")),
                "location /api/");
        only(api.children(), d -> d.name().equals("error_page")
                && d.args().equals(List.of(String.valueOf(gateway.status.value()), gateway.location)),
                "error_page " + gateway.status.value() + " " + gateway.location);

        Directive named = namedLocation(gateway);
        Directive defaultType = only(named.children(), d -> d.name().equals("default_type"), "default_type");
        assertThat(defaultType.args()).containsExactly("application/problem+json");
        Directive types = only(named.children(), d -> d.isBlock("types"), "types");
        assertThat(types.children()).as("the empty types block of %s", gateway.location).isEmpty();
    }

    @ParameterizedTest(name = "<{0}>")
    @ValueSource(strings = {"/api/customers/AAAD", "/api/", "/api/a%2Fb", "/api/customers/A%20AD",
            "/api/%E2%82%AC", "/api/x$y'(z)*+,;=:@!~._-"})
    @DisplayName("a path under /api/ that a URI keeps as it is becomes instance unchanged")
    void usablePathBecomesInstance(String path) throws IOException {
        assertThat(instanceMap.valueFor(path)).isNotEmpty();

        for (Gateway gateway : Gateway.values()) {
            assertSameProblem(nginxBody(gateway, path), factoryBody(gateway, path));
        }
    }

    @ParameterizedTest(name = "<{0}>")
    @ValueSource(strings = {"", "/", "/healthz", "/apix/a", "/API/a", "/x/../api/a", "api/a",
            "/api/x\"y", "/api/x\\y", "/api/a b", "/api/a\tb", "/api/a%zz", "/api/a%2", "/api/a%",
            "/api/a?b", "/api/a#b", "/api/caf\u00e9", "/api/a\u2028b", "/api/a\n", "/api/a\r\n"})
    @DisplayName("any other path leaves instance out")
    void otherPathLeavesInstanceOut(String path) throws IOException {
        assertThat(instanceMap.valueFor(path)).isEmpty();

        for (Gateway gateway : Gateway.values()) {
            assertSameProblem(nginxBody(gateway, path), factoryBody(gateway, null));
        }
    }

    @Test
    @DisplayName("instance is set for exactly the characters a URI path allows as they are")
    void instanceIsSetForExactlyThePathCharacters() throws IOException {
        List<String> mismatches = new ArrayList<>();
        for (char c = 0; c < 0x80; c++) {
            String path = "/api/a" + c + "b";
            boolean expected = PATH_CHARACTERS_AS_IS.indexOf(c) >= 0;
            boolean actual = !instanceMap.valueFor(path).isEmpty();
            if (actual != expected) {
                mismatches.add(String.format("U+%04X %s", (int) c, actual ? "accepted" : "rejected"));
                continue;
            }
            for (Gateway gateway : Gateway.values()) {
                assertSameProblem(nginxBody(gateway, path), factoryBody(gateway, expected ? path : null));
            }
        }

        assertThat(mismatches).as("characters the instance map judges wrongly").isEmpty();
    }

    /**
     * Asserts that two problem bodies are the same JSON object with the same top-level member order.
     *
     * @param actual the body nginx writes
     * @param expected the body {@link ProblemFactory} builds
     * @throws IOException if either body is not JSON or repeats a member
     */
    private static void assertSameProblem(String actual, String expected) throws IOException {
        JsonNode actualTree = STRICT_READER.readTree(actual);
        JsonNode expectedTree = STRICT_READER.readTree(expected);

        assertThat(actualTree).as("nginx body %s", actual).isEqualTo(expectedTree);
        assertThat(memberNames(actualTree)).as("member order of %s", actual)
                .containsExactlyElementsOf(memberNames(expectedTree));
    }

    /**
     * Returns the top-level member names of a JSON object in document order.
     *
     * @param tree the parsed object
     * @return the names
     */
    private static List<String> memberNames(JsonNode tree) {
        assertThat(tree.isObject()).as("a JSON object: %s", tree).isTrue();
        return tree.properties().stream().map(Map.Entry::getKey).toList();
    }

    /**
     * Returns the body {@link ProblemFactory} builds for DEM9999 at the gateway's status, serialized as
     * {@link ProblemFactory#write} serializes it.
     *
     * @param gateway the status
     * @param instance the request path, or {@code null} for none
     * @return the JSON text
     * @throws IOException if serialization fails
     */
    private static String factoryBody(Gateway gateway, @Nullable String instance) throws IOException {
        ProblemDetail problem = PROBLEMS.create(gateway.status, PROGRAM_ERROR, List.of(), instance);
        return new String(MAPPER.writeValueAsBytes(problem), StandardCharsets.UTF_8);
    }

    /**
     * Returns the body nginx writes for a request path: the {@code return} text of the gateway's named
     * location with {@code $api_problem_instance} expanded through the map. The location must return
     * the gateway's status.
     *
     * @param gateway the status
     * @param path the raw request path without its query
     * @return the JSON text
     */
    private static String nginxBody(Gateway gateway, String path) {
        Directive named = namedLocation(gateway);
        Directive answer = only(named.children(), d -> d.name().equals("return"), "return in " + gateway.location);
        assertThat(answer.args()).as("return in %s", gateway.location).hasSize(2);
        assertThat(answer.args().get(0)).isEqualTo(String.valueOf(gateway.status.value()));
        return expand(answer.args().get(1), Map.of(INSTANCE_VARIABLE, instanceMap.valueFor(path)));
    }

    /**
     * Returns the server's named location of a gateway status.
     *
     * @param gateway the status
     * @return the {@code location} block
     */
    private static Directive namedLocation(Gateway gateway) {
        Directive server = only(config, d -> d.isBlock("server"), "server");
        return only(server.children(), d -> d.isBlock("location") && d.args().equals(List.of(gateway.location)),
                "location " + gateway.location);
    }

    /**
     * Expands the variable references of an nginx value, failing on any variable not given, so a value
     * that echoes anything else from the request fails the test.
     *
     * @param template the value as written in nginx.conf
     * @param variables the values of the variables it may reference
     * @return the expanded text
     */
    private static String expand(String template, Map<String, String> variables) {
        Matcher reference = VARIABLE.matcher(template);
        StringBuilder text = new StringBuilder();
        while (reference.find()) {
            String name = reference.group(1) != null ? reference.group(1) : reference.group(2);
            assertThat(variables).as("variables %s may reference", template).containsKey(name);
            reference.appendReplacement(text, Matcher.quoteReplacement(variables.get(name)));
        }
        reference.appendTail(text);
        return text.toString();
    }

    /**
     * Returns the one directive of a list that matches.
     *
     * @param directives the directives of one level
     * @param match the predicate
     * @param description what is looked for, for the failure message
     * @return the directive
     */
    private static Directive only(List<Directive> directives, Predicate<Directive> match, String description) {
        List<Directive> found = directives.stream().filter(match).toList();
        assertThat(found).as("exactly one %s in nginx.conf", description).hasSize(1);
        return found.get(0);
    }

    /**
     * One nginx directive: its name, its arguments with quotes removed, and the directives of its block.
     *
     * @param name the directive name
     * @param args the arguments
     * @param block the directives of its block, or {@code null} for a simple directive
     */
    private record Directive(String name, List<String> args, @Nullable List<Directive> block) {

        /**
         * Tells whether this is a block directive of the given name.
         *
         * @param blockName the name
         * @return {@code true} for a {@code blockName ... { }} directive
         */
        boolean isBlock(String blockName) {
            return block != null && name.equals(blockName);
        }

        /**
         * Returns the directives of this block.
         *
         * @return the directives
         * @throws IllegalStateException for a simple directive
         */
        List<Directive> children() {
            if (block == null) {
                throw new IllegalStateException(name + " has no block");
            }
            return block;
        }
    }

    /**
     * One token of nginx.conf: a word, unquoted, or one of the delimiters {@code ;}, <code>{</code> and
     * <code>}</code>.
     *
     * @param text the word or the delimiter
     * @param delimiter {@code true} for a delimiter
     */
    private record Token(String text, boolean delimiter) {
    }

    /**
     * An nginx {@code map} as nginx evaluates it: an exact key first, then the regular expressions in
     * the order written, then the default ({@code ""} when none is given). Every value is taken as
     * written; the caller expands its variables.
     *
     * @param defaultValue the value when nothing matches
     * @param exact the exact keys and their values
     * @param patterns the regular expressions and their values, in order
     */
    private record NginxMap(String defaultValue, Map<String, String> exact, Map<Pattern, String> patterns) {

        /**
         * Reads a {@code map} block whose keys are plain strings or {@code ~} / {@code ~*} regular
         * expressions; any other directive in the block fails the test.
         *
         * @param map the block
         * @return the map
         */
        static NginxMap of(Directive map) {
            String defaultValue = "";
            Map<String, String> exact = new LinkedHashMap<>();
            Map<Pattern, String> patterns = new LinkedHashMap<>();
            for (Directive entry : map.children()) {
                assertThat(entry.block()).as("map entry %s", entry.name()).isNull();
                assertThat(entry.args()).as("map entry %s", entry.name()).hasSize(1);
                String key = entry.name();
                String value = entry.args().get(0);
                if (key.equals("default")) {
                    defaultValue = value;
                } else if (key.startsWith("~*")) {
                    patterns.put(Pattern.compile(key.substring(2), Pattern.CASE_INSENSITIVE), value);
                } else if (key.startsWith("~")) {
                    patterns.put(Pattern.compile(key.substring(1)), value);
                } else {
                    exact.put(key.startsWith("\\") ? key.substring(1) : key, value);
                }
            }
            return new NginxMap(defaultValue, Map.copyOf(exact), patterns);
        }

        /**
         * Returns the expanded value of the map for a source string: {@code $request_path_no_query}
         * becomes the source itself.
         *
         * @param source the value of the map's source variable
         * @return the value
         */
        String valueFor(String source) {
            String value = exact.get(source);
            if (value == null) {
                value = patterns.entrySet().stream()
                        .filter(entry -> entry.getKey().matcher(source).find())
                        .map(Map.Entry::getValue)
                        .findFirst()
                        .orElse(defaultValue);
            }
            return expand(value, Map.of(PATH_VARIABLE, source));
        }
    }

    /**
     * Parses nginx.conf into its top-level directives.
     *
     * @param text the file content
     * @return the directives
     */
    private static List<Directive> parse(String text) {
        Iterator<Token> tokens = tokenize(text).iterator();
        return parseBlock(tokens, false);
    }

    /**
     * Parses directives up to the end of the enclosing block, or of the file at the top level.
     *
     * @param tokens the remaining tokens
     * @param nested {@code true} inside a block, which must end with <code>}</code>
     * @return the directives
     */
    private static List<Directive> parseBlock(Iterator<Token> tokens, boolean nested) {
        List<Directive> directives = new ArrayList<>();
        List<String> words = new ArrayList<>();
        while (tokens.hasNext()) {
            Token token = tokens.next();
            if (!token.delimiter()) {
                words.add(token.text());
                continue;
            }
            switch (token.text()) {
                case ";" -> directives.add(directive(words, null));
                case "{" -> directives.add(directive(words, parseBlock(tokens, true)));
                default -> {
                    if (!nested || !words.isEmpty()) {
                        throw new IllegalStateException("nginx.conf: unexpected } after " + words);
                    }
                    return List.copyOf(directives);
                }
            }
            words.clear();
        }
        if (nested || !words.isEmpty()) {
            throw new IllegalStateException("nginx.conf: unexpected end of file after " + words);
        }
        return List.copyOf(directives);
    }

    /**
     * Builds a directive from the words before its delimiter.
     *
     * @param words the name and the arguments
     * @param block the block's directives, or {@code null} for a simple directive
     * @return the directive
     */
    private static Directive directive(List<String> words, @Nullable List<Directive> block) {
        if (words.isEmpty()) {
            throw new IllegalStateException("nginx.conf: a delimiter without a directive");
        }
        return new Directive(words.get(0), List.copyOf(words.subList(1, words.size())), block);
    }

    /**
     * Splits nginx.conf into tokens as nginx does: a {@code #} at the start of a token comments out the
     * rest of the line; a word in single or double quotes may hold spaces and delimiters, and
     * {@code \"}, {@code \'}, {@code \\}, {@code \t}, {@code \r} and {@code \n} are unescaped in it while
     * any other backslash is kept; an unquoted word ends at a space or a delimiter, except inside a
     * <code>${name}</code> reference.
     *
     * @param text the file content
     * @return the tokens
     */
    private static List<Token> tokenize(String text) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '#') {
                while (i < text.length() && text.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == ';' || c == '{' || c == '}') {
                tokens.add(new Token(String.valueOf(c), true));
                i++;
            } else if (c == '"' || c == '\'') {
                StringBuilder word = new StringBuilder();
                i++;
                while (true) {
                    if (i >= text.length()) {
                        throw new IllegalStateException("nginx.conf: unterminated quoted string");
                    }
                    char d = text.charAt(i);
                    if (d == c) {
                        i++;
                        break;
                    }
                    if (d == '\\' && i + 1 < text.length()) {
                        char e = text.charAt(i + 1);
                        String unescaped = switch (e) {
                            case '"', '\'', '\\' -> String.valueOf(e);
                            case 't' -> "\t";
                            case 'r' -> "\r";
                            case 'n' -> "\n";
                            default -> null;
                        };
                        if (unescaped != null) {
                            word.append(unescaped);
                            i += 2;
                            continue;
                        }
                    }
                    word.append(d);
                    i++;
                }
                tokens.add(new Token(word.toString(), false));
            } else {
                int start = i;
                while (i < text.length()) {
                    char d = text.charAt(i);
                    if (d == '$' && i + 1 < text.length() && text.charAt(i + 1) == '{') {
                        int close = text.indexOf('}', i);
                        if (close < 0) {
                            throw new IllegalStateException("nginx.conf: unterminated ${ reference");
                        }
                        i = close + 1;
                    } else if (Character.isWhitespace(d) || d == ';' || d == '{' || d == '}') {
                        break;
                    } else {
                        i++;
                    }
                }
                tokens.add(new Token(text.substring(start, i), false));
            }
        }
        return tokens;
    }
}
