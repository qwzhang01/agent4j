package io.github.qwzhang01.agent.sandbox.classloader;

import io.github.qwzhang01.agent.sandbox.Sandbox;
import io.github.qwzhang01.agent.sandbox.SandboxResult;
import io.github.qwzhang01.agent.sandbox.SandboxSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
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
 * 4. Capture stdout on the sandbox thread via a ThreadLocal router
 *    (does not swap System.out/err per execute — concurrent runs stay isolated)
 * 5. Enforce timeout via Future + ExecutorService
 * <p>
 * Pros: fast (no JVM startup), in-process
 * Cons: not a true security boundary (reflection can escape)
 */
public class ClassLoaderSandbox implements Sandbox {

    private static final Logger log = LoggerFactory.getLogger(ClassLoaderSandbox.class);

    private static final PrintStream ORIGINAL_OUT;
    private static final PrintStream ORIGINAL_ERR;
    private static final ThreadLocal<OutputStream> LOCAL_OUT = new ThreadLocal<>();
    private static final ThreadLocal<OutputStream> LOCAL_ERR = new ThreadLocal<>();

    static {
        ORIGINAL_OUT = System.out;
        ORIGINAL_ERR = System.err;
        // One-time router: sandbox threads bind a buffer; everyone else hits the original stream.
        // execute() never calls System.setOut/setErr, so concurrent runs cannot clobber each other.
        System.setOut(new PrintStream(new RoutingOutputStream(LOCAL_OUT, ORIGINAL_OUT), true));
        System.setErr(new PrintStream(new RoutingOutputStream(LOCAL_ERR, ORIGINAL_ERR), true));
    }

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

        ByteArrayOutputStream stdoutBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream stderrBuffer = new ByteArrayOutputStream();

        try {
            Future<SandboxResult> future = executor.submit(() -> {
                LOCAL_OUT.set(stdoutBuffer);
                LOCAL_ERR.set(stderrBuffer);
                try {
                    Class<?> clazz = sandboxLoader.loadClass(className);
                    Method run = clazz.getMethod("run");

                    Object result = run.invoke(null);
                    String output = result != null ? result.toString() : "";

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
                } finally {
                    LOCAL_OUT.remove();
                    LOCAL_ERR.remove();
                }
            });

            try {
                return future.get(spec.getTimeout().toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                return SandboxResult.timeout(stdoutBuffer.toString());
            }

        } catch (Exception e) {
            return SandboxResult.error("Sandbox error: " + e.getMessage());
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Routes writes to a per-thread capture buffer when bound, otherwise to the
     * process stream captured at class-load time.
     */
    private static final class RoutingOutputStream extends OutputStream {
        private final ThreadLocal<OutputStream> local;
        private final OutputStream fallback;

        RoutingOutputStream(ThreadLocal<OutputStream> local, OutputStream fallback) {
            this.local = local;
            this.fallback = fallback;
        }

        private OutputStream dest() {
            OutputStream bound = local.get();
            return bound != null ? bound : fallback;
        }

        @Override
        public void write(int b) throws IOException {
            dest().write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            dest().write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            dest().flush();
        }
    }
}
