package io.github.qwzhang01.agent.sandbox.classloader;

import io.github.qwzhang01.agent.sandbox.Sandbox;
import io.github.qwzhang01.agent.sandbox.SandboxResult;
import io.github.qwzhang01.agent.sandbox.SandboxSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Sandbox implementation using ClassLoader isolation (方案2).
 * <p>
 * Flow:
 * 1. Compile Java source code in-memory (InMemoryCompiler)
 * 2. Load compiled bytecode with SandboxClassLoader (blocks dangerous classes)
 * 3. Find and invoke the "run()" method
 * 4. Capture stdout by redirecting System.out
 * 5. Enforce timeout via Future + ExecutorService
 * <p>
 * Pros: fast (no JVM startup), in-process
 * Cons: not a true security boundary (reflection can escape)
 */
public class ClassLoaderSandbox implements Sandbox {

    private static final Logger log = LoggerFactory.getLogger(ClassLoaderSandbox.class);

    private final SandboxSpec defaultSpec;
    private final InMemoryCompiler compiler;

    public ClassLoaderSandbox() {
        this(SandboxSpec.builder().build());
    }

    public ClassLoaderSandbox(SandboxSpec defaultSpec) {
        this.defaultSpec = defaultSpec;
        this.compiler = new InMemoryCompiler();
    }

    @Override
    public SandboxResult execute(String className, String code) {
        return execute(className, code, defaultSpec);
    }

    @Override
    public SandboxResult execute(String className, String code, SandboxSpec spec) {
        // 1. Compile
        Map<String, byte[]> classBytes;
        try {
            classBytes = compiler.compile(className, code);
        } catch (InMemoryCompiler.CompilationException e) {
            return SandboxResult.error(e.getMessage());
        }

        // 2. Create sandboxed ClassLoader
        SandboxClassLoader sandboxLoader = new SandboxClassLoader(
                getClass().getClassLoader(),
                classBytes,
                spec.getBlockedPackages(),
                spec.getBlockedClasses()
        );

        // 3. Execute with timeout
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "sandbox-executor");
            t.setDaemon(true);
            return t;
        });

        // Capture stdout
        ByteArrayOutputStream stdoutBuffer = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;

        try {
            System.setOut(new PrintStream(stdoutBuffer));
            System.setErr(new PrintStream(new ByteArrayOutputStream()));

            // Load and invoke in sandbox thread
            Future<SandboxResult> future = executor.submit(() -> {
                try {
                    Class<?> clazz = sandboxLoader.loadClass(className);
                    Method run = clazz.getMethod("run");

                    Object result = run.invoke(null);
                    String output = result != null ? result.toString() : "";

                    // Merge captured stdout with return value
                    String capturedStdout = stdoutBuffer.toString();
                    String fullOutput = capturedStdout.isEmpty()
                            ? output
                            : capturedStdout + (output.isEmpty() ? "" : "\n" + output);

                    return SandboxResult.success(fullOutput);
                } catch (SecurityException e) {
                    return SandboxResult.blocked(e.getMessage().replace("Blocked: ", ""));
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    return SandboxResult.error(cause.getClass().getSimpleName() + ": " + cause.getMessage());
                }
            });

            SandboxResult result;
            try {
                result = future.get(spec.getTimeout().toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                return SandboxResult.timeout(stdoutBuffer.toString());
            }

            return result;

        } catch (Exception e) {
            return SandboxResult.error("Sandbox error: " + e.getMessage());
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
            executor.shutdownNow();
        }
    }
}
