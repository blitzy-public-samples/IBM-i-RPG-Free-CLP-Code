package com.democorp.customermaster.controller;

import com.democorp.customermaster.controller.dto.CustomerFields;
import com.democorp.customermaster.controller.dto.Notice;
import com.democorp.customermaster.controller.dto.ReviewResponse;
import com.democorp.customermaster.controller.dto.SearchResponse;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.Schema;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * Completes the two success-body properties whose published shape swagger-core cannot derive from
 * the records' {@code @Schema} metadata.
 *
 * <p>The records in {@code controller/dto} state their OpenAPI shape with documentation-only
 * {@code @Schema} metadata: which members are always serialized ({@code required}) and which may be
 * {@code null}. That covers every member except two whose type is itself a component schema. For
 * those, swagger-core writes a bare {@code $ref} and places any further keyword beside it, where
 * openapi-typescript and other JSON Schema tooling ignore it. After springdoc has built the
 * document, this customizer replaces the two properties:
 *
 * <table>
 *   <caption>Rewritten properties</caption>
 *   <tr><th>Property</th><th>Published as</th><th>Why</th></tr>
 *   <tr><td>{@code SearchResponse.notice}</td>
 *       <td>{@code oneOf: [{$ref: Notice}, {type: "null"}]}</td>
 *       <td>A page without a message serializes {@code "notice": null}</td></tr>
 *   <tr><td>{@code ReviewResponse.customer}</td>
 *       <td>{@code allOf: [{$ref: CustomerFields}]}, with every {@code CustomerFields} property
 *       listed in {@code required}</td>
 *       <td>A successful review returns all nine fields, while {@code CustomerFields} as a request
 *       body keeps them optional</td></tr>
 * </table>
 *
 * <p>Both forms are OpenAPI 3.1 (JSON Schema 2020-12), the version springdoc publishes by default.
 * openapi-typescript renders them as {@code components["schemas"]["Notice"] | null} and
 * {@code WithRequired<components["schemas"]["CustomerFields"], ...>}. Nothing here changes
 * serialization: the bodies are the records the controllers return.
 *
 * <p>Each rewrite puts a newly built schema in place of the property, so applying the customizer
 * again yields the same document. A document without the owning schema, as a springdoc group that
 * leaves out its endpoint would produce, is left unchanged. An owning schema without the property
 * means the record component was renamed without this class; that fails with
 * {@link IllegalStateException} rather than publishing a contract the wire no longer matches.
 *
 * <p>Like the controllers, the bean exists only in the servlet web application, never in the
 * generator profile. It holds no state, so one instance serves every document build.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class DtoSchemaCustomizer implements OpenApiCustomizer {

    /** The {@link SearchResponse} component that is {@code null} on a page without a message. */
    static final String SEARCH_NOTICE = "notice";

    /** The {@link ReviewResponse} component that holds the nine reviewed fields. */
    static final String REVIEW_CUSTOMER = "customer";

    /** Creates the customizer. Spring instantiates it; it holds no state. */
    public DtoSchemaCustomizer() {
    }

    /**
     * Rewrites {@code SearchResponse.notice} and {@code ReviewResponse.customer} in the document
     * springdoc has built.
     *
     * @param openApi the document; its component schemas are changed in place
     * @throws IllegalStateException if an owning schema lacks the property this class rewrites, or
     *                               {@code ReviewResponse} is published without
     *                               {@code CustomerFields}
     */
    @Override
    public void customise(OpenAPI openApi) {
        Components components = openApi.getComponents();
        if (components == null || components.getSchemas() == null) {
            return;
        }
        nullableNotice(components);
        reviewedCustomer(components);
    }

    /**
     * Publishes {@code SearchResponse.notice} as a {@code Notice} or {@code null}.
     */
    private static void nullableNotice(Components components) {
        Schema<?> owner = components.getSchemas().get(SearchResponse.class.getSimpleName());
        if (owner == null) {
            return;
        }
        Schema<?> published = property(owner, SearchResponse.class, SEARCH_NOTICE);
        JsonSchema nullType = new JsonSchema();
        nullType.addType("null");
        JsonSchema noticeOrNull = new JsonSchema();
        noticeOrNull.addOneOfItem(reference(Notice.class));
        noticeOrNull.addOneOfItem(nullType);
        noticeOrNull.setDescription(published.getDescription());
        owner.getProperties().put(SEARCH_NOTICE, noticeOrNull);
    }

    /**
     * Publishes {@code ReviewResponse.customer} as {@code CustomerFields} with every property
     * required, leaving the {@code CustomerFields} schema itself, which request bodies use,
     * unchanged.
     */
    private static void reviewedCustomer(Components components) {
        Schema<?> owner = components.getSchemas().get(ReviewResponse.class.getSimpleName());
        if (owner == null) {
            return;
        }
        Schema<?> published = property(owner, ReviewResponse.class, REVIEW_CUSTOMER);
        Schema<?> fields = components.getSchemas().get(CustomerFields.class.getSimpleName());
        if (fields == null || fields.getProperties() == null || fields.getProperties().isEmpty()) {
            throw new IllegalStateException(ReviewResponse.class.getSimpleName()
                    + " is published without the properties of "
                    + CustomerFields.class.getSimpleName());
        }
        List<String> allFields = new ArrayList<>(new TreeSet<>(fields.getProperties().keySet()));
        JsonSchema reviewed = new JsonSchema();
        reviewed.addAllOfItem(reference(CustomerFields.class));
        reviewed.setRequired(allFields);
        reviewed.setDescription(published.getDescription());
        owner.getProperties().put(REVIEW_CUSTOMER, reviewed);
    }

    /**
     * Returns a {@code $ref} to the component schema springdoc names after the record.
     */
    private static JsonSchema reference(Class<? extends Record> dto) {
        JsonSchema reference = new JsonSchema();
        reference.set$ref(Components.COMPONENTS_SCHEMAS_REF + dto.getSimpleName());
        return reference;
    }

    /**
     * Returns the published schema of one record component.
     *
     * @throws IllegalStateException if the owning schema has no such property
     */
    private static Schema<?> property(Schema<?> owner, Class<? extends Record> dto, String name) {
        Schema<?> property = owner.getProperties() == null ? null : owner.getProperties().get(name);
        if (property == null) {
            throw new IllegalStateException(dto.getSimpleName() + " is published without its '"
                    + name + "' property");
        }
        return property;
    }
}
