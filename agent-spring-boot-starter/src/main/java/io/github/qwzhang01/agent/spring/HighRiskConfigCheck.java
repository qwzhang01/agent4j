package io.github.qwzhang01.agent.spring;

import java.util.ArrayList;
import java.util.List;

/**
 * Boot-time high-risk configuration check (Stage 8.2): "启动时检查高风险配置"
 * — bare tools, no sandbox, no persistent store, no secret masking.
 * <p>
 * Runs once at startup. Findings are logged as one structured block and —
 * in the SECURE profile — any HIGH finding fails the boot. TEST logs the
 * same findings but never blocks. UNSAFE skips entirely: the operator
 * explicitly opted out, restating the choice adds nothing.
 * <p>
 * Judgment discipline (what CAN be judged at boot, honestly):
 * <ul>
 *   <li><b>HIGH</b> — objective contradictions only. Today exactly one:
 *       SECURE profile with side-effect auto-approval on (test-grade
 *       permission under a production-grade profile name).</li>
 *   <li><b>MEDIUM</b> — configured-but-absent persistence/sandbox beans
 *       and a blank API key. These are deployment-shape notes: they have
 *       legitimate single-JVM / env-var answers, so they must not block.</li>
 *   <li><b>LOW</b> — informational (masked-by-default confirmation).</li>
 * </ul>
 * What this check deliberately does NOT claim: it cannot enumerate the
 * agents a business creates after boot (tools are registered per-agent),
 * so "bare tool" judgments beyond the profile-level contradiction stay
 * with the runtime governance stack, which refuses them per call.
 */
public class HighRiskConfigCheck {

    /**
     * Risk levels ordered lowest to highest. SECURE blocks on any finding
     * at HIGH; TEST warns at every level and blocks on nothing.
     */
    public enum Level { LOW, MEDIUM, HIGH }

    /** One finding: component, what was found, what to do about it. */
    public record Finding(Level level, String component, String detail, String remediation) {
        @Override
        public String toString() {
            return "[" + level + "][" + component + "] " + detail + " — " + remediation;
        }
    }

    private final AgentProfile profile;
    private final AgentProperties properties;

    public HighRiskConfigCheck(AgentProfile profile, AgentProperties properties) {
        this.profile = profile;
        this.properties = properties;
    }

    /**
     * Inspect and return all findings. A pure function of the properties
     * and the optional runtime beans — safe to unit-test without Spring.
     *
     * @param persistentStorePresent whether a RunStore (or equivalent
     *        durable spine) bean exists in the context
     * @param sandboxPresent whether a Sandbox bean exists in the context
     */
    public List<Finding> inspect(boolean persistentStorePresent, boolean sandboxPresent) {
        List<Finding> findings = new ArrayList<>();
        if (profile == AgentProfile.UNSAFE) {
            return findings; // explicit opt-out, nothing to restate
        }

        // 1. The objective contradiction: production profile + test-grade
        //    auto-approval of side-effect tools.
        if (profile == AgentProfile.SECURE && properties.getApproval().isAutoApprove()) {
            findings.add(new Finding(Level.HIGH, "approval",
                    "SECURE profile with side-effect auto-approval on — test-grade "
                            + "permission under a production profile",
                    "set agent4j.approval.auto-approve=false or use profile=test"));
        }

        // 2. Bare tool exposure shape: no sandbox to isolate code-exec tools.
        if (!sandboxPresent) {
            findings.add(new Finding(Level.MEDIUM, "sandbox",
                    "No Sandbox bean present — code-exec tools would run in-process",
                    "add an agent-sandbox Sandbox bean, or keep registries free of "
                            + "code-exec tools (the governed executor still gates "
                            + "side-effect tools on approval)"));
        }

        // 3. No persistent store: single-JVM memory semantics.
        if (!persistentStorePresent) {
            findings.add(new Finding(Level.MEDIUM, "store",
                    "No RunStore bean — runs live in JVM memory only "
                            + "(a crash loses in-flight state)",
                    "add a RunStore (JdbcRunStore on a real database) for "
                            + "crash-recoverable runs"));
        }

        // 4. Secret masking: the boundary default in this starter.
        findings.add(new Finding(Level.LOW, "masking",
                "Secret masking defaults to on at the model boundary "
                        + "(SecretMasker.withDefaults)",
                "no action needed; override the masker bean to customize rules"));

        // 5. Blank API key against a real provider (often an env var not
        //    wired — legitimate, so MEDIUM note not HIGH).
        if ("openai".equalsIgnoreCase(properties.getModel().getProvider())
                && (properties.getModel().getApiKey() == null
                        || properties.getModel().getApiKey().isBlank())) {
            findings.add(new Finding(Level.MEDIUM, "model",
                    "OpenAI-compatible provider configured with a blank API key",
                    "check the api-key property / OPENAI_API_KEY env binding"));
        }
        return findings;
    }

    /** SECURE blocks on HIGH findings; TEST and UNSAFE never block. */
    public boolean shouldBlockBoot(List<Finding> findings) {
        return profile == AgentProfile.SECURE
                && findings.stream().anyMatch(f -> f.level() == Level.HIGH);
    }

    /** Render findings as one structured log block. */
    public String renderLog(List<Finding> findings) {
        StringBuilder sb = new StringBuilder("[HighRiskConfigCheck] profile=")
                .append(profile).append(" findings=").append(findings.size());
        for (Finding f : findings) {
            sb.append("\n  ").append(f);
        }
        return sb.toString();
    }
}
