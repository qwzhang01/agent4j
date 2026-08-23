package io.github.qwzhang01.agent.trace.feedback;

import java.time.Instant;

/**
 * A human quality rating for ONE trajectory (Stage 14 D5/D6).
 * <p>
 * Ratings live in an annotations SIDECAR file, never inside the trajectory
 * itself: trajectory files are append-only training assets, and rewriting
 * them per annotation would be both a race and an integrity hazard (same
 * discipline as Stage 13 PromptManager's publish-only history).
 *
 * @param trajectoryId which trajectory this rates
 * @param rating       1-5, 5 = excellent
 * @param notes        free-text justification (null normalized to "")
 * @param annotator    who rated (e.g. "console", an employee id)
 * @param createdAt    when
 */
public record HumanFeedback(String trajectoryId, int rating, String notes, String annotator,
                            Instant createdAt) {

    public HumanFeedback {
        if (trajectoryId == null || trajectoryId.isBlank()) {
            throw new IllegalArgumentException("trajectoryId must not be blank");
        }
        if (rating < 1 || rating > 5) {
            throw new IllegalArgumentException("rating must be 1-5, got " + rating);
        }
        if (annotator == null || annotator.isBlank()) {
            throw new IllegalArgumentException("annotator must not be blank");
        }
        notes = notes == null ? "" : notes;
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
