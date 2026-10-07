package com.democorp.customermaster.controller;

import java.lang.reflect.RecordComponent;
import java.util.List;

import com.democorp.customermaster.controller.dto.CustomerFields;
import com.democorp.customermaster.controller.dto.CustomerUpdateRequest;
import com.democorp.customermaster.controller.dto.ReviewRequest;
import io.swagger.v3.core.util.SchemaTypeUtils;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import org.hibernate.validator.constraints.CodePointLength;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * Publishes the {@link CodePointLength} limits of the customer request records as the OpenAPI
 * {@code minLength} and {@code maxLength} of their properties, the same two keywords and values
 * swagger-core publishes for Bean Validation's {@code @Size}.
 *
 * <p><b>Why.</b> The nine text components of {@link CustomerFields}, {@link ReviewRequest} and
 * {@link CustomerUpdateRequest} limit each value to its column width counted in characters, which
 * only {@code @CodePointLength} expresses: {@code @Size} counts UTF-16 units and would reject, say,
 * a 21-character name of supplementary characters that its {@code varchar(40)} column stores.
 * swagger-core derives schema keywords from the {@code jakarta.validation} constraints only, so
 * without this customizer those properties would be published with no length at all. JSON Schema
 * counts {@code minLength} and {@code maxLength} in code points too, so the published limit is the
 * enforced one.
 *
 * <p><b>Mapping.</b> After springdoc has built the document, each component a record's
 * {@code @CodePointLength} annotates gets the constraint's {@code min} as {@code minLength} and its
 * {@code max} as {@code maxLength}, both always written, when its published schema is a string:
 * {@code @CodePointLength(max = 40)} becomes {@code maxLength: 40, minLength: 0}, as the committed
 * OpenAPI snapshot carries it. The constraint's normalization strategy is not published: JSON Schema
 * has no counterpart, and the records use the default, no normalization. A documentation-only
 * {@code @Schema(maxLength = n)} cannot stand in for this class: swagger-core writes a
 * {@code minLength} from {@code @Schema} only when it is above 0, so the published schema would
 * change.
 *
 * <p>Applying the customizer again yields the same document. A document without one of the records,
 * as a springdoc group that leaves out its endpoint would produce, is left unchanged for that record.
 * A record's schema without the property of an annotated component means the component was renamed
 * or hidden without this class; that fails with {@link IllegalStateException} rather than publishing
 * a contract the binder no longer matches.
 *
 * <p>Like the controllers, the bean exists only in the servlet web application, never in the
 * generator profile. It holds no state, so one instance serves every document build.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
final class CodePointLengthSchemaCustomizer implements OpenApiCustomizer {

    /** The request records whose text components carry {@link CodePointLength}. */
    static final List<Class<? extends Record>> REQUEST_RECORDS =
            List.of(CustomerFields.class, ReviewRequest.class, CustomerUpdateRequest.class);

    /**
     * Sets {@code minLength} and {@code maxLength} on every property of {@link #REQUEST_RECORDS} that
     * a {@link CodePointLength} constrains.
     *
     * @param openApi the document; its component schemas are changed in place
     * @throws IllegalStateException if a record's schema lacks the property of an annotated component
     */
    @Override
    public void customise(OpenAPI openApi) {
        Components components = openApi.getComponents();
        if (components == null || components.getSchemas() == null) {
            return;
        }
        for (Class<? extends Record> dto : REQUEST_RECORDS) {
            Schema<?> owner = components.getSchemas().get(dto.getSimpleName());
            if (owner != null) {
                publishLengths(owner, dto);
            }
        }
    }

    /**
     * Publishes the limits of one record's annotated components on its schema.
     *
     * @param owner the record's component schema
     * @param dto   the record
     * @throws IllegalStateException if {@code owner} lacks the property of an annotated component
     */
    private static void publishLengths(Schema<?> owner, Class<? extends Record> dto) {
        for (RecordComponent component : dto.getRecordComponents()) {
            // The constraint propagates from the record component to its accessor (target METHOD).
            CodePointLength length = component.getAccessor().getAnnotation(CodePointLength.class);
            if (length == null) {
                continue;
            }
            Schema<?> property = owner.getProperties() == null ? null
                    : owner.getProperties().get(component.getName());
            if (property == null) {
                throw new IllegalStateException(dto.getSimpleName() + " is published without its '"
                        + component.getName() + "' property");
            }
            if (SchemaTypeUtils.isStringSchema(property)) {
                property.setMinLength(length.min());
                property.setMaxLength(length.max());
            }
        }
    }
}
