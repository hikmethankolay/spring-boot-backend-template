package com.hikmethankolay.template.common;

import java.util.List;
import org.springframework.http.ETag;
import org.springframework.http.ResponseEntity;

/**
 * A response body together with the JPA {@code @Version} of the entity it was read from, sent to the client as a
 * strong {@code ETag} (e.g. {@code "3"}) and checked against {@code If-Match} on updates.
 *
 * <p>The tag is strong because {@code If-Match} only ever matches strong tags (RFC 9110, section 13.1.1). The version
 * changes on every write of the row, also of fields the response does not show, so a tag may change while the body
 * stays the same; it never stays the same while the body changes.</p>
 *
 * @param body    the response body
 * @param version the entity's version when the body was read
 * @param <T>     the body type
 */
public record Versioned<T>(T body, long version) {

    /**
     * Returns the body with {@code 200 OK} and the version as {@code ETag}.
     *
     * @return the response
     */
    public ResponseEntity<T> toResponse() {
        return ResponseEntity.ok().eTag(tag(version)).body(body);
    }

    /**
     * Checks an {@code If-Match} header against the current version of an entity, before changing it.
     *
     * @param ifMatch the header, or {@code null} if the request has none: the change then goes ahead
     * @param version the entity's current version
     * @throws ApiException 412 unless the header is {@code *} or lists the current tag (weak tags never match)
     */
    public static void checkIfMatch(String ifMatch, long version) {
        if (ifMatch == null) {
            return;
        }
        ETag current = new ETag(Long.toString(version), false);
        List<ETag> tags = ETag.parse(ifMatch);
        if (tags.stream().noneMatch(tag -> tag.isWildcard() || tag.compare(current, true))) {
            throw ApiException.preconditionFailed(
                    ProblemType.PRECONDITION_FAILED,
                    "The resource was changed since it was read (its ETag is now " + tag(version) + "); reload it "
                            + "and retry.");
        }
    }

    private static String tag(long version) {
        return "\"" + version + "\"";
    }
}
