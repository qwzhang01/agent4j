package io.github.qwzhang01.agent.rag.model;

import java.util.List;
import java.util.Objects;

/**
 * One structural block of a parsed document, in reading order.
 *
 * @param type         structural kind
 * @param text         block text; tables are rendered as Markdown pipe rows, code keeps its fences stripped
 * @param headingLevel 1-6 for {@link BlockType#HEADING}, 0 otherwise
 * @param sectionPath  heading titles enclosing this block, outermost first (a heading's own path includes itself)
 * @param startLine    1-based first source line; 0 when the format has no line concept
 * @param endLine      1-based last source line (inclusive); 0 when unknown
 * @param page         1-based page for paginated formats (PDF), null otherwise
 */
public record DocumentBlock(
        BlockType type,
        String text,
        int headingLevel,
        List<String> sectionPath,
        int startLine,
        int endLine,
        Integer page
) {
    public DocumentBlock {
        Objects.requireNonNull(type, "type");
        text = text == null ? "" : text;
        sectionPath = sectionPath == null ? List.of() : List.copyOf(sectionPath);
    }
}
