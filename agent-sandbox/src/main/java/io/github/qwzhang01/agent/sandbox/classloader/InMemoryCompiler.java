package io.github.qwzhang01.agent.sandbox.classloader;

import javax.tools.*;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Compiles Java source code in-memory using javax.tools.JavaCompiler.
 * <p>
 * No .java or .class files are written to disk.
 * The compiled bytecode is stored in memory and returned as a Map.
 * <p>
 * Requires JDK (not JRE) - JavaCompiler is part of tools.jar / jdk.compiler module.
 */
public class InMemoryCompiler {

    private final JavaCompiler compiler;

    public InMemoryCompiler() {
        this.compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException(
                    "JavaCompiler not available. Run with JDK, not JRE.");
        }
    }

    /**
     * Compile a single Java source file.
     *
     * @param className  the fully-qualified class name (e.g. "Generated")
     * @param sourceCode the Java source code
     * @return Map of class name -> bytecode bytes
     * @throws CompilationException if compilation fails
     */
    public Map<String, byte[]> compile(String className, String sourceCode) {
        InMemoryFileManager fileManager = new InMemoryFileManager(
                compiler.getStandardFileManager(null, null, null));

        JavaFileObject source = new InMemoryJavaSource(className, sourceCode);
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

        JavaCompiler.CompilationTask task = compiler.getTask(
                null,           // writer (null = System.err)
                fileManager,    // file manager (in-memory)
                diagnostics,    // error collector
                null,           // compiler options
                null,           // classes to process
                List.of(source) // source files
        );

        boolean success = task.call();

        if (!success) {
            StringBuilder error = new StringBuilder("Compilation failed:\n");
            for (Diagnostic<?> d : diagnostics.getDiagnostics()) {
                error.append("  Line ").append(d.getLineNumber())
                        .append(": ").append(d.getMessage(Locale.getDefault()))
                        .append("\n");
            }
            throw new CompilationException(error.toString());
        }

        return fileManager.getClassBytes();
    }

    // ============ Exceptions ============

    public static class CompilationException extends RuntimeException {
        public CompilationException(String message) {
            super(message);
        }
    }

    // ============ In-Memory Source ============

    /**
     * Represents a Java source file in memory.
     */
    static class InMemoryJavaSource extends SimpleJavaFileObject {
        private final String code;

        InMemoryJavaSource(String className, String code) {
            super(java.net.URI.create("string:///" + className.replace('.', '/') + ".java"),
                    Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }

    // ============ In-Memory File Manager ============

    /**
     * Collects compiled bytecode in memory instead of writing .class files.
     */
    static class InMemoryFileManager extends ForwardingJavaFileManager<JavaFileManager> {
        private final Map<String, byte[]> classBytes = new HashMap<>();

        InMemoryFileManager(JavaFileManager fileManager) {
            super(fileManager);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className,
                                                   JavaFileObject.Kind kind,
                                                   javax.tools.FileObject sibling) {
            return new InMemoryClassFile(className, kind);
        }

        Map<String, byte[]> getClassBytes() {
            return classBytes;
        }

        /**
         * Represents a compiled .class file in memory.
         * When the compiler writes bytecode, it goes into the Map.
         */
        class InMemoryClassFile extends SimpleJavaFileObject {
            private final String className;

            InMemoryClassFile(String className, JavaFileObject.Kind kind) {
                super(java.net.URI.create("mem:///" + className.replace('.', '/') + ".class"),
                        kind);
                this.className = className;
            }

            @Override
            public java.io.OutputStream openOutputStream() {
                return new java.io.ByteArrayOutputStream() {
                    @Override
                    public void close() {
                        classBytes.put(className, toByteArray());
                    }
                };
            }
        }
    }
}
