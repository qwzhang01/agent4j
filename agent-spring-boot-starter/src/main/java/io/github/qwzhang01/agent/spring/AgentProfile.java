package io.github.qwzhang01.agent.spring;

/**
 * Runtime profiles (, harness roadmap): the Starter's three
 * clearly-named operating modes.
 * <p>
 * The ladder exists so "ungoverned" can never be silent:
 * <ul>
 *   <li>{@link #SECURE} — full governance stack on by default:
 *       governed tool executor, contract-derived permissions,
 *       auto-approval <em>off</em> (side-effect tools denied without an
 *       explicit approval service), secret masking at the boundary,
 *       high-risk config check runs at boot and fails the boot.</li>
 *   <li>{@link #TEST} — same assembly as SECURE but non-interactive
 *       defaults: auto-approve side-effect tools, high-risk check runs
 *       but only WARNS (never blocks a test boot).</li>
 *   <li>{@link #UNSAFE} — the explicit raw path. No governance, no
 *       masking, no boot check. Opt-in by name, logged loudly at
 *       startup — the 0.1.3 default behavior, now something you must
 *       ask .</li>
 * </ul>
 * Map: {@code agent4j.profile: secure | test | unsafe} (default
 * {@code secure}).
 */
public enum AgentProfile {

    /** Full governance, fail-closed. The production default. */
    SECURE,

    /** Same stack, non-interactive defaults, boot check warns instead of blocking. */
    TEST,

    /** Explicit raw path — no governance. You asked for it, by name. */
    UNSAFE
}
