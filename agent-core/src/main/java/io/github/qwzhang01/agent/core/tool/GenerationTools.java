package io.github.qwzhang01.agent.core.tool;

/**
 * Canonical names for multimodal generation / vision tools.
 * <p>
 * Shared so Stage 9 {@code ToolPolicy} and Stage 7 scheduler wiring
 * do not hard-code strings in three modules.
 */
public final class GenerationTools {

    public static final String DESCRIBE_IMAGE = "describe_image";
    public static final String GENERATE_IMAGE = "generate_image";
    public static final String GENERATE_VIDEO = "generate_video";

    private GenerationTools() {
    }
}
