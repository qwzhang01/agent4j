package io.github.qwzhang01.agent.sandbox;

/**
 * Isolation tier of a sandbox implementation (KP9).
 * <p>
 * The spectrum runs from fast-but-permeable to slow-but-robust.
 * Each tier adds an isolation layer and raises the startup cost.
 * The right tier is determined by the risk / latency / escape-surface triangle,
 * not by "stronger is always better."
 * <p>
 * <pre>
 *   Tier          Startup     Escape surface              v1 status
 *   ──────────────────────────────────────────────────────────────
 *   CLASSLOADER   ~0 ms       reflection, Unsafe, JNI     ✅ ClassLoaderSandbox
 *   PROCESS       1–2 s       shared FS (if not restricted) ✅ ProcessSandbox
 *   DOCKER        2–5 s       kernel syscalls (without seccomp) ❌ out of v1 scope
 *   MICROVMM      50–200 ms   para-virtualized device side-channels ❌ out of v1 scope
 *   WASM          ~10 ms      WASM runtime bugs             ❌ out of v1 scope
 * </pre>
 *
 * <h3>Upgrade triggers</h3>
 * <ul>
 *   <li>CLASSLOADER → PROCESS: untrusted users, multi-tenant, or adversarial prompts
 *       that instruct the LLM to escape (Decision 21 boundary).</li>
 *   <li>PROCESS → DOCKER: multi-tenant + network isolation required + you can depend
 *       on a Docker daemon.</li>
 *   <li>DOCKER → MICROVMM: stronger kernel isolation needed (public cloud, shared
 *       hardware, compliance requirements).</li>
 *   <li>DOCKER / MICROVMM → WASM: polyglot code, need portable VM, no container daemon.</li>
 * </ul>
 */
public enum SandboxTier {

    /**
     * In-process ClassLoader isolation.
     * <p>
     * Fast (no JVM startup), but NOT a security boundary: reflection, {@code Unsafe},
     * and JNI can escape. Suitable only for {@link SandboxRiskLevel#TRUSTED} and
     * {@link SandboxRiskLevel#SEMI_TRUSTED} (non-adversarial LLM code).
     * <p>
     * agent4j implementation: {@link ClassLoaderSandbox} (via {@code ClassLoaderSandbox}).
     */
    CLASSLOADER,

    /**
     * OS-level process isolation.
     * <p>
     * Subprocess cannot access the parent JVM's heap or file handles directly.
     * Reliable timeout via {@code destroyForcibly()}. JVM startup cost: 1–2 s.
     * Does not restrict filesystem access within the subprocess unless the OS
     * user is also restricted.
     * <p>
     * agent4j implementation: {@link ProcessSandbox}.
     */
    PROCESS,

    /**
     * Container isolation (Docker / Podman).
     * <p>
     * Adds: network namespace, mount namespace, cgroup resource limits, optional
     * seccomp filter. Startup: 2–5 s cold, less with pre-warmed containers.
     * <p>
     * Not implemented in agent4j v1 (no Docker daemon dependency).
     */
    DOCKER,

    /**
     * Micro-VM isolation (Firecracker, gVisor, Kata Containers).
     * <p>
     * Hardware-level VM boundary per workload; startup 50–200 ms (Firecracker).
     * Stronger kernel isolation than containers; used in AWS Lambda / Fly.io.
     * <p>
     * Not implemented in agent4j v1.
     */
    MICROVMM,

    /**
     * WebAssembly virtual machine (Wasmtime, WasmEdge).
     * <p>
     * VM-level isolation without an OS process per sandbox; fast (~10 ms), portable.
     * Requires code to be compiled to WASM. Suitable for polyglot agent workloads.
     * <p>
     * Not implemented in agent4j v1.
     */
    WASM
}
