package com.hikmethankolay.template.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

class VersionedTest {

    @Test
    void responseCarriesTheVersionAsAStrongETag() {
        var response = new Versioned<>("body", 7).toResponse();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"7\"");
        assertThat(response.getBody()).isEqualTo("body");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"7\"", "*", "\"6\", \"7\"", " \"5\",\"7\" "})
    void matchingIfMatchLetsTheChangeThrough(String ifMatch) {
        assertThatCode(() -> Versioned.checkIfMatch(ifMatch, 7)).doesNotThrowAnyException();
    }

    @Test
    void missingIfMatchLetsTheChangeThrough() {
        assertThatCode(() -> Versioned.checkIfMatch(null, 7)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"6\"", "W/\"7\"", "7", "", "\"70\"", "\"6\", W/\"7\""})
    void otherIfMatchIsAPreconditionFailure(String ifMatch) {
        assertThatThrownBy(() -> Versioned.checkIfMatch(ifMatch, 7)).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_FAILED);
            assertThat(ex.getBody().getType()).isEqualTo(ProblemType.PRECONDITION_FAILED.uri());
        });
    }
}
