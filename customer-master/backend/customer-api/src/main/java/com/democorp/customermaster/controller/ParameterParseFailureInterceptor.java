package com.democorp.customermaster.controller;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.catalina.Globals;
import org.apache.tomcat.util.http.Parameters.FailReason;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Rejects a request whose parameters Tomcat failed to parse, before the handler binds them, so a
 * parameter Tomcat dropped is never served as though it were absent.
 *
 * <p><b>Why.</b> Tomcat decodes the query string, and a form body, the first time a parameter is read.
 * When a name or value holds a {@code %} that is not followed by two hexadecimal digits, it logs
 * {@code Character decoding failed. Parameter [cursor] with value [%%%] has been ignored}, skips that
 * parameter and records the failure in the request attributes
 * {@value Globals#PARAMETER_PARSE_FAILED_ATTR} and {@value Globals#PARAMETER_PARSE_FAILED_REASON_ATTR}.
 * Nothing else reads them, so {@code GET /api/customers?cursor=%%%} answered 200 with the first page,
 * {@code name=ZQ%} the unfiltered list and {@code state=C%} neither DEM0007 nor a filter.
 *
 * <p><b>What it does.</b> On a REQUEST dispatch it reads the parameter map, which makes Tomcat parse the
 * parameters now, and when the failure attribute is set it throws a
 * {@link ParameterParseFailedException}. {@link ApiExceptionHandler} answers it, as every exception from
 * a handler interceptor, through {@link ProblemFactory}. The failure reason sets the status as Tomcat's
 * own {@code FailedRequestFilter} does:
 * <table>
 *   <caption>Tomcat failure reason to answer</caption>
 *   <tr><th>Reason</th><th>Answer</th></tr>
 *   <tr><td>{@code URL_DECODING}</td><td>400 {@code APP0400} "cursor has an invalid value", with
 *       {@code errors[0].field} {@code cursor}: the first query parameter, in query order, whose name or
 *       value cannot be decoded; "parameter has an invalid value" with no {@code errors} when its name
 *       is itself malformed or the failure lies in a form body</td></tr>
 *   <tr><td>{@code NO_NAME}</td><td>400 {@code APP0400} {@value #REASON_NO_NAME}</td></tr>
 *   <tr><td>{@code TOO_MANY_PARAMETERS}</td><td>400 {@code APP0400} {@value #REASON_TOO_MANY}</td></tr>
 *   <tr><td>{@code POST_TOO_LARGE}</td><td>413 {@code APP0400} {@value #REASON_BODY_TOO_LARGE}</td></tr>
 *   <tr><td>{@code IO_ERROR}, not the client's fault</td><td>500 {@code DEM9999} with an
 *       {@code errorId}</td></tr>
 *   <tr><td>any other reason</td><td>400 {@code APP0400} {@value #REASON_MALFORMED}</td></tr>
 * </table>
 * No reason quotes the value the client sent.
 *
 * <p><b>Where it runs.</b> As a handler interceptor it runs inside the {@code DispatcherServlet}, after
 * the whole servlet filter chain, so every 401 and 403 the security filter chain decides stands: an
 * anonymous {@code GET /api/customers?cursor=%%%} is still 401 {@code APP0401}. It runs after handler
 * mapping, so 405 and 406 keep precedence, and before argument binding, where the dropped parameter
 * would be lost. Other dispatcher types pass untouched: the ERROR dispatch to {@code /error} already
 * answers the original request's failure, and an ASYNC dispatch continues a request this interceptor
 * accepted.
 *
 * <p>It holds no state, so one instance serves all request threads.
 */
final class ParameterParseFailureInterceptor implements HandlerInterceptor {

    /** Reason of a parameter that has a value but no name, such as {@code ?=x}. */
    static final String REASON_NO_NAME = "parameter without a name";

    /** Reason of more parameters than the container accepts. */
    static final String REASON_TOO_MANY = "too many parameters";

    /** Reason of a form body over the container's size limit. */
    static final String REASON_BODY_TOO_LARGE = "request body too large";

    /** Reason of an I/O failure while reading a form body; answered as 500, so never shown. */
    static final String REASON_UNREADABLE = "request parameters could not be read";

    /** Reason of every other parse failure, such as a body cut short. */
    static final String REASON_MALFORMED = "malformed request parameters";

    /** Creates the interceptor, which needs no collaborator and no configuration. */
    ParameterParseFailureInterceptor() {
        super();
    }

    /**
     * Has Tomcat parse the parameters of a REQUEST dispatch and rejects the request when that failed.
     *
     * @param request the request
     * @param response the response; not written
     * @param handler the chosen handler; not used
     * @return {@code true} when the parameters were parsed without failure or the dispatch is not a
     *     REQUEST dispatch
     * @throws ParameterParseFailedException when Tomcat reported a parameter parse failure
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getDispatcherType() != DispatcherType.REQUEST) {
            return true;
        }
        // Reading any parameter makes Tomcat parse the query string and a form body now, once.
        request.getParameterMap();
        if (request.getAttribute(Globals.PARAMETER_PARSE_FAILED_ATTR) == null) {
            return true;
        }
        throw failureOf(request.getAttribute(Globals.PARAMETER_PARSE_FAILED_REASON_ATTR),
                request.getQueryString());
    }

    /**
     * Returns the exception for a Tomcat parse failure reason, by the table on this class.
     *
     * @param reason the value of {@value Globals#PARAMETER_PARSE_FAILED_REASON_ATTR}; anything but a
     *     {@link FailReason}, {@code null} included, is treated as an unknown reason
     * @param queryString the raw query string of the request; may be {@code null}
     * @return the exception to throw
     */
    static ParameterParseFailedException failureOf(@Nullable Object reason, @Nullable String queryString) {
        if (!(reason instanceof FailReason failReason)) {
            return ParameterParseFailedException.rejected(HttpStatus.BAD_REQUEST, REASON_MALFORMED);
        }
        return switch (failReason) {
            case URL_DECODING -> ParameterParseFailedException.undecodable(undecodableParameter(queryString));
            case NO_NAME -> ParameterParseFailedException.rejected(HttpStatus.BAD_REQUEST, REASON_NO_NAME);
            case TOO_MANY_PARAMETERS ->
                    ParameterParseFailedException.rejected(HttpStatus.BAD_REQUEST, REASON_TOO_MANY);
            case POST_TOO_LARGE ->
                    ParameterParseFailedException.rejected(HttpStatus.PAYLOAD_TOO_LARGE, REASON_BODY_TOO_LARGE);
            case IO_ERROR ->
                    ParameterParseFailedException.rejected(HttpStatus.INTERNAL_SERVER_ERROR, REASON_UNREADABLE);
            default -> ParameterParseFailedException.rejected(HttpStatus.BAD_REQUEST, REASON_MALFORMED);
        };
    }

    /**
     * Finds the query parameter Tomcat could not decode: the first {@code &}-separated chunk, in query
     * order, whose name (the text before its first {@code =}) or value holds a {@code %} not followed by
     * two hexadecimal digits, the rule of Tomcat's {@code UDecoder}. Chunks with an empty name are
     * skipped, because Tomcat never decodes them.
     *
     * @param queryString the raw query string; may be {@code null}
     * @return the decoded name of that parameter ({@code +} read as a space, the bytes as UTF-8, Tomcat's
     *     query-string encoding); {@code null} when the name is itself undecodable or no chunk is
     */
    @Nullable
    static String undecodableParameter(@Nullable String queryString) {
        if (queryString == null || queryString.isEmpty()) {
            return null;
        }
        for (String chunk : queryString.split("&", -1)) {
            int equals = chunk.indexOf('=');
            String name = equals < 0 ? chunk : chunk.substring(0, equals);
            String value = equals < 0 ? "" : chunk.substring(equals + 1);
            if (name.isEmpty()) {
                continue;
            }
            boolean nameDecodes = isDecodable(name);
            if (nameDecodes && isDecodable(value)) {
                continue;
            }
            return nameDecodes ? decode(name) : null;
        }
        return null;
    }

    /**
     * Tells whether every {@code %} of a raw query string part is followed by two hexadecimal digits.
     *
     * @param part a name or value as sent
     * @return {@code true} when Tomcat can decode it
     */
    private static boolean isDecodable(String part) {
        for (int index = part.indexOf('%'); index >= 0; index = part.indexOf('%', index + 3)) {
            if (index + 2 >= part.length()
                    || !isHexDigit(part.charAt(index + 1))
                    || !isHexDigit(part.charAt(index + 2))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Tells whether a character is an ASCII hexadecimal digit, the only digits Tomcat's {@code UDecoder}
     * accepts in an escape; {@link Character#digit(char, int)} would also accept full-width ones.
     *
     * @param c the character
     * @return {@code true} for {@code 0-9}, {@code a-f} and {@code A-F}
     */
    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /**
     * Decodes a raw query string part that {@link #isDecodable} accepts, as Tomcat does: {@code +} is a
     * space, {@code %XX} the byte {@code XX}, and the resulting bytes are read as UTF-8, any malformed
     * sequence becoming U+FFFD.
     *
     * @param part a decodable name as sent
     * @return the decoded text
     */
    private static String decode(String part) {
        byte[] raw = part.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream decoded = new ByteArrayOutputStream(raw.length);
        for (int index = 0; index < raw.length; index++) {
            byte current = raw[index];
            if (current == '+') {
                decoded.write(' ');
            } else if (current == '%') {
                decoded.write(Character.digit(raw[index + 1], 16) << 4 | Character.digit(raw[index + 2], 16));
                index += 2;
            } else {
                decoded.write(current);
            }
        }
        return decoded.toString(StandardCharsets.UTF_8);
    }
}
