package io.github.qwzhang01.agent.examples;

import io.github.qwzhang01.agent.sandbox.Sandbox;
import io.github.qwzhang01.agent.sandbox.SandboxResult;
import io.github.qwzhang01.agent.sandbox.classloader.ClassLoaderSandbox;

/**
 * Demonstrates the sandbox executing LLM-written Java code.
 * <p>
 * Shows:
 * 1. Normal code executes successfully
 * 2. File access is blocked
 * 3. Process execution is blocked
 * 4. Network access is blocked
 * 5. Infinite loop times out
 */
public class SandboxExample {

    public static void main(String[] args) {
        System.out.println("=== Sandbox Demo ===\n");
        Sandbox sandbox = new ClassLoaderSandbox();

        // 1. Normal code
        System.out.println("--- Test 1: Normal code ---");
        SandboxResult r1 = sandbox.execute("Generated", """
                public class Generated {
                    public static String run() {
                        int sum = 0;
                        for (int i = 1; i <= 100; i++) sum += i;
                        return "Sum 1-100 = " + sum;
                    }
                }
                """);
        System.out.println("Success: " + r1.success());
        System.out.println("Output:  " + r1.stdout() + "\n");

        // 2. File access blocked
        System.out.println("--- Test 2: File access blocked ---");
        SandboxResult r2 = sandbox.execute("Generated", """
                import java.io.File;
                public class Generated {
                    public static String run() {
                        File f = new File("/etc/passwd");
                        return f.exists() ? "exists" : "not exists";
                    }
                }
                """);
        System.out.println("Success: " + r2.success());
        System.out.println("Error:   " + r2.error() + "\n");

        // 3. Process execution blocked
        System.out.println("--- Test 3: Runtime.exec blocked ---");
        SandboxResult r3 = sandbox.execute("Generated", """
                public class Generated {
                    public static String run() {
                        Runtime.getRuntime().exec("rm -rf /");
                        return "executed";
                    }
                }
                """);
        System.out.println("Success: " + r3.success());
        System.out.println("Error:   " + r3.error() + "\n");

        // 4. Network blocked
        System.out.println("--- Test 4: Network blocked ---");
        SandboxResult r4 = sandbox.execute("Generated", """
                import java.net.Socket;
                public class Generated {
                    public static String run() {
                        new Socket("evil.com", 80);
                        return "connected";
                    }
                }
                """);
        System.out.println("Success: " + r4.success());
        System.out.println("Error:   " + r4.error() + "\n");

        // 5. Timeout
        System.out.println("--- Test 5: Infinite loop times out ---");
        SandboxResult r5 = sandbox.execute("Generated", """
                public class Generated {
                    public static String run() {
                        while (true) {}
                    }
                }
                """, io.github.qwzhang01.agent.sandbox.SandboxSpec.builder()
                .timeout(java.time.Duration.ofSeconds(2))
                .build());
        System.out.println("Success: " + r5.success());
        System.out.println("TimedOut: " + r5.timedOut() + "\n");

        System.out.println("=== Done ===");
    }
}
