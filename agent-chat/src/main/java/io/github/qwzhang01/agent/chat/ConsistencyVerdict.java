package io.github.qwzhang01.agent.chat;

/**
 * Result of a {@link ConsistencyGuard} check.
 * <p>
 * {@code consistent} means no alert. A warning never rewrites the reply; the
 * engine keeps the original assistant line and notifies the host.
 *
 * @param consistent {@code true} when the turn looks consistent to the host
 * @param warning    optional host message; blank when consistent
 */
public record ConsistencyVerdict(boolean consistent, String warning) {

    public ConsistencyVerdict {
        warning = warning == null ? "" : warning;
        if (consistent) {
            warning = "";
        }
    }

    public static ConsistencyVerdict ok() {
        return new ConsistencyVerdict(true, "");
    }

    public static ConsistencyVerdict warn(String warning) {
        String text = warning == null || warning.isBlank() ? "inconsistent" : warning.trim();
        return new ConsistencyVerdict(false, text);
    }
}
