package io.github.qwzhang01.agent.sandbox.process;

import io.github.qwzhang01.agent.sandbox.Sandbox;
import io.github.qwzhang01.agent.sandbox.SandboxResult;
import io.github.qwzhang01.agent.sandbox.SandboxSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Sandbox implementation using process isolation (方案1).
 * <p>
 * Flow:
 * 1. Validate className (legal Java identifier - no path traversal, )
 * 2. Create a workspace dir under the canonicalized base (containment check)
 * 3. Write source + the in-guest SandboxGuard class (source-injected policy)
 * 4. Compile with javac (subprocess, timeout)
 * 5. Run with java (timeout, env allowlist, output caps, tree-kill on timeout)
 * 6. Cleanup workspace recursively
 * <p>
 * Pros: true OS-level isolation, secure
 * Cons: slow (JVM startup), needs JDK on PATH
 * <p>
 * hardening (see limitations.md for the honest boundary):
 * <ul>
 *   <li>className whitelist {@code ^[A-Za-z_$][A-Za-z0-9_$]*$} - no separators,
 *       no path fragments, no "..".</li>
 *   <li>Working directory canonicalized and containment-checked against the
 *       requested base.</li>
 *   <li>Env: allowlist inheritance only (default PATH/TMPDIR/LANG/TZ/LC_*),
 *       overlay env applied on top; the full host environment is never
 *       inherited unless {@code ENV_INHERIT_ALL} is passed.</li>
 *   <li>stdout/stderr capped at {@code outputLimitBytes} per stream with a
 *       truncation marker.</li>
 *   <li>Timeout kills the whole process tree (descendants first), not just
 *       the direct child.</li>
 *   <li>The in-guest guard enforces WORKSPACE_ONLY file access and
 *       network denial at the guest SecurityManager level; see
 *       {@code SandboxGuard} (source-injected, never on the host classpath).</li>
 * </ul>
 */
public class ProcessSandbox implements Sandbox {

    private static final Logger log = LoggerFactory.getLogger(ProcessSandbox.class);

    static final Pattern CLASS_NAME_PATTERN =
            Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*$");

    private static final String GUARD_PACKAGE = "io.github.qwzhang01.agent.sandbox.guard";
    private static final String GUARD_CLASS_NAME = GUARD_PACKAGE + ".SandboxGuard";

    private static final String TRUNCATION_MARKER =
            "\\n...[output truncated by sandbox: stream exceeded outputLimitBytes]";

    private final SandboxSpec defaultSpec;

    public ProcessSandbox() {
        this(SandboxSpec.builder().build());
    }

    public ProcessSandbox(SandboxSpec defaultSpec) {
        this.defaultSpec = defaultSpec;
    }

    // Helper to create SandboxResult (avoids name collision with static method)
    private static SandboxResult SandboxResult(boolean success, String stdout, String stderr,
                                               int exitCode, boolean timedOut, String error) {
        return new SandboxResult(success, stdout, stderr, exitCode, timedOut, error);
    }

    @Override
    public SandboxResult execute(String className, String code) {
        return execute(className, code, defaultSpec);
    }

    @Override
    public SandboxResult execute(String className, String code, SandboxSpec spec) {
        Path sandboxDir = null;

        try {
            if (className == null || !CLASS_NAME_PATTERN.matcher(className).matches()) {
                return SandboxResult.error(
                        "[INVALID_CLASS_NAME] className must be a legal Java identifier, got: "
                                + (className == null ? "null" : "'" + className + "'"),
                        SandboxResult.FailureKind.BLOCKED_BY_POLICY);
            }

            // 1. Create sandbox working directory (canonicalized containment)
            String baseDir = spec.getWorkingDirectory() != null
                    ? spec.getWorkingDirectory()
                    : System.getProperty("java.io.tmpdir");
            Path base = Path.of(baseDir).toAbsolutePath().normalize();
            if (!Files.isDirectory(base)) {
                return SandboxResult.sandboxFailure(
                        "[INVALID_WORKSPACE] working directory does not exist: " + base);
            }
            Path canonicalBase = base.toRealPath();
            sandboxDir = Files.createTempDirectory(canonicalBase, "sandbox-");

            // 2. Write guest source + the source-injected guard
            Path sourceFile = sandboxDir.resolve(className + ".java");
            Files.writeString(sourceFile, code);
            writeGuardSource(sandboxDir);

            // 3. Compile with javac from the running JDK (not PATH)
            SandboxResult compileResult = runProcess(
                    compileCommand(sourceFile),
                    sandboxDir,
                    spec,
                    "javac"
            );
            if (!compileResult.success()) {
                return SandboxResult(
                        false,
                        compileResult.stdout(),
                        compileResult.stderr(),
                        compileResult.exitCode(),
                        compileResult.timedOut(),
                        "Compilation failed"
                );
            }

            // 4. Run with java. -Xmx applies here (not to javac): the child's
            // heap is the sandbox memory ceiling. memoryLimitBytes <= 0 means
            // do not pass a cap (host JVM default).
            SandboxResult runResult = runProcess(
                    javaCommand(sandboxDir, className, spec),
                    sandboxDir,
                    spec,
                    "java"
            );
            return runResult;

        } catch (Exception e) {
            return SandboxResult.sandboxFailure("Sandbox error: " + e.getMessage());
        } finally {
            // 5. Cleanup
            if (sandboxDir != null) {
                cleanupSandboxDir(sandboxDir);
            }
        }
    }

