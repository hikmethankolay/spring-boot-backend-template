package com.hikmethankolay.template.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

class SortAllowListTest {

    private final SortAllowList sorting = new SortAllowList(List.of("id", "title"));

    @Test
    void appendsIdSoTiesKeepAStableOrder() {
        Pageable checked = sorting.check(PageRequest.of(2, 10, Sort.by(Sort.Direction.DESC, "title")));

        assertThat(checked.getSort()).containsExactly(Sort.Order.desc("title"), Sort.Order.asc("id"));
        assertThat(checked.getPageNumber()).isEqualTo(2);
        assertThat(checked.getPageSize()).isEqualTo(10);
    }

    @Test
    void keepsARequestAlreadySortedById() {
        Pageable request = PageRequest.of(0, 5, Sort.by(Sort.Direction.DESC, "id"));

        assertThat(sorting.check(request)).isSameAs(request);
    }

    @Test
    void sortsAnUnsortedPageById() {
        assertThat(sorting.check(PageRequest.of(0, 5)).getSort()).containsExactly(Sort.Order.asc("id"));
    }

    @Test
    void refusesAPageStartingPastTheLargestJpaOffset() {
        // The JPA offset is an int: a larger one would fail in the query as a 500.
        assertThatThrownBy(() -> sorting.check(PageRequest.of(21_474_837, 100)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        ex -> assertThat(ex.getBody().getType()).hasToString("urn:problem:page-too-large"));
        assertThat(sorting.check(PageRequest.of(21_474_836, 100)).getPageNumber())
                .isEqualTo(21_474_836);
    }

    @Test
    void leavesUnpagedRequestsAlone() {
        assertThat(sorting.check(Pageable.unpaged())).isEqualTo(Pageable.unpaged());
    }

    @Test
    void rejectsAPropertyNotAllowed() {
        assertThatThrownBy(() -> sorting.check(PageRequest.of(0, 5, Sort.by("passwordHash"))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("passwordHash");
    }
}
