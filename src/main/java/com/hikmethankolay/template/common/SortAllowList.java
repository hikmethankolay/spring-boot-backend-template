package com.hikmethankolay.template.common;

import java.util.List;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Restricts the {@code sort} parameter of a paginated request to an explicit list of properties.
 *
 * <p>Spring Data accepts any property of the entity, including ones the API never returns. Sorting by a
 * hidden column such as a password hash reveals information about values the caller cannot see, so every
 * list endpoint should only allow sorting by fields that are already visible in its response.</p>
 */
public final class SortAllowList {

    private final Set<String> allowed;

    private final String description;

    /**
     * Creates an allow-list.
     *
     * @param properties entity property names that may be sorted by
     */
    public SortAllowList(List<String> properties) {
        this.allowed = Set.copyOf(properties);
        this.description = String.join(", ", properties);
    }

    /**
     * Checks every sort property of a request.
     *
     * @param pageable the requested page
     * @return the page request, ending with {@code id} so rows with equal sort values keep a stable order across
     *         pages
     * @throws ApiException 400 naming the rejected property and the allowed ones, or if the page starts past
     *                      {@link Integer#MAX_VALUE} rows, which the JPA offset cannot hold
     */
    public Pageable check(Pageable pageable) {
        if (pageable.isPaged() && pageable.getOffset() > Integer.MAX_VALUE) {
            throw ApiException.badRequest(
                    ProblemType.PAGE_TOO_LARGE, "The page starts too far into the list; request a smaller page.");
        }
        Sort sort = pageable.getSort();
        for (Sort.Order order : sort) {
            if (!allowed.contains(order.getProperty())) {
                throw ApiException.badRequest(
                        ProblemType.INVALID_SORT,
                        "Cannot sort by '" + order.getProperty() + "'. Allowed properties: " + description + ".");
            }
        }
        // Without a unique last key, LIMIT/OFFSET may repeat or skip rows that tie on the requested sort.
        if (pageable.isUnpaged() || sort.getOrderFor("id") != null) {
            return pageable;
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort.and(Sort.by("id")));
    }
}
