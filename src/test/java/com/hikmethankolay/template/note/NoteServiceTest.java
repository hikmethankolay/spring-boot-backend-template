package com.hikmethankolay.template.note;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hikmethankolay.template.common.ApiException;
import com.hikmethankolay.template.note.dto.NoteRequest;
import com.hikmethankolay.template.webhook.WebhookEvents;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class NoteServiceTest {

    private final NoteRepository notes = mock(NoteRepository.class);

    private final NoteService service = new NoteService(notes, mock(WebhookEvents.class), new NoteProperties(2));

    @Test
    void aUserAtTheLimitCannotCreateAnotherNote() {
        when(notes.countByUserId(7L)).thenReturn(2L);

        assertThatThrownBy(() -> service.create(7L, new NoteRequest("Title", "Body")))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getBody().getType()).hasToString("urn:problem:note-limit-reached");
                });
        verify(notes, never()).save(any());
    }
}
