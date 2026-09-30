package io.github.qwzhang01.agent.rag.ingest;

/**
 * Size limits for {@link StructureAwareChunker}, in characters (UTF-16 code units).
 *
 * @param targetChars  blocks of one section are packed into a chunk until the next would exceed this
 * @param maxChars     a single block longer than this is split (paragraph/list by sentence,
 *                     table by rows, code by lines)
 * @param overlapChars tail of the previous piece prepended to the next piece of a split
 *                     paragraph/list block; 0 disables overlap
 */
public record ChunkerOptions(int targetChars, int maxChars, int overlapChars) {

    public static final int DEFAULT_TARGET_CHARS = 800;
    public static final int DEFAULT_MAX_CHARS = 1500;
    public static final int DEFAULT_OVERLAP_CHARS = 0;

    public ChunkerOptions {
        if (targetChars <= 0) {
            throw new IllegalArgumentException("targetChars must be > 0: " + targetChars);
        }
        if (maxChars < targetChars) {
            throw new IllegalArgumentException("maxChars must be >= targetChars: " + maxChars);
        }
        if (overlapChars < 0 || overlapChars >= targetChars) {
            throw new IllegalArgumentException("overlapChars must be in [0, targetChars): " + overlapChars);
        }
    }

    /** 800 / 1500 / 0. */
    public static ChunkerOptions defaults() {
        return new ChunkerOptions(DEFAULT_TARGET_CHARS, DEFAULT_MAX_CHARS, DEFAULT_OVERLAP_CHARS);
    }
}
