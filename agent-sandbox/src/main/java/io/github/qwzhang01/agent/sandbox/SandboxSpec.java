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
 * - environment: env vars passed to the process
 * - memoryLimitBytes: max memory (process sandbox only)
 * - blockedPackages: Java packages blocked by ClassLoader sandbox
 * - blockedClasses: specific fully-qualified class names blocked
 * <p>
 * Use Builder pattern for construction.
 */
public class SandboxSpec {

    private final Duration timeout;
    private final String workingDirectory;
    private final Map<String, String> environment;
    private final long memoryLimitBytes;
    private final List<String> blockedPackages;
    private final List<String> blockedClasses;

    private SandboxSpec(Builder builder) {
        this.timeout = builder.timeout;
        this.workingDirectory = builder.workingDirectory;
        this.environment = builder.environment;
        this.memoryLimitBytes = builder.memoryLimitBytes;
        this.blockedPackages = builder.blockedPackages;
        this.blockedClasses = builder.blockedClasses;
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

    public long getMemoryLimitBytes() {
        return memoryLimitBytes;
    }

    public List<String> getBlockedPackages() {
        return blockedPackages;
    }

    public List<String> getBlockedClasses() {
        return blockedClasses;
    }

    public static class Builder {
        private Duration timeout = Duration.ofSeconds(10);
        private String workingDirectory;
        private Map<String, String> environment = Map.of();
        private long memoryLimitBytes = 256 * 1024 * 1024; // 256MB default
        private List<String> blockedPackages = defaultBlockedPackages();
        private List<String> blockedClasses = List.of();

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

        public SandboxSpec build() {
            return new SandboxSpec(this);
        }
    }
}
