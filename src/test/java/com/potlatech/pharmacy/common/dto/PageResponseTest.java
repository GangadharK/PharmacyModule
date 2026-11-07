package com.potlatech.pharmacy.common.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import com.potlatech.pharmacy.common.dto.PageResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PageResponse - the pagination envelope")
class PageResponseTest {

    @Test
    @DisplayName("wraps the first page with first=true, last=false")
    void firstPage() {
        Page<String> page = new PageImpl<>(
                List.of("a", "b", "c"), PageRequest.of(0, 3), 10);

        PageResponse<String> response = PageResponse.from(page);

        assertThat(response.content()).containsExactly("a", "b", "c");
        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(3);
        assertThat(response.totalElements()).isEqualTo(10);
        assertThat(response.totalPages()).isEqualTo(4);
        assertThat(response.first()).isTrue();
        assertThat(response.last()).isFalse();
    }

    @Test
    @DisplayName("wraps a middle page with first=false, last=false")
    void middlePage() {
        Page<String> page = new PageImpl<>(
                List.of("d", "e", "f"), PageRequest.of(1, 3), 10);

        PageResponse<String> response = PageResponse.from(page);

        assertThat(response.first()).isFalse();
        assertThat(response.last()).isFalse();
    }

    @Test
    @DisplayName("wraps the last page with first=false, last=true")
    void lastPage() {
        Page<String> page = new PageImpl<>(
                List.of("j"), PageRequest.of(3, 3), 10);

        PageResponse<String> response = PageResponse.from(page);

        assertThat(response.first()).isFalse();
        assertThat(response.last()).isTrue();
        assertThat(response.content()).containsExactly("j");
    }

    @Test
    @DisplayName("a single page is both first and last")
    void singlePage() {
        Page<String> page = new PageImpl<>(
                List.of("a", "b"), PageRequest.of(0, 10), 2);

        PageResponse<String> response = PageResponse.from(page);

        assertThat(response.first()).isTrue();
        assertThat(response.last()).isTrue();
        assertThat(response.totalPages()).isEqualTo(1);
    }

    @Test
    @DisplayName("an empty result set still has a well-formed envelope")
    void emptyResult() {
        Page<String> page = new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);

        PageResponse<String> response = PageResponse.from(page);

        assertThat(response.content()).isEmpty();
        assertThat(response.totalElements()).isZero();
        assertThat(response.totalPages()).isZero();
        assertThat(response.first()).isTrue();
        assertThat(response.last()).isTrue();
    }

    @Test
    @DisplayName("a page past the end is neither first nor last")
    void pageBeyondEnd() {
        Page<String> page = new PageImpl<>(
                List.of(), PageRequest.of(9, 3), 10);

        PageResponse<String> response = PageResponse.from(page);

        assertThat(response.content()).isEmpty();
        assertThat(response.first()).isFalse();
    }

    @Test
    @DisplayName("of() computes totalPages by ceiling division")
    void computesTotalPages() {
        assertThat(PageResponse.of(List.of("a"), 0, 3, 10).totalPages()).isEqualTo(4);
        assertThat(PageResponse.of(List.of("a"), 0, 3, 9).totalPages()).isEqualTo(3);
        assertThat(PageResponse.of(List.of("a"), 0, 3, 1).totalPages()).isEqualTo(1);
    }

    @Test
    @DisplayName("of() with a size of zero does not divide by zero")
    void handlesZeroSize() {
        PageResponse<String> response = PageResponse.of(List.of(), 0, 0, 10);

        assertThat(response.totalPages()).isZero();
    }

    @Test
    @DisplayName("empty() is a well-formed page 0")
    void emptyFactory() {
        PageResponse<String> response = PageResponse.empty(0, 20);

        assertThat(response.content()).isEmpty();
        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.first()).isTrue();
        assertThat(response.last()).isTrue();
    }
}
