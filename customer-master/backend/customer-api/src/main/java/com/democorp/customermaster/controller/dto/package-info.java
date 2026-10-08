/**
 * The JSON wire contracts of {@code /api}: one record per request or response body or object nested
 * in one, with the {@link StorableText} constraint and the {@link PurposeDeserializer} they bind
 * with. The rules below hold for every record here.
 *
 * <p><b>Member order and the published schema.</b> A record's component order is its JSON member
 * order. The committed OpenAPI snapshot, {@code openapi/customer-master-api.yaml}, which
 * {@code OpenApiSnapshotIT} compares with the served document, and the frontend's
 * {@code src/api/schema.d.ts} list the properties alphabetically instead, because
 * {@code application.yml} sets {@code springdoc.writer-with-order-by-keys: true}. Adding, removing
 * or renaming a component changes both, which are then regenerated with
 * {@code ./mvnw -B -ntp verify -Dopenapi.snapshot.update=true} in {@code backend} followed by
 * {@code npm run gen:api} in {@code frontend}. Component names are the wire names and, for a
 * request body, also the {@code errors[].field} values of a problem+json response, which the UI
 * matches to its inputs, so they are not renamed.
 *
 * <p><b>Only the components are properties.</b> No record uses {@code @JsonUnwrapped}: the records
 * that share the nine data fields declare them, and a component whose type is a record is a nested
 * JSON object. The mapping methods ({@code from}, {@code of}, {@code toDraft}, {@code fields})
 * follow no getter convention, so Jackson and springdoc see the components and nothing else. The
 * {@code @Schema} and {@code @ArraySchema} annotations are documentation only: they publish which
 * members are required and which may be {@code null}, and change no serialization. No response
 * record carries {@code @JsonInclude}, so every component is always serialized and a {@code null}
 * member is sent as {@code null}.
 *
 * <p><b>Strict request binding.</b> The request records {@link CustomerFields},
 * {@link CustomerUpdateRequest} and {@link ReviewRequest} close their schema
 * ({@code additionalProperties: false}). The {@code spring.jackson} settings in
 * {@code application.yml}, with {@code config/StrictJsonBindingConfig} for text components, reject
 * with 400 APP0400, before any service runs, an unknown member, a member given twice, content after
 * the JSON object and a value of another JSON type than its component, which is never coerced: a
 * string for a number or boolean, a fraction or exponent for an integer, a number or boolean for
 * text. Each text limit counts code points, as the column does; the comment at the components of
 * {@link CustomerFields} gives the reason.
 *
 * <p>All records are immutable and therefore thread-safe.
 */
package com.democorp.customermaster.controller.dto;
