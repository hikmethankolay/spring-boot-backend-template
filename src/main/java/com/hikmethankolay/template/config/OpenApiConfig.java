package com.hikmethankolay.template.config;

import com.hikmethankolay.template.common.ApiDocs;
import com.hikmethankolay.template.common.ProblemType;
import com.hikmethankolay.template.common.SortableBy;
import com.hikmethankolay.template.common.TraceIdResponseFilter;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;

/**
 * OpenAPI 3 document served at {@code /v3/api-docs} and rendered by Swagger UI at {@code /swagger-ui.html}.
 *
 * <p>springdoc builds the document from the controllers; this class adds what it cannot infer:</p>
 * <ul>
 *   <li>the RFC 9457 {@code Problem} schema with its {@link ProblemType}s, and the error responses every
 *       operation can return (400 for operations with input, 404 for operations on {@code {id}}, 429 and 500
 *       everywhere), declared once under {@code components.responses} and referenced;</li>
 *   <li>the {@code X-Trace-Id} and {@code RateLimit-*} headers on every response, {@code Retry-After} on 429 and
 *       {@code WWW-Authenticate} on 401, declared once under {@code components.headers} and referenced;</li>
 *   <li>the tags with their descriptions, the required fields of page responses, and password fields
 *       marked {@code writeOnly} with {@code format: password};</li>
 *   <li>the page size limit ({@code spring.data.web.pageable.max-page-size}) on {@code size} parameters;</li>
 *   <li>the allowed properties of {@code sort} parameters, from {@link SortableBy};</li>
 *   <li>a relative server URL, so Swagger UI and generated clients call the host that serves the document.</li>
 * </ul>
 *
 * <p>Security schemes, public endpoints and 401/403 responses are added by
 * {@code com.hikmethankolay.template.security.ApiDocsSecurityCustomizer}.</p>
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    /** Component name of the problem detail schema. */
    static final String PROBLEM = "Problem";

    /** Component name of a field-level validation error. */
    static final String FIELD_VIOLATION = "FieldViolation";

    /** Component name of the 400 response of operations with input. */
    static final String BAD_REQUEST = "BadRequest";

    /** Component name of the 401 response of protected operations. */
    public static final String UNAUTHORIZED = "Unauthorized";

    /** Component name of the 403 response of operations that need a role. */
    public static final String FORBIDDEN = "Forbidden";

    /** Component name of the 404 response of operations on {@code {id}}. */
    static final String USER_NOT_FOUND = "UserNotFound";

    /** Component name of the 429 response of every operation. */
    static final String TOO_MANY_REQUESTS = "TooManyRequests";

    /** Component name of the 500 response of every operation. */
    static final String INTERNAL_SERVER_ERROR = "InternalServerError";

    private static final String RESPONSES_REF = "#/components/responses/";

    private static final String HEADERS_REF = "#/components/headers/";

    private static final Map<String, String> COMMON_HEADERS = orderedMap(
            TraceIdResponseFilter.HEADER,
            "Trace id of the request; quote it when reporting an error",
            "RateLimit-Limit",
            "Requests allowed per minute for the client IP",
            "RateLimit-Remaining",
            "Requests left in the current window",
            "RateLimit-Reset",
            "Seconds until the window resets");

    private static final String PASSWORD_RULE = "15-128 characters (8 for users with two-factor authentication), "
            + "no composition rules; common and breached passwords are rejected";

    /** Creates the configuration; Spring instantiates it. */
    public OpenApiConfig() {
        // Nothing to initialise.
    }

    /**
     * Describes the API.
     *
     * @param applicationName value of {@code spring.application.name}, used as the document title
     * @return the OpenAPI model that springdoc merges with the scanned endpoints
     */
    @Bean
    OpenAPI openApi(@Value("${spring.application.name}") String applicationName) {
        return new OpenAPI()
                .info(new Info()
                        .title(applicationName)
                        .version("v1")
                        .description("REST API. Errors are RFC 9457 problem details (application/problem+json).")
                        .license(new License().name("MIT").url("https://opensource.org/license/mit")))
                .servers(List.of(new Server().url("/").description("The host serving this document")))
                .tags(List.of(
                        new Tag().name("Authentication").description("Registration, login, token refresh and logout"),
                        new Tag()
                                .name("Account")
                                .description("Email verification and password reset, "
                                        + "confirmed with a token sent by email"),
                        new Tag()
                                .name("Users")
                                .description("The current user's profile and data, and user " + "administration")))
                .components(components());
    }

    /**
     * The shared schemas, response headers and error responses that operations reference.
     *
     * @return the components
     */
    private static Components components() {
        Components components = new Components()
                .addSchemas(PROBLEM, problemSchema())
                .addSchemas(FIELD_VIOLATION, fieldViolationSchema());
        COMMON_HEADERS.forEach((name, description) -> components.addHeaders(
                name, header(description, name.startsWith("RateLimit") ? new IntegerSchema() : new StringSchema())));
        components.addHeaders(HttpHeaders.RETRY_AFTER, header("Seconds to wait before retrying", new IntegerSchema()));
        components.addHeaders(
                HttpHeaders.WWW_AUTHENTICATE, header("Authentication scheme to use: `Bearer`", new StringSchema()));

        components.addResponses(BAD_REQUEST, problem("Invalid input; validation errors are listed in `errors`"));
        components.addResponses(
                UNAUTHORIZED,
                problem("Missing, invalid, expired or revoked access token")
                        .addHeaderObject(HttpHeaders.WWW_AUTHENTICATE, headerRef(HttpHeaders.WWW_AUTHENTICATE)));
        components.addResponses(FORBIDDEN, problem("Authenticated, but not allowed"));
        components.addResponses(USER_NOT_FOUND, problem("No user with this id"));
        components.addResponses(
                TOO_MANY_REQUESTS,
                problem("Too many requests or login attempts; wait for " + "`Retry-After` seconds")
                        .addHeaderObject(HttpHeaders.RETRY_AFTER, headerRef(HttpHeaders.RETRY_AFTER)));
        components.addResponses(
                INTERNAL_SERVER_ERROR, problem("Unexpected server error; quote `X-Trace-Id` when " + "reporting it"));
        components.getResponses().values().forEach(response -> addCommonHeaders(null, response));
        return components;
    }

    /**
     * Adds the shared error responses, response headers and the page size limit to every operation, and
     * completes the schemas springdoc generates. Runs last, so responses added by other customizers get
     * the headers too.
     *
     * @param maxPageSize value of {@code spring.data.web.pageable.max-page-size}
     * @return the customizer springdoc applies after scanning the controllers
     */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    OpenApiCustomizer errorResponsesAndHeaders(
            @Value("${spring.data.web.pageable.max-page-size:2000}") int maxPageSize) {
        return openApi -> {
            openApi.getPaths()
                    .forEach((path, item) -> item.readOperations().forEach(operation -> {
                        ApiResponses responses = operation.getResponses();
                        if (hasInput(operation)) {
                            responses.putIfAbsent("400", responseRef(BAD_REQUEST));
                        }
                        if (path.contains("{id}")) {
                            responses.putIfAbsent("404", responseRef(USER_NOT_FOUND));
                        }
                        responses.putIfAbsent("429", responseRef(TOO_MANY_REQUESTS));
                        responses.putIfAbsent("500", responseRef(INTERNAL_SERVER_ERROR));
                        responses.forEach(OpenApiConfig::addCommonHeaders);
                        if (operation.getParameters() != null) {
                            operation.getParameters().stream()
                                    .filter(parameter ->
                                            "size".equals(parameter.getName()) && parameter.getSchema() != null)
                                    .forEach(parameter ->
                                            parameter.getSchema().setMaximum(BigDecimal.valueOf(maxPageSize)));
                        }
                    }));
            completeSchemas(openApi.getComponents().getSchemas());
        };
    }

    /**
     * Fills in what springdoc cannot infer from the classes: Spring Data's page responses always contain
     * every field, and password fields are only ever sent, never returned.
     *
     * @param schemas the component schemas, by name
     */
    private static void completeSchemas(Map<String, Schema> schemas) {
        schemas.forEach((name, schema) -> {
            if (name.startsWith("PagedModel")) {
                schema.setRequired(List.of("content", "page"));
            }
            if ("PageMetadata".equals(name)) {
                schema.setRequired(List.of("number", "size", "totalElements", "totalPages"));
            }
            Map<String, Schema> properties = schema.getProperties();
            if (properties != null) {
                properties.forEach((property, propertySchema) -> {
                    if (property.toLowerCase(Locale.ROOT).endsWith("password")) {
                        propertySchema.setFormat("password");
                        propertySchema.setWriteOnly(true);
                        // Only new passwords (@ValidPassword) have a minimum length.
                        if (propertySchema.getMinLength() != null && propertySchema.getMinLength() > 0) {
                            propertySchema.setDescription(PASSWORD_RULE);
                        }
                    }
                });
            }
        });
    }

    /**
     * Documents the allowed properties of the {@code sort} parameter on operations annotated with
     * {@link SortableBy}: they are listed in the description and enforced by a pattern.
     *
     * @return the customizer springdoc applies to every operation
     */
    @Bean
    OperationCustomizer sortableProperties() {
        return (operation, handlerMethod) -> {
            SortableBy sortable = handlerMethod.getMethodAnnotation(SortableBy.class);
            if (sortable != null && operation.getParameters() != null) {
                String properties = sortable.value();
                String alternatives = String.join("|", properties.split(", "));
                operation.getParameters().stream()
                        .filter(parameter -> "sort".equals(parameter.getName()))
                        .forEach(parameter -> {
                            parameter.setDescription("Sort order as `property,asc` or `property,desc`; repeat the "
                                    + "parameter to sort by several properties. Allowed properties: " + properties
                                    + ". Any other property is rejected with 400.");
                            if (parameter.getSchema() != null
                                    && parameter.getSchema().getItems() != null) {
                                parameter
                                        .getSchema()
                                        .getItems()
                                        .setPattern("^(" + alternatives + ")(,(asc|desc|ASC|DESC))?$");
                            }
                        });
            }
            return operation;
        };
    }

    /**
     * Builds a reference to one of the shared error responses.
     *
     * @param name the component name, e.g. {@link #UNAUTHORIZED}
     * @return the response object
     */
    public static ApiResponse responseRef(String name) {
        return new ApiResponse().$ref(RESPONSES_REF + name);
    }

    /**
     * Builds an error response with the problem detail body.
     *
     * @param description when the error occurs
     * @return the response object
     */
    private static ApiResponse problem(String description) {
        return new ApiResponse()
                .description(description)
                .content(new Content()
                        .addMediaType(
                                ApiDocs.PROBLEM_JSON,
                                new MediaType().schema(new Schema<>().$ref(ApiDocs.PROBLEM_SCHEMA))));
    }

    /**
     * Tells whether an operation takes a body or parameters, and can therefore be rejected with 400.
     *
     * @param operation the operation
     * @return {@code true} if it has a request body or parameters
     */
    private static boolean hasInput(Operation operation) {
        return operation.getRequestBody() != null
                || (operation.getParameters() != null
                        && !operation.getParameters().isEmpty());
    }

    /**
     * Adds references to the trace id and rate limit headers to a response, plus {@code WWW-Authenticate}
     * on 401, keeping headers it already declares. References to shared responses are left alone: the
     * shared response carries the headers.
     *
     * @param status   the response status, e.g. {@code 401}, or {@code null} for a shared response
     * @param response the response to extend
     */
    private static void addCommonHeaders(String status, ApiResponse response) {
        if (response.get$ref() != null) {
            return;
        }
        List<String> names = new ArrayList<>(COMMON_HEADERS.keySet());
        if ("401".equals(status)) {
            names.add(HttpHeaders.WWW_AUTHENTICATE);
        }
        names.forEach(name -> {
            if (response.getHeaders() == null || !response.getHeaders().containsKey(name)) {
                response.addHeaderObject(name, headerRef(name));
            }
        });
    }

    /**
     * Builds a reference to one of the shared response headers.
     *
     * @param name the header name
     * @return the header object
     */
    private static Header headerRef(String name) {
        return new Header().$ref(HEADERS_REF + name);
    }

    /**
     * Builds a map that keeps the order of its entries.
     *
     * @param entries alternating keys and values
     * @return the map
     */
    private static Map<String, String> orderedMap(String... entries) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            map.put(entries[i], entries[i + 1]);
        }
        return map;
    }

    /**
     * Builds a response header description.
     *
     * @param description what the header carries
     * @param schema      the header's type
     * @return the header object
     */
    private static Header header(String description, Schema<?> schema) {
        return new Header().description(description).schema(schema);
    }

    /**
     * The RFC 9457 problem detail produced by {@code GlobalExceptionHandler} and the security error handlers.
     *
     * @return the schema
     */
    private static Schema<?> problemSchema() {
        return new ObjectSchema()
                .description("RFC 9457 problem detail")
                .addProperty(
                        "type",
                        new StringSchema()
                                .format("uri")
                                .example(ProblemType.VALIDATION_FAILED.uri().toString())
                                .description(problemTypes()))
                .addProperty("title", new StringSchema().example("Bad Request"))
                .addProperty("status", new IntegerSchema().example(400))
                .addProperty("detail", new StringSchema().example("Request validation failed."))
                .addProperty(
                        "instance", new StringSchema().format("uri-reference").example("/api/v1/auth/register"))
                .addProperty(
                        "errors",
                        new ArraySchema()
                                .items(new Schema<>().$ref("#/components/schemas/" + FIELD_VIOLATION))
                                .description("Field errors; only on validation failures"))
                .required(List.of("type", "title", "status"));
    }

    /**
     * Lists the problem types for the description of {@code Problem.type}.
     *
     * @return a Markdown list of the types and when they occur
     */
    private static String problemTypes() {
        return Arrays.stream(ProblemType.values())
                .map(type -> "- `" + type.uri() + "`: " + type.description())
                .collect(Collectors.joining(
                        "\n",
                        "Identifies the kind of error. `about:blank` for generic HTTP errors (malformed JSON, "
                                + "unsupported media type, ...), otherwise one of:\n\n",
                        ""));
    }

    /**
     * One entry of {@code errors} in a validation problem.
     *
     * @return the schema
     */
    private static Schema<?> fieldViolationSchema() {
        return new ObjectSchema()
                .addProperty("field", new StringSchema().example("email"))
                .addProperty("message", new StringSchema().example("must be a well-formed email address"))
                .required(List.of("field", "message"));
    }
}
