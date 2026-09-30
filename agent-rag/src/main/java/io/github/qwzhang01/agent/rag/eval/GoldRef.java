package io.github.qwzhang01.agent.rag.eval;

import io.github.qwzhang01.agent.rag.model.Chunk;

import java.util.Objects;

/**
 * A gold-standard location that should be retrieved for a question.
 *
 * @param docId   document id, compared exactly with {@link Chunk#docId()}
 * @param section substring of {@link Chunk#sectionLabel()}; null or blank matches any section
 */
public record GoldRef(String docId, String section) {

    public GoldRef {
        Objects.requireNonNull(docId, "docId");
    }

    /** Whether {@code chunk} lies at this location. */
    public boolean matches(Chunk chunk) {
        if (!docId.equals(chunk.docId())) {
            return false;
        }
        return section == null || section.isBlank() || chunk.sectionLabel().contains(section);
    }

    @Override
    public String toString() {
        return section == null || section.isBlank() ? docId : docId + " § " + section;
    }
}
