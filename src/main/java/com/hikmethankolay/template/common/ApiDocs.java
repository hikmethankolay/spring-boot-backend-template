package com.hikmethankolay.template.common;

/**
 * Constants for OpenAPI annotations on controllers, so every error response points to the same
 * problem detail schema (registered by {@code OpenApiConfig}).
 */
public final class ApiDocs {

    /** Media type of every error response (RFC 9457). */
    public static final String PROBLEM_JSON = "application/problem+json";

    /** Reference to the problem detail schema in the OpenAPI components. */
    public static final String PROBLEM_SCHEMA = "#/components/schemas/Problem";

    /** Description of the {@code ETag} response header of a resource read or written with {@link Versioned}. */
    public static final String ETAG = "Strong tag of this version of the resource; send it as `If-Match` to update it";

    /** Description of the optional {@code If-Match} request header of an update checked with {@link Versioned}. */
    public static final String IF_MATCH = "The `ETag` the change is based on; if the resource changed since, the "
            + "update is refused with 412. Without the header the update applies to whatever the current version is";

    /** Description of the {@code 412} response of an update checked with {@link Versioned}. */
    public static final String PRECONDITION_FAILED = "`If-Match` does not match the current `ETag`; reload and retry";

    /** Description of the {@code 304} response of a read that returns an {@code ETag}. */
    public static final String NOT_MODIFIED =
            "`If-None-Match` lists the current `ETag`: the copy the client has is still current";

    /** Not instantiable. */
    private ApiDocs() {}
}