    /**
     * Write the in-guest guard + launcher sources under their own package
     * directory so they compile alongside the guest code and load in the
     * guest JVM only.
     */
    private static void writeGuardSource(Path sandboxDir) throws IOException {
        Path guardDir = sandboxDir.resolve("io/github/qwzhang01/agent/sandbox/guard");
        Files.createDirectories(guardDir);
        Files.writeString(guardDir.resolve("SandboxGuard.java"), GUARD_SOURCE);
        Files.writeString(guardDir.resolve("SandboxGuestLauncher.java"), LAUNCHER_SOURCE);
    }

    static List<String> compileCommand(Path sourceFile) {
        // Compile the guest class plus the guard & launcher; their package
        // directory is on the sourcepath so javac finds them.
        Path guardDir = sourceFile.getParent()
                .resolve("io/github/qwzhang01/agent/sandbox/guard");
        return List.of(
                javacBinary(),
                "-d", sourceFile.getParent().toString(),
                sourceFile.toString(),
                guardDir.resolve("SandboxGuard.java").toString(),
                guardDir.resolve("SandboxGuestLauncher.java").toString()
        );
    }

    private static String javaBinary() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /**
     * {@code java [-Xmx<bytes>] -cp <dir> Launcher <GuestClass>}.
     * The launcher installs the in-guest guard first, then invokes the
     * guest's {@code main} reflectively .
     * Suffix-less {@code -Xmx} is bytes (HotSpot). Prefer {@code m} when the
     * limit is an exact megabyte so the flag stays readable in process lists.
     * <p>
     *  {@code -Djava.security.manager=allow} re-permits
     * {@code System.setSecurityManager} on JDK 18-23 (JEP 411 moved it to
     * disallow-by-default; JDK 21 throws UnsupportedOperationException without
     * the flag). Harmless on JDK 17 (a warning). On JDK 24+ (JEP 486 removed
     * SecurityManager entirely) the flag itself aborts VM init - the guard's
     * degraded-mode fallback is the honest answer there, and
     * {@link #javaCommand} keeps the flag off when the RUNNING JDK is 24+.
     */
    static List<String> javaCommand(Path sandboxDir, String className, SandboxSpec spec) {
        List<String> command = new ArrayList<>();
        command.add(javaBinary());
        if (securityManagerInstallable()) {
            command.add("-Djava.security.manager=allow");
        }
        long limit = spec.getMemoryLimitBytes();
        if (limit > 0) {
            command.add(xmxFlag(limit));
        }
        command.add("-cp");
        command.add(sandboxDir.toString());
        command.add(GUARD_PACKAGE + ".SandboxGuestLauncher");
        command.add(className);
        return command;
    }

    /**
     * Whether the RUNNING JDK still supports installing a SecurityManager at
     * all. JDK 24 (JEP 486) removed it: {@code -Djava.security.manager=allow}
     * aborts VM init with "Enabling a Security Manager is not supported", so
     * the flag must not be passed there. Detected from
     * {@code java.specification.version} (17, 21, 24...).
     */
    static boolean securityManagerInstallable() {
        try {
            int feature = Integer.parseInt(System.getProperty("java.specification.version")
                    .strip());
            return feature < 24;
        } catch (NumberFormatException | NullPointerException e) {
            // Unparseable version string: assume a JDK that still has it
            // (fail toward the stricter guard, not the weaker).
            return true;
        }
    }

    static String xmxFlag(long bytes) {
        long megabyte = 1024L * 1024L;
        if (bytes >= megabyte && bytes % megabyte == 0) {
            return "-Xmx" + (bytes / megabyte) + "m";
        } else if (bytes >= 1024 && bytes % 1024 == 0) {
            return "-Xmx" + (bytes / 1024) + "k";
        }
        return "-Xmx" + bytes;
    }

    private static String javacBinary() {
        Path home = Path.of(System.getProperty("java.home"));
        Path javac = home.resolve("bin").resolve("javac");
        if (Files.isExecutable(javac)) {
            return javac.toString();
        }
        Path sibling = home.getParent() != null
                ? home.getParent().resolve("bin").resolve("javac")
                : null;
        if (sibling != null && Files.isExecutable(sibling)) {
            return sibling.toString();
        }
        return "javac";
    }

