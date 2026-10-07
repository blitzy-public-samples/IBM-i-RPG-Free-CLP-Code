package com.democorp.customermaster.controller.dto;

import com.democorp.customermaster.service.CustomerMaintenanceService;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;

/**
 * Binds the {@code purpose} of a review request from exactly the JSON string {@code "ADD"} or
 * {@code "EDIT"}: the text must equal a {@link CustomerMaintenanceService.Purpose} constant name
 * character for character.
 *
 * <p>Jackson's default enum binding trims the text before its name lookup, so {@code " ADD"},
 * {@code "ADD "} and {@code "\tEDIT"} would select a constant, and its feature switches could also
 * admit ordinals, other cases or {@code toString()} forms. This deserializer consults none of them:
 * <ul>
 *   <li>a JSON string equal to a constant name binds that constant;</li>
 *   <li>any other JSON string ({@code "add"}, {@code " ADD"}, {@code "EDIT\n"}, {@code "0"},
 *       {@code ""}) fails through {@link DeserializationContext#handleWeirdStringValue}, an
 *       {@code InvalidFormatException};</li>
 *   <li>any other token (a number, a boolean, an object, an array) fails through
 *       {@link DeserializationContext#handleUnexpectedToken(Class, JsonParser)}, a
 *       {@code MismatchedInputException};</li>
 *   <li>JSON {@code null} and an absent member are never passed here: Jackson supplies the inherited
 *       null value, {@code null}, which {@code @NotNull} on the record component rejects.</li>
 * </ul>
 * Both failures are {@code MismatchedInputException}s whose path Jackson extends with the property
 * name, so the API answers 400 APP0400 with {@code errors[0].field} {@code purpose}.
 *
 * <p>The schema springdoc derives from the enum type, {@code enum: [ADD, EDIT]}, is unaffected.
 *
 * <p>Instances keep no mutable state and are thread-safe.
 */
public final class PurposeDeserializer extends StdDeserializer<CustomerMaintenanceService.Purpose> {

    /** Serialization version; {@link StdDeserializer} is {@code Serializable}. */
    private static final long serialVersionUID = 1L;

    /** Creates the deserializer; Jackson instantiates it from {@code @JsonDeserialize(using = ...)}. */
    public PurposeDeserializer() {
        super(CustomerMaintenanceService.Purpose.class);
    }

    /**
     * Reads the current token as a purpose.
     *
     * @param parser  the parser, positioned on the property's value
     * @param context the deserialization context that reports a mismatch
     * @return the constant whose name equals the JSON string
     * @throws IOException a {@code MismatchedInputException} when the value is not exactly
     *                     {@code "ADD"} or {@code "EDIT"}, or a read failure of the parser
     */
    @Override
    public CustomerMaintenanceService.Purpose deserialize(JsonParser parser, DeserializationContext context)
            throws IOException {
        if (!parser.hasToken(JsonToken.VALUE_STRING)) {
            return (CustomerMaintenanceService.Purpose) context.handleUnexpectedToken(
                    CustomerMaintenanceService.Purpose.class, parser);
        }
        String text = parser.getText();
        for (CustomerMaintenanceService.Purpose purpose : CustomerMaintenanceService.Purpose.values()) {
            if (purpose.name().equals(text)) {
                return purpose;
            }
        }
        return (CustomerMaintenanceService.Purpose) context.handleWeirdStringValue(
                CustomerMaintenanceService.Purpose.class, text, "not one of the values accepted: ADD, EDIT");
    }
}
