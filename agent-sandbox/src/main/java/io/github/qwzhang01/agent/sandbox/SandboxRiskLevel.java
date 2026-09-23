package io.github.qwzhang01.agent.sandbox;

/**
 * Risk classification for code that a sandbox will execute (KP9).
 * <p>
 * The risk level is the caller's assertion about the provenance and intent of the
 * code. It drives {@link SandboxPolicy#tierFor(SandboxRiskLevel)} and the default
 * escalation strategy in {@link SandboxEscalator}.
 * <p>
 * Risk spectrum (ascending, with sandbox tier recommendation):
 * <ol>
 *   <li>{@link #TRUSTED} — operator-authored, same trust domain as the host JVM.</li>
 *   <li>{@link #SEMI_TRUSTED} — LLM-generated, non-adversarial. This is the
 *       Decision 21 operating assumption for agent4j: the model is not trying to
 *       escape, it just needs a safe place to run the code it invented.</li>
 *   <li>{@link #UNTRUSTED} — end-user submitted code; could be malicious by accident.</li>
 *   <li>{@link #ADVERSARIAL} — actively hostile code, e.g. from a multi-tenant
 *       environment where users are expected to probe sandbox boundaries.</li>
 * </ol>
 *
 * <h2>When does Decision 21 break?</h2>
 * Decision 21 "ClassLoader is enough for single-tenant half-trusted tool code" holds
 * under two conditions:
 * <ul>
 *   <li>Single tenant — one user's code cannot affect another user's state.</li>
 *   <li>Non-adversarial — the LLM is not instructed to bypass the sandbox.</li>
 * </ul>
 * Both conditions break at once in a multi-tenant coding agent where users submit
 * arbitrary prompts. At that point {@link #UNTRUSTED} or {@link #ADVERSARIAL} is the
 * correct level, and {@link SandboxPolicy} maps those to {@link SandboxTier#PROCESS}.
 * Docker / microVM / WASM are the right answer beyond that; they are out of v1 scope
 * (no Docker daemon dependency, no WASM runtime requirement).
 */
public enum SandboxRiskLevel {

    /**
     * Operator-authored code in the same trust domain as the host JVM.
     * <p>
     * Example: a built-in tool the framework ships and the operator has not modified.
     * No sandboxing is strictly required; ClassLoader isolation is used defensively
     * for output capture and timeout enforcement only.
     */
    TRUSTED,

    /**
     * LLM-generated code that is assumed non-adversarial (Decision 21).
     * <p>
     * The model writes code to solve a user request; it is not trying to escape the
     * sandbox. ClassLoader isolation is sufficient here: it blocks the common dangerous
     * classes (Runtime, File, ProcessBuilder, reflection) at load time, which is
     * "defence in depth" against bugs in the model's code rather than a security
     * boundary against intentional attacks.
     * <p>
     * In multi-tenant deployments where the LLM is prompted by untrusted users,
     * this level should be upgraded to {@link #UNTRUSTED}.
     */
    SEMI_TRUSTED,

    /**
     * Code submitted by an untrusted user; may be unintentionally or intentionally
     * malicious.
     * <p>
     * Example: a user pastes arbitrary Java in a playground page.
     * Process isolation ({@link SandboxTier#PROCESS}) is required: the subprocess
     * boundary prevents the code from accessing the host JVM's memory or file handles.
     * Container isolation (Docker / microVM) is preferred for stronger guarantees but
     * is outside v1 scope.
     */
    UNTRUSTED,

    /**
     * Code from actively hostile parties who know the sandbox exists and are probing
     * its boundaries — red-team scenarios, public multi-tenant services.
     * <p>
     * {@link SandboxTier#PROCESS} is the minimum viable tier in v1.
     * Production deployments at this level should use Docker + resource cgroups or
     * Firecracker microVMs. WASM is the right answer when portability constraints
     * prevent container dependencies.
     */
    ADVERSARIAL
}
