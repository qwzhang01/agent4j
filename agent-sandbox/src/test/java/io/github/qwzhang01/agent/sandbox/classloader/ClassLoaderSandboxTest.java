package io.github.qwzhang01.agent.sandbox.classloader;

import io.github.qwzhang01.agent.sandbox.SandboxResult;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ClassLoaderSandbox (方案2).
 * <p>
 * Verifies:
 * - Normal code execution works
 * - Dangerous classes (File, Runtime, ProcessBuilder) are blocked
 * - Compilation errors are reported
 * - Timeout works
 * - stdout capture works
 */
class ClassLoaderSandboxTest {

    private ClassLoaderSandbox sandbox;

    @BeforeEach
    void setUp() {
        sandbox = new ClassLoaderSandbox();
    }

    // ============ Normal Execution ============

    @Test
    @DisplayName("Simple arithmetic code executes successfully")
    void testSimpleCode() {
        String code = """
                public class Generated {
                    public static String run() {
                        int a = 1 + 2;
                        return "Result: " + a;
                    }
                }
                """;

        SandboxResult result = sandbox.execute("Generated", code);

        assertTrue(result.success());
        assertTrue(result.stdout().contains("Result: 3"));
    }

    @Test
    @DisplayName("Code with string manipulation")
    void testStringCode() {
        String code = """
                public class Generated {
                    public static String run() {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < 5; i++) {
                            sb.append(i).append(" ");
                        }
                        return sb.toString().trim();
                    }
                }
                """;

        SandboxResult result = sandbox.execute("Generated", code);

        assertTrue(result.success());
        assertEquals("0 1 2 3 4", result.stdout());
    }

    @Test
    @DisplayName("Code with List and Map (standard library)")
    void testCollections() {
        String code = """
                import java.util.*;
                public class Generated {
                    public static String run() {
                        List<String> list = new ArrayList<>();
                        list.add("hello");
                        list.add("world");
                        Map<String, Integer> map = new HashMap<>();
                        map.put("count", list.size());
                        return map.toString();
                    }
                }
                """;

        SandboxResult result = sandbox.execute("Generated", code);

        assertTrue(result.success());
        assertTrue(result.stdout().contains("count=2"));
    }

    // ============ Blocking Dangerous Classes ============

    @Test
    @DisplayName("java.io.File access is blocked")
    void testFileAccessBlocked() {
        String code = """
                import java.io.File;
                public class Generated {
                    public static String run() {
                        File f = new File("/etc/passwd");
                        return f.exists() ? "exists" : "not exists";
                    }
                }
                """;

        SandboxResult result = sandbox.execute("Generated", code);

        assertFalse(result.success());
        assertTrue(result.error().contains("Blocked") || result.stdout().contains("Blocked"));
    }

    @Test
    @DisplayName("Runtime.exec is blocked")
    void testRuntimeExecBlocked() {
        String code = """
                public class Generated {
                    public static String run() {
                        Runtime.getRuntime().exec("ls");
                        return "executed";
                    }
                }
                """;

        SandboxResult result = sandbox.execute("Generated", code);

        assertFalse(result.success());
    }

    @Test
    @DisplayName("ProcessBuilder is blocked")
    void testProcessBuilderBlocked() {
        String code = """
                import java.util.*;
                public class Generated {
                    public static String run() {
                        new ProcessBuilder("ls").start();
                        return "executed";
                    }
                }
                """;

        SandboxResult result = sandbox.execute("Generated", code);

        assertFalse(result.success());
    }

    @Test
    @DisplayName("Network access (java.net.Socket) is blocked")
    void testNetworkBlocked() {
        String code = """
                import java.net.*;
                public class Generated {
                    public static String run() {
                        new Socket("evil.com", 80);
                        return "connected";
                    }
                }
                """;

        SandboxResult result = sandbox.execute("Generated", code);

        assertFalse(result.success());
    }

    // ============ Compilation Errors ============

    @Test
    @DisplayName("Compilation error is reported")
    void testCompilationError() {
        String code = """
                public class Generated {
                    public static String run() {
                        int x = "not an int";  // type error
                        return x;
                    }
                }
                """;

        SandboxResult result = sandbox.execute("Generated", code);

        assertFalse(result.success());
        assertNotNull(result.error());
    }

    @Test
    @DisplayName("Runtime exception in code is captured")
    void testRuntimeException() {
        String code = """
                public class Generated {
                    public static String run() {
                        int[] arr = new int[0];
                        return "value: " + arr[5];  // ArrayIndexOutOfBounds at runtime
                    }
                }
                """;

        SandboxResult result = sandbox.execute("Generated", code);

        assertFalse(result.success());
        assertTrue(result.error().contains("ArrayIndexOutOfBoundsException"));
    }

    // ============ stdout Capture ============

    @Test
    @DisplayName("System.out.println output is captured")
    void testStdoutCapture() {
        String code = """
                public class Generated {
                    public static String run() {
                        System.out.println("hello from stdout");
                        return "return value";
                    }
                }
                """;

        SandboxResult result = sandbox.execute("Generated", code);

        assertTrue(result.success());
        assertTrue(result.stdout().contains("hello from stdout"));
        assertTrue(result.stdout().contains("return value"));
    }

    // ============ Timeout ============

    @Test
    @DisplayName("Infinite loop is terminated by timeout")
    void testTimeout() {
        String code = """
                public class Generated {
                    public static String run() {
                        while (true) {
                            // infinite loop
                        }
                    }
                }
                """;

        SandboxResult result = sandbox.execute("Generated", code,
                io.github.qwzhang01.agent.sandbox.SandboxSpec.builder()
                        .timeout(java.time.Duration.ofSeconds(2))
                        .build());

        assertFalse(result.success());
        assertTrue(result.timedOut());
    }
}
