package io.github.qwzhang01.agent.sandbox;

/**
 * Sandbox interface for executing code in isolation.
 * <p>
 * Two implementations:
 * - ClassLoaderSandbox: in-process, fast, uses custom ClassLoader to block dangerous classes
 * - ProcessSandbox: subprocess, secure, uses ProcessBuilder with timeout + working directory
 */
public interface Sandbox {

    /**
     * Execute Java source code in the sandbox.
     *
     * @param className the class name (must match the public class in the code)
     * @param code      the Java source code
     * @return execution result
     */
    SandboxResult execute(String className, String code);

    /**
     * Execute with custom spec.
     */
    SandboxResult execute(String className, String code, SandboxSpec spec);
}
