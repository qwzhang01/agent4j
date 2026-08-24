package io.github.qwzhang01.agent.tavern.world;

/**
 * A world-state change as an explicit instruction (Stage 16, blueprint D3).
 * <p>
 * The world never mutates through free-form setters; every change is one of
 * these enumerable, auditable, replayable values. The replay engine (M16.4)
 * rebuilds world history by re-applying these instructions; the turn log
 * records them verbatim; the audit trail sees them as the effect of a tool
 * call.
 * <p>
 * Same philosophy as the WorkflowState blackboard (Stage 5), in domain form:
 * shared mutable state is fine, but changes must be first-class values -
 * a {@code map.put} scattered across the codebase is neither auditable
 * nor replayable.
 */
public sealed interface WorldEffect {

    /** Set a named flag to a value (overwrites any previous value). */
    record SetFlag(String key, String value) implements WorldEffect {
        public SetFlag {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("flag key must not be null or blank");
            }
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("flag value must not be null or blank");
            }
        }
    }

    /** Remove a named flag. */
    record ClearFlag(String key) implements WorldEffect {
        public ClearFlag {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("flag key must not be null or blank");
            }
        }
    }

    /** Move the scene to a different location. */
    record SetLocation(String location) implements WorldEffect {
        public SetLocation {
            if (location == null || location.isBlank()) {
                throw new IllegalArgumentException("location must not be null or blank");
            }
        }
    }
}
