package com.hikmethankolay.template.note;

import com.hikmethankolay.template.common.ApiException;
import com.hikmethankolay.template.common.ProblemType;
import com.hikmethankolay.template.common.SortAllowList;
import com.hikmethankolay.template.common.Versioned;
import com.hikmethankolay.template.note.dto.NoteRequest;
import com.hikmethankolay.template.note.dto.NoteResponse;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Notes of the calling user. Every method takes the caller's id and only ever touches that user's notes:
 * a note of someone else is reported as missing (404), so ids of other users' notes cannot be probed.
 */
@Service
@Transactional(readOnly = true)
public class NoteService {

    /** Properties the note list can be sorted by: all of them are visible in {@link NoteResponse}. */
    public static final String SORTABLE_PROPERTIES = "id, title, createdAt, updatedAt";

    private static final SortAllowList SORTING = new SortAllowList(List.of(SORTABLE_PROPERTIES.split(", ")));

    private final NoteRepository notes;

    /**
     * Creates the service.
     *
     * @param notes note data access
     */
    public NoteService(NoteRepository notes) {
        this.notes = notes;
    }

    /**
     * Lists the caller's notes page by page.
     *
     * @param userId   the caller
     * @param pageable page, size and sort order
     * @return one page of notes
     * @throws ApiException 400 if the sort order uses a property outside {@link #SORTABLE_PROPERTIES}
     */
    public Page<NoteResponse> list(long userId, Pageable pageable) {
        return notes.findAllByUserId(userId, SORTING.check(pageable)).map(NoteResponse::from);
    }

    /**
     * Returns one of the caller's notes.
     *
     * @param userId the caller
     * @param id     note id
     * @return the note and its version, for the {@code ETag}
     * @throws ApiException 404 if the caller has no note with this id
     */
    public Versioned<NoteResponse> get(long userId, long id) {
        return versioned(findNote(userId, id));
    }

    /**
     * Creates a note owned by the caller.
     *
     * @param userId  the caller
     * @param request title and text
     * @return the created note
     */
    @Transactional
    public NoteResponse create(long userId, NoteRequest request) {
        return NoteResponse.from(notes.save(new Note(userId, request.title(), request.body())));
    }

    /**
     * Replaces the title and text of one of the caller's notes.
     *
     * @param userId  the caller
     * @param id      note id
     * @param request new title and text
     * @param ifMatch the {@code If-Match} header, or {@code null} to update whatever the current version is
     * @return the updated note and its new version, for the {@code ETag}
     * @throws ApiException 404 if the caller has no note with this id, 412 if {@code ifMatch} does not match
     */
    @Transactional
    public Versioned<NoteResponse> update(long userId, long id, NoteRequest request, String ifMatch) {
        Note note = findNote(userId, id);
        Versioned.checkIfMatch(ifMatch, note.getVersion());
        note.update(request.title(), request.body());
        // Flushed here so updatedAt, the new version and a concurrent-update conflict (409) are in this response.
        return versioned(notes.saveAndFlush(note));
    }

    /**
     * Deletes one of the caller's notes.
     *
     * @param userId the caller
     * @param id     note id
     * @throws ApiException 404 if the caller has no note with this id
     */
    @Transactional
    public void delete(long userId, long id) {
        notes.delete(findNote(userId, id));
    }

    /**
     * Lists every note of a user, for the personal data export.
     *
     * @param userId the user
     * @return the notes, oldest first
     */
    public List<NoteResponse> export(long userId) {
        return notes.findAllByUserIdOrderByIdAsc(userId).stream()
                .map(NoteResponse::from)
                .toList();
    }

    private static Versioned<NoteResponse> versioned(Note note) {
        return new Versioned<>(NoteResponse.from(note), note.getVersion());
    }

    private Note findNote(long userId, long id) {
        return notes.findByIdAndUserId(id, userId)
                .orElseThrow(() -> ApiException.notFound(ProblemType.NOTE_NOT_FOUND, "Note not found."));
    }
}
