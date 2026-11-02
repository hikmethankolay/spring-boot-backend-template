package com.hikmethankolay.template.note;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * A note owned by one user, mapped to the {@code notes} table created by {@code V8__notes.sql}.
 *
 * <p>The owner is stored as a plain id rather than a {@code @ManyToOne User}: the feature never needs the
 * user entity, and the database enforces the link with a foreign key.</p>
 */
@Entity
@Table(name = "notes")
public class Note {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 10000)
    private String body;

    // Two concurrent updates: the second fails with 409 instead of silently overwriting the first.
    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Constructor required by JPA. */
    protected Note() {}

    /**
     * Creates a new, not yet persisted note.
     *
     * @param userId id of the owner
     * @param title  title
     * @param body   text
     */
    public Note(long userId, String title, String body) {
        this.userId = userId;
        this.title = title;
        this.body = body;
    }

    /** Sets both timestamps before the first insert. */
    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    /** Refreshes {@code updatedAt} before every update. */
    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /**
     * Returns the id.
     *
     * @return the database id, or {@code null} before the note is persisted
     */
    public Long getId() {
        return id;
    }

    /**
     * Returns the owner's id.
     *
     * @return the id of the user who owns the note
     */
    public Long getUserId() {
        return userId;
    }

    /**
     * Returns the title.
     *
     * @return the title
     */
    public String getTitle() {
        return title;
    }

    /**
     * Returns the text.
     *
     * @return the text
     */
    public String getBody() {
        return body;
    }

    /**
     * Replaces the title and the text.
     *
     * @param title the new title
     * @param body  the new text
     */
    public void update(String title, String body) {
        this.title = title;
        this.body = body;
    }

    /**
     * Returns the creation time.
     *
     * @return when the note was created
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Returns the time of the last update.
     *
     * @return when the note was last changed
     */
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Returns the optimistic-locking version, sent to clients as the note's {@code ETag}.
     *
     * @return the version, incremented on every update
     */
    public long getVersion() {
        return version;
    }
}