    /**
     * Build the guest environment: allowlist-filtered host env + the overlay
     * env from the spec. The full host environment is never inherited unless
     * the allowlist is explicitly {@link SandboxSpec#ENV_INHERIT_ALL}.
     */
    static Map<String, String> guestEnvironment(ProcessBuilder pb, SandboxSpec spec) {
        Map<String, String> guest = new HashMap<>();
        List<String> allow = spec.getEnvAllowlist();
        boolean inheritAll = allow.contains("*");
        if (inheritAll) {
            guest.putAll(pb.environment());
        } else {
            Map<String, String> host = pb.environment();
            for (String key : allow) {
                String value = host.get(key);
                if (value != null) {
                    guest.put(key, value);
                }
            }
        }
        // Overlay env wins over inherited values.
        guest.putAll(spec.getEnvironment());
        return guest;
    }

    /**
     * Run a process with timeout and capture output.
     */
    private SandboxResult runProcess(List<String> command, Path workingDir,
                                     SandboxSpec spec, String label) {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDir.toFile());

        Map<String, String> env = guestEnvironment(pb, spec);
        pb.environment().clear();
        pb.environment().putAll(env);
        pb.environment().put("SANDBOX_WORKSPACE", workingDir.toString());

        pb.redirectErrorStream(false);

        try {
            Process process = pb.start();

            // Read stdout and stderr in separate threads (avoid deadlock),
            long cap = spec.getOutputLimitBytes();
            CappedBuffer stdoutBuffer = new CappedBuffer(cap);
            CappedBuffer stderrBuffer = new CappedBuffer(cap);

            Thread stdoutReader = new Thread(() -> readStream(process.getInputStream(), stdoutBuffer));
            Thread stderrReader = new Thread(() -> readStream(process.getErrorStream(), stderrBuffer));
            stdoutReader.start();
            stderrReader.start();

            boolean finished = process.waitFor(spec.getTimeout().toMillis(), TimeUnit.MILLISECONDS);

            if (!finished) {
                killProcessTree(process);
                stdoutReader.join(500);
                stderrReader.join(500);
                return SandboxResult.timeout(stdoutBuffer.capturedText());
            }

            stdoutReader.join(1000);
            stderrReader.join(1000);

            int exitCode = process.exitValue();
            boolean success = exitCode == 0;
            String stdout = stdoutBuffer.capturedText();
            String stderr = stderrBuffer.capturedText();

            if (success) {
                return SandboxResult.success(stdout, stderr);
            } else {
                return new SandboxResult(
                        false, stdout, stderr, exitCode, false,
                        label + " exited with code " + exitCode
                );
            }

        } catch (Exception e) {
            return SandboxResult.sandboxFailure(label + " failed: " + e.getMessage());
        }
    }

    /**
     * Kill the direct child and all its descendants. {@code destroyForcibly}
     * on the direct child leaves grandchildren running; the tree walk closes
     * that hole (fork-bomb-shaped escapes die with the tree).
     */
    static void killProcessTree(Process process) {
        try {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
        } catch (Exception e) {
            log.debug("descendant kill failed: {}", e.getMessage());
        }
        try {
            process.destroyForcibly();
        } catch (Exception e) {
            log.debug("child kill failed: {}", e.getMessage());
        }
    }

    private void readStream(InputStream input, CappedBuffer buffer) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input))) {
            String line;
            while ((line = reader.readLine()) != null) {
                buffer.writeLine(line);
            }
        } catch (IOException e) {
            // Stream closed, ignore
        }
    }

    static final class CappedBuffer {
        private final long capBytes;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private boolean truncated = false;

        CappedBuffer(long capBytes) {
            this.capBytes = capBytes;
        }

        void writeLine(String line) {
            byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
            if (capBytes > 0 && buffer.size() + bytes.length > capBytes) {
                truncated = true;
                return;
            }
            buffer.writeBytes(bytes);
        }

        String capturedText() {
            String text = buffer.toString(StandardCharsets.UTF_8);
            return truncated ? text + TRUNCATION_MARKER : text;
        }

        boolean wasTruncated() {
            return truncated;
        }
    }

    private void cleanupSandboxDir(Path dir) {
        try {
            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException e) {
            log.warn("Failed to cleanup sandbox dir: {}", dir, e);
        }
    }

    // Guard source (source-injected into the guest)

    /**
     * The guard source text embedded in the guest compilation. Loaded from
     * the classpath resource so the guard compiled here is the one shipped.
     */
    private static final String GUARD_SOURCE = loadGuardSource();

    private static final String LAUNCHER_SOURCE = loadLauncherSource();

    private static String loadGuardSource() {
        try (InputStream in = ProcessSandbox.class.getResourceAsStream(
                "/sandbox-guard/SandboxGuard.java")) {
            if (in == null) {
                throw new IllegalStateException(
                        "SandboxGuard.java resource missing on classpath - "
                                + "process sandbox cannot inject the guard");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read SandboxGuard source resource", e);
        }
    }

    private static String loadLauncherSource() {
        try (InputStream in = ProcessSandbox.class.getResourceAsStream(
                "/sandbox-guard/SandboxGuestLauncher.java")) {
            if (in == null) {
                throw new IllegalStateException(
                        "SandboxGuestLauncher.java resource missing on classpath - "
                                + "process sandbox cannot inject the launcher");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read SandboxGuestLauncher source resource", e);
        }
    }
}
