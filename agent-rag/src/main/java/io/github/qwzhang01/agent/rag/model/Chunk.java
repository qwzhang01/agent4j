package io.github.qwzhang01.agent.rag.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The unit of indexing, retrieval and citation.
 *
 * @param chunkId     {@code docId + "#" + ordinal}; stable while the document content is unchanged
 * @param docId       owning document
 * @param docTitle    owning document title
 * @param source      owning document source (path/URI)
 * @param sectionPath heading titles enclosing the chunk, outermost first
 * @param text        chunk body as shown to the LLM and to users
 * @param startLine   1-based first source line; 0 when unknown
 * @param endLine     1-based last source line (inclusive); 0 when unknown
 * @param page        1-based page for paginated formats, null otherwise
 * @param metadata    inherited document metadata plus chunk-level attributes (e.g. {@code blockTypes})
 */
public record Chunk(
        String chunkId,
        String docId,
        String docTitle,
        String source,
        List<String> sectionPath,
        String text,
        int startLine,
        int endLine,
        Integer page,
        Map<String, String> metadata
) {
    public Chunk {
        Objects.requireNonNull(chunkId, "chunkId");
        Objects.requireNonNull(docId, "docId");
        text = text == null ? "" : text;
        docTitle = docTitle == null ? docId : docTitle;
        sectionPath = sectionPath == null ? List.of() : List.copyOf(sectionPath);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** Section path joined with {@code " > "}; empty when the chunk sits before any heading. */
    public String sectionLabel() {
        return String.join(" > ", sectionPath);
    }

    /**
     * Text used for embedding and BM25: document title and section path prepended to the body,
     * so a chunk that says "set it to 30" still matches a query naming the feature in its heading.
     */
    public String contextualText() {
        StringBuilder sb = new StringBuilder(docTitle);
        if (!sectionPath.isEmpty()) {
            sb.append(" | ").append(sectionLabel());
        }
        return sb.append('\n').append(text).toString();
    }
}
