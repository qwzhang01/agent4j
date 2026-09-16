package io.github.qwzhang01.agent.sandbox;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Configuration for sandbox execution.
 * <p>
 * Controls:
 * - timeout: how long the code can run
 * - workingDirectory: where the code runs (process sandbox)
 * - environment: env vars injected into the process (overlay, see envAllowlist)
 * - envAllowlist: host env keys the guest may inherit (default minimal set;
 *   the full host environment is NEVER inherited by default - Stage 4.1)
 * - networkBlocked: guest network access denied (default true - Stage 4.2)
 * - fileAccess: guest filesystem access (default WORKSPACE_ONLY - Stage 4.2)
 * - outputLimitBytes: per-stream capture cap with truncation marker (Stage 4.1)
 * - memoryLimitBytes: max memory (process sandbox only)
 * - blockedPackages: Java packages blocked by ClassLoader sandbox
 * - blockedClasses: specific fully-qualified class names blocked
 * <p>
 * Use Builder pattern for construction.
 */
public class SandboxSpec {

    /**
     * Guest filesystem access policy (Stage 4.2).
     * <ul>
     *   <li>{@code WORKSPACE_ONLY} - reads/writes confined to the sandbox
     *       working directory (in-guest SecurityManager enforcement at the
     *       PROCESS tier; structural at the DOCKER tier via read-only rootfs).</li>
     *   <li>{@code UNRESTRICTED} - no filesystem confinement. The PROCESS tier
     *       runs as the host OS user: this means effectively host-wide access.
     *       Only for TRUSTED debugging.</li>
     * </ul>
     */
    public enum FileAccessPolicy {
        WORKSPACE_ONLY,
        UNRESTRICTED
    }

    /**
     * Default host env keys the guest process may inherit. Everything else in
     * the host environment is dropped before the guest starts (Stage 4.1:
     * "environment uses an allowlist, never the full host environment").
     * {@code HOME} is deliberately absent: it leaks the host username/path and
     * guest code has no legitimate need for it.
     */
    public static final List<String> DEFAULT_ENV_ALLOWLIST =
            List.of("PATH", "TMPDIR", "LANG", "TZ", "LC_ALL", "LC_CTYPE");

    /**
     * Sentinel allowlist meaning "inherit the entire host environment" - the
     * pre-Stage-4 behavior, kept only for callers that explicitly opt back in.
     */
    public static final List<String> ENV_INHERIT_ALL = List.of("*");

    private final Duration timeout;
    private final String workingDirectory;
    private final Map<String, String> environment;
    private final List<String> envAllowlist;
    private final boolean networkBlocked;
    private final FileAccessPolicy fileAccess;
    private final long outputLimitBytes;
    private final long memoryLimitBytes;
    private final List<String> blockedPackages;
    private final List<String> blockedClasses;
    private final String runId;

    private SandboxSpec(Builder builder) {
        this.timeout = builder.timeout;
        this.workingDirectory = builder.workingDirectory;
        this.environment = builder.environment;
        this.envAllowlist = builder.envAllowlist;
        this.networkBlocked = builder.networkBlocked;
        this.fileAccess = builder.fileAccess;
        this.outputLimitBytes = builder.outputLimitBytes;
        this.memoryLimitBytes = builder.memoryLimitBytes;
        this.blockedPackages = builder.blockedPackages;
        this.blockedClasses = builder.blockedClasses;
        this.runId = builder.runId;
    }

    /**
     * Default blocked packages for Java sandbox.
     * These packages contain classes that can access the OS.
     */
    public static List<String> defaultBlockedPackages() {
        return List.of(
                "java.io.File",           // 文件系统访问
                "java.io.FileInputStream",
                "java.io.FileOutputStream",
                "java.nio.file",           // NIO 文件系统
                "java.lang.Runtime",       // 执行系统命令
                "java.lang.ProcessBuilder",
                "java.lang.ProcessHandle",
                "java.lang.reflect",       // 反射逃逸
                "java.net",                // 网络访问
                "java.lang.ClassLoader",   // 自定义 ClassLoader
                "java.lang.Thread",        // 线程控制
                "jdk.tools"                // 编译器等工具
        );
    }

