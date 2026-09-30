package io.github.qwzhang01.agent.rag.model;

/**
 * Structural kind of a parsed block. Chunkers keep {@link #TABLE} and
 * {@link #CODE} blocks whole instead of splitting them mid-row / mid-line.
 */
public enum BlockType {
    HEADING,
    PARAGRAPH,
    LIST,
    TABLE,
    CODE,
    QUOTE
}
