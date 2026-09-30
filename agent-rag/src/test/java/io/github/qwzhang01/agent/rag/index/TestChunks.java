package io.github.qwzhang01.agent.rag.index;

import io.github.qwzhang01.agent.rag.model.Chunk;

import java.util.List;
import java.util.Map;

/** Chunk factories shared by index and retrieve tests. */
public final class TestChunks {

    private TestChunks() {
    }

    public static Chunk chunk(String docId, int ordinal, String text) {
        return chunk(docId, ordinal, text, Map.of());
    }

    public static Chunk chunk(String docId, int ordinal, String text, Map<String, String> metadata) {
        return new Chunk(docId + "#" + ordinal, docId, "Doc " + docId, "/corpus/" + docId,
                List.of(), text, ordinal + 1, ordinal + 1, null, metadata);
    }
}
