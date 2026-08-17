package io.github.qwzhang01.agent.sandbox.classloader;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Custom ClassLoader that blocks access to dangerous packages/classes.
 * <p>
 * This is the ClassLoader sandbox (方案2): code runs in-process but
 * cannot load classes that could access the OS (file system, network,
 * process execution, reflection).
 * <p>
 * Limitation: ClassLoader isolation is NOT a security boundary.
 * It can be escaped via reflection, JNI, Unsafe, etc.
 * For LLM-generated code (not adversarial), it's sufficient.
 * For truly untrusted code, use ProcessSandbox.
 */
public class SandboxClassLoader extends ClassLoader {

    private final Map<String, byte[]> compiledClasses;
    private final Set<String> blockedPackages;
    private final Set<String> blockedClasses;

    /**
     * @param parent          the parent ClassLoader (usually the application ClassLoader)
     * @param compiledClasses bytecode from InMemoryCompiler
     * @param blockedPackages package prefixes to block (e.g. "java.io.File")
     * @param blockedClasses  specific class names to block
     */
    public SandboxClassLoader(ClassLoader parent,
                              Map<String, byte[]> compiledClasses,
                              List<String> blockedPackages,
                              List<String> blockedClasses) {
        super(parent);
        this.compiledClasses = compiledClasses;
        this.blockedPackages = new HashSet<>(blockedPackages);
        this.blockedClasses = new HashSet<>(blockedClasses);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        // 1. Check if this class is in the blocked list
        if (isBlocked(name)) {
            throw new SecurityException("Blocked: access to " + name + " is not allowed in sandbox");
        }

        // 2. Check if already loaded
        Class<?> loaded = findLoadedClass(name);
        if (loaded != null) {
            if (resolve) resolveClass(loaded);
            return loaded;
        }

        // 3. Check if it's one of our compiled classes
        byte[] bytecode = compiledClasses.get(name);
        if (bytecode != null) {
            Class<?> clazz = defineClass(name, bytecode, 0, bytecode.length);
            if (resolve) resolveClass(clazz);
            return clazz;
        }

        // 4. Delegate to parent for standard library classes
        return super.loadClass(name, resolve);
    }

    /**
     * Check if a class should be blocked.
     */
    private boolean isBlocked(String name) {
        // Check exact class name
        if (blockedClasses.contains(name)) {
            return true;
        }

        // Check package prefix
        for (String prefix : blockedPackages) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }

        return false;
    }
}