    public static Builder builder() {
        return new Builder();
    }

    public Duration getTimeout() {
        return timeout;
    }

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    public Map<String, String> getEnvironment() {
        return environment;
    }

    /**
     * Host env keys the guest may inherit. {@link #ENV_INHERIT_ALL} ("*")
     * restores the pre-Stage-4 full-inheritance behavior.
     */
    public List<String> getEnvAllowlist() {
        return envAllowlist;
    }

    /** Whether guest network access is denied (default true). */
    public boolean isNetworkBlocked() {
        return networkBlocked;
    }

    /** Guest filesystem access policy (default WORKSPACE_ONLY). */
    public FileAccessPolicy getFileAccess() {
        return fileAccess;
    }

    /**
     * Per-stream (stdout/stderr) capture cap in bytes. Excess output is
     * truncated and marked; {@code <= 0} means unlimited.
     */
    public long getOutputLimitBytes() {
        return outputLimitBytes;
    }

    public long getMemoryLimitBytes() {
        return memoryLimitBytes;
    }

    public List<String> getBlockedPackages() {
        return blockedPackages;
    }

    public List<String> getBlockedClasses() {
        return blockedClasses;
    }

    /**
     * Attribution key for run-scoped accounting (the escalation budget's
     * ledger unit, debt-2 fix 2026-09-12). Null means "no attribution" -
     * callers without a run identity fall back to instance-level budget.
     */
    public String getRunId() {
        return runId;
    }

    public static class Builder {
        private Duration timeout = Duration.ofSeconds(10);
        private String workingDirectory;
        private Map<String, String> environment = Map.of();
        private List<String> envAllowlist = DEFAULT_ENV_ALLOWLIST;
        private boolean networkBlocked = true;
        private FileAccessPolicy fileAccess = FileAccessPolicy.WORKSPACE_ONLY;
        private long outputLimitBytes = 1024 * 1024; // 1 MB per stream
        private long memoryLimitBytes = 256 * 1024 * 1024; // 256MB default
        private List<String> blockedPackages = defaultBlockedPackages();
        private List<String> blockedClasses = List.of();
        private String runId;

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder workingDirectory(String dir) {
            this.workingDirectory = dir;
            return this;
        }

        public Builder environment(Map<String, String> env) {
            this.environment = env;
            return this;
        }

        /** Host env keys the guest may inherit; pass {@code ["*"]} for full inheritance. */
        public Builder envAllowlist(List<String> keys) {
            this.envAllowlist = keys == null ? List.of() : List.copyOf(keys);
            return this;
        }

        /** Deny guest network access (default true). */
        public Builder networkBlocked(boolean blocked) {
            this.networkBlocked = blocked;
            return this;
        }

        /** Guest filesystem access policy (default WORKSPACE_ONLY). */
        public Builder fileAccess(FileAccessPolicy policy) {
            this.fileAccess = policy;
            return this;
        }

        /** Per-stream output capture cap; {@code <= 0} = unlimited. */
        public Builder outputLimitBytes(long bytes) {
            this.outputLimitBytes = bytes;
            return this;
        }

        public Builder memoryLimitBytes(long bytes) {
            this.memoryLimitBytes = bytes;
            return this;
        }

        public Builder blockedPackages(List<String> packages) {
            this.blockedPackages = packages;
            return this;
        }

        public Builder blockedClasses(List<String> classes) {
            this.blockedClasses = classes;
            return this;
        }

        /**
         * Attribution key for run-scoped accounting (escalation budget).
         * Null (default) = no attribution, instance-level budget applies.
         */
        public Builder runId(String runId) {
            this.runId = runId;
            return this;
        }

        public SandboxSpec build() {
            return new SandboxSpec(this);
        }
    }
}
