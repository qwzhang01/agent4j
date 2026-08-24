package io.github.qwzhang01.agent.coding.exec;

import io.github.qwzhang01.agent.sandbox.SandboxResult;
import io.github.qwzhang01.agent.sandbox.SandboxSpec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * The no-shell command executor - gate 3 of the command defense (Stage 17 M17.3,
 * blueprint D2/D5).
 * <p>
 * <b>No shell anywhere</b>: {@code ProcessBuilder} receives the argv directly, no
 * {@code /bin/sh -c} is ever involved, so injection syntax ({@code ; | && $( ) ` >})
 * inside arguments is inert by construction - it is just an argument nobody interprets.
 * <p>
 * Blueprint D5 (the Stage 4 sandbox field check): the <b>data contracts are reused</b>
 * ({@link SandboxSpec} in, {@link SandboxResult} out - timeout/env/result semantics are
 * not re-invented), and the <b>process pattern is re-implemented</b> (dual-stream
 * reading to avoid pipe deadlock, timeout with destroyForcibly - the same shape as
 * {@code ProcessSandbox.runProcess}). What is deliberately NOT reused is
 * {@code ProcessSandbox.execute()} itself: its temp-dir-and-burn isolation model
 * contradicts "the command must see the real workspace" - here the cwd is
 * <b>pinned to the workspace root</b> ({@code spec.workingDirectory} is ignored, javadoc
 * honesty over silent surprise).
 * <p>
 * Output budget: each stream (stdout/stderr) is truncated to {@code maxOutputBytes}
 * <b>keeping head and tail halves</b> with an honest marker; a hard in-memory cap of
 * 4x the budget protects the JVM from unbounded producers (beyond that, only the byte
 * count keeps running - the marker reports the true total).
 */
public final class CommandRunner {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    public static final long DEFAULT_MAX_OUTPUT_BYTES = 64 * 1024;
    private static final int HARD_CAP_FACTOR = 4;

    private final Path workingDirectory;
    private final Duration defaultTimeout;
    private final long maxOutputBytes;

    public CommandRunner(Path workingDirectory) {
        this(workingDirectory, DEFAULT_TIMEOUT, DEFAULT_MAX_OUTPUT_BYTES);
    }

    public CommandRunner(Path workingDirectory, Duration defaultTimeout, long maxOutputBytes) {
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory must not be null");
        this.defaultTimeout = Objects.requireNonNull(defaultTimeout, "defaultTimeout must not be null");
        if (maxOutputBytes <= 0) {
            throw new IllegalArgumentException("maxOutputBytes must be positive");
        }
        this.maxOutputBytes = maxOutputBytes;
    }

    /** Run with the default timeout; see {@link #run(List, SandboxSpec)}. */
    public SandboxResult run(List<String> argv) {
        return run(argv, null);
    }

    /**
     * Run {@code argv} with the process cwd pinned to the workspace root.
     * <p>
     * From the spec, {@code timeout} and {@code environment} are honored;
     * {@code workingDirectory} is deliberately ignored (anchoring is gate 3, not a hint).
     *
     * @throws IllegalArgumentException on null/empty argv or blank elements (caller -
     *                                  the whitelist - should have caught these already)
     */
    public SandboxResult run(List<String> argv, SandboxSpec spec) {
        if (argv == null || argv.isEmpty()) {
            throw new IllegalArgumentException("command must not be null or empty");
        }
        for (String element : argv) {
            if (element == null || element.isBlank()) {
                throw new IllegalArgumentException("command arguments must not be null or blank");
            }
        }
        Duration timeout = (spec != null && spec.getTimeout() != null)
                ? spec.getTimeout() : defaultTimeout;

        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.directory(workingDirectory.toFile());
        if (spec != null && spec.getEnvironment() != null && !spec.getEnvironment().isEmpty()) {
            pb.environment().putAll(spec.getEnvironment());
        }
        pb.redirectErrorStream(false);

        try {
            Process process = pb.start();

            CapturedOutput stdout = new CapturedOutput(maxOutputBytes);
            CapturedOutput stderr = new CapturedOutput(maxOutputBytes);
            Thread stdoutReader = new Thread(() -> drain(process.getInputStream(), stdout));
            Thread stderrReader = new Thread(() -> drain(process.getErrorStream(), stderr));
            stdoutReader.start();
            stderrReader.start();

            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);

            if (!finished) {
                process.destroyForcibly();
                stdoutReader.join(500);
                stderrReader.join(500);
                return new SandboxResult(false, stdout.render(maxOutputBytes),
                        stderr.render(maxOutputBytes), -1, true, "Execution timed out");
            }

            stdoutReader.join(1000);
            stderrReader.join(1000);

            int exitCode = process.exitValue();
            String out = stdout.render(maxOutputBytes);
            String err = stderr.render(maxOutputBytes);
            if (exitCode == 0) {
                return new SandboxResult(true, out, err, exitCode, false, null);
            }
            return new SandboxResult(false, out, err, exitCode, false,
                    "command exited with code " + exitCode);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SandboxResult.error("command execution interrupted: " + e.getMessage());
        } catch (IOException e) {
            return SandboxResult.error("command failed to start: " + e.getMessage());
        }
    }

    // ============ Output capture with head/tail budget ============

    /**
     * Accumulates stream bytes up to a hard in-memory cap (4x budget) while counting
     * the true total; renders either the full text or head+tail halves with a marker.
     */
    private static final class CapturedOutput {
        private final long hardCapBytes;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private long totalBytes;

        CapturedOutput(long maxOutputBytes) {
            this.hardCapBytes = maxOutputBytes * HARD_CAP_FACTOR;
        }

        void append(byte[] data, int length) {
            totalBytes += length;
            if (buffer.size() < hardCapBytes) {
                int room = (int) Math.min(length, hardCapBytes - buffer.size());
                buffer.write(data, 0, room);
            }
            // beyond the hard cap only the count keeps running - the marker in render()
            // reports the true total, the tail degrades honestly (v1 boundary)
        }

        String render(long maxOutputBytes) {
            if (totalBytes <= maxOutputBytes) {
                return buffer.toString(StandardCharsets.UTF_8);
            }
            // head + tail halves of the budget, honest marker with the true total
            byte[] all = buffer.toByteArray();
            int half = (int) Math.min(maxOutputBytes / 2, all.length);
            String head = new String(all, 0, half, StandardCharsets.UTF_8);
            String tail = all.length > half
                    ? new String(all, all.length - half, half, StandardCharsets.UTF_8)
                    : "";
            return head + "\n...[TRUNCATED: showing " + (half * 2) + " of "
                    + totalBytes + " bytes]...\n" + tail;
        }
    }

    private static void drain(InputStream input, CapturedOutput capture) {
        byte[] chunk = new byte[8192];
        try (InputStream in = input) {
            int n;
            while ((n = in.read(chunk)) != -1) {
                capture.append(chunk, n);
            }
        } catch (IOException ignored) {
            // stream closed / process killed - fine
        }
    }
}
