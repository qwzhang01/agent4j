package io.github.qwzhang01.agent.sandbox.process;

import io.github.qwzhang01.agent.sandbox.Sandbox;
import io.github.qwzhang01.agent.sandbox.SandboxResult;
import io.github.qwzhang01.agent.sandbox.SandboxSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Sandbox implementation using process isolation (方案1).
 * <p>
 * Flow:
 * 1. Write Java source code to a temp directory (sandbox working directory)
 * 2. Compile with javac (subprocess, with timeout)
 * 3. Run with java (subprocess, with timeout + working directory restriction)
 * 4. Capture stdout/stderr
 * 5. Cleanup temp files
 * <p>
 * Pros: true OS-level isolation, secure
 * Cons: slow (JVM startup), needs JDK on PATH
 */
public class ProcessSandbox implements Sandbox {

    private static final Logger log = LoggerFactory.getLogger(ProcessSandbox.class);

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
            // 1. Create sandbox working directory
            String baseDir = spec.getWorkingDirectory() != null
                    ? spec.getWorkingDirectory()
                    : System.getProperty("java.io.tmpdir");
            sandboxDir = Files.createTempDirectory(Path.of(baseDir), "sandbox-");

            // 2. Write source code
            Path sourceFile = sandboxDir.resolve(className + ".java");
            Files.writeString(sourceFile, code);

            // 3. Compile with javac
            SandboxResult compileResult = runProcess(
                    List.of("javac", sourceFile.toString()),
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

            // 4. Run with java
            SandboxResult runResult = runProcess(
                    List.of("java", "-cp", sandboxDir.toString(), className),
                    sandboxDir,
                    spec,
                    "java"
            );
            return runResult;

        } catch (Exception e) {
            return SandboxResult.error("Sandbox error: " + e.getMessage());
        } finally {
            // 5. Cleanup
            if (sandboxDir != null) {
                cleanupSandboxDir(sandboxDir);
            }
        }
    }

    /**
     * Run a process with timeout and capture output.
     */
    private SandboxResult runProcess(List<String> command, Path workingDir,
                                     SandboxSpec spec, String label) {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDir.toFile());

        // Set environment (if specified)
        if (!spec.getEnvironment().isEmpty()) {
            pb.environment().putAll(spec.getEnvironment());
        }

        // Capture output
        pb.redirectErrorStream(false);

        try {
            Process process = pb.start();

            // Read stdout and stderr in separate threads (avoid deadlock)
            ByteArrayOutputStream stdoutBuffer = new ByteArrayOutputStream();
            ByteArrayOutputStream stderrBuffer = new ByteArrayOutputStream();

            Thread stdoutReader = new Thread(() -> readStream(process.getInputStream(), stdoutBuffer));
            Thread stderrReader = new Thread(() -> readStream(process.getErrorStream(), stderrBuffer));
            stdoutReader.start();
            stderrReader.start();

            boolean finished = process.waitFor(spec.getTimeout().toMillis(), TimeUnit.MILLISECONDS);

            if (!finished) {
                process.destroyForcibly();
                stdoutReader.join(500);
                stderrReader.join(500);
                return SandboxResult.timeout(stdoutBuffer.toString());
            }

            stdoutReader.join(1000);
            stderrReader.join(1000);

            int exitCode = process.exitValue();
            boolean success = exitCode == 0;
            String stdout = stdoutBuffer.toString();
            String stderr = stderrBuffer.toString();

            if (success) {
                return SandboxResult.success(stdout, stderr);
            } else {
                return new SandboxResult(
                        false, stdout, stderr, exitCode, false,
                        label + " exited with code " + exitCode
                );
            }

        } catch (Exception e) {
            return SandboxResult.error(label + " failed: " + e.getMessage());
        }
    }

    private void readStream(InputStream input, ByteArrayOutputStream buffer) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input))) {
            String line;
            while ((line = reader.readLine()) != null) {
                buffer.write(line.getBytes());
                buffer.write('\n');
            }
        } catch (IOException e) {
            // Stream closed, ignore
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
}
