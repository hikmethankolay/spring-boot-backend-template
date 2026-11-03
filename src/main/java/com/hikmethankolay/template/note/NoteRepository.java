package com.hikmethankolay.template.note;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Data access for {@link Note}. Every query is scoped to an owner, so one user never sees another's notes. */
public interface NoteRepository extends JpaRepository<Note, Long> {

    /**
     * Finds a note of one user.
     *
     * @param id     note id
     * @param userId owner id
     * @return the note, or empty if it does not exist or belongs to someone else
     */
    Optional<Note> findByIdAndUserId(long id, long userId);

    /**
     * Lists the notes of one user.
     *
     * @param userId   owner id
     * @param pageable page, size and sort order
     * @return one page of notes
     */
    Page<Note> findAllByUserId(long userId, Pageable pageable);

    /**
     * Counts the notes of one user.
     *
     * @param userId owner id
     * @return how many notes the user has
     */
    long countByUserId(long userId);

    /**
     * Holds a lock on a user's notes until the transaction ends, so concurrent creates count one after another.
     *
     * @param userId owner id
     * @return always 1
     */
    @Query(value = "select 1 from pg_advisory_xact_lock(hashtextextended('notes:' || :userId, 0))", nativeQuery = true)
    int lockUser(long userId);

    /**
     * Lists every note of one user, for the personal data export.
     *
     * @param userId owner id
     * @return the notes, oldest first
     */
    List<Note> findAllByUserIdOrderByIdAsc(long userId);
}
