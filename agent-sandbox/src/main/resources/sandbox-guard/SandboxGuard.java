package io.github.qwzhang01.agent.sandbox.guard;

import java.io.File;
import java.io.FilePermission;
import java.lang.reflect.ReflectPermission;
import java.net.NetPermission;
import java.net.SocketPermission;
import java.security.Permission;
import java.util.PropertyPermission;

/**
 * In-guest security policy executor (Stage 4.1/4.2).
 * <p>
 * SOURCE-INJECTED, never on the host classpath: {@code ProcessSandbox} embeds
 * this class inside the guest source tree it compiles (its own package), so
 * the guard class lives in the guest JVM only. The host never loads it.
 * <p>
 * Enforcement model: a {@code SecurityManager} whose {@code checkPermission}
 * grants only what the tier's policy allows. The manager is deprecated for
 * REMOVAL in the JDK (JEP 411) - which is why this is the PROCESS-tier guest
 * guard, not the platform boundary. The DOCKER tier replaces it with
 * structural isolation (namespaces/cgroups/seccomp); see limitations.md.
 * <p>
 * Policy (mirrors {@code SandboxSpec} defaults):
 * <ul>
 *   <li>Filesystem: reads/writes confined to the working directory
 *       ({@code user.dir} in the guest) - WORKSPACE_ONLY.</li>
 *   <li>Network: denied by default (no connect/accept/resolve).</li>
 *   <li>Process: {@code Runtime.exec} requires EXECUTE, denied - no child
 *       processes, no fork bombs.</li>
 *   <li>Exit: guest may exit normally (its own JVM); {@code System.exit}
 *       with a nonzero code is allowed so error paths stay observable.</li>
 *   <li>Properties: read user.dir/java.io.tmpdir etc.; writing system
 *       properties is denied (mutation of the guest's own config).</li>
 *   <li>Everything not explicitly granted: DENIED with a policy-tagged
 *       message so the failure reads "blocked by sandbox policy", never
 *        "some error happened".</li>
 * </ul>
 */
public class SandboxGuard {

    /** Marker prefix for policy denials surfaced in guest stderr. */
    public static final String POLICY_DENIAL_PREFIX = "[SANDBOX_POLICY]";

    /** Set once installation completes; locks the setSecurityManager door. */
    static final java.util.concurrent.atomic.AtomicBoolean installed =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private SandboxGuard() {
    }

    /** Install the guard policy for this guest JVM run. */
    public static void install() {
        SecurityManager guard = new GuardManager();
        System.setSecurityManager(guard);
        installed.set(true);
    }

    static final class GuardManager extends SecurityManager {

        @Override
        public void checkPermission(Permission perm) {
            // Policy refusal, not a machinery crash: the caller pattern
            // (AccessController.doPrivileged not used by guest code) means
            // every check that falls through to deny() IS a policy denial.
            if (perm instanceof FilePermission fp) {
                checkFile(fp);
            } else if (perm instanceof SocketPermission) {
                deny(perm, "network access is denied by sandbox policy");
            } else if (perm instanceof NetPermission) {
                deny(perm, "network capability is denied by sandbox policy");
            } else if (perm instanceof PropertyPermission pp) {
                checkProperty(pp);
            } else if (perm instanceof RuntimePermission rp) {
                checkRuntime(rp);
            } else if (perm instanceof ReflectPermission) {
                // suppressAccessChecks: JDK internals (lambda metafactory,
                // method handles) run under AccessController.doPrivileged and
                // need this to bootstrap. Denying it breaks ANY guest code
                // containing a lambda or method reference (ProcessHandleImpl
                // hits it at class-init). The doPrivileged frames mean the
                // check originates in trusted JDK machinery, not the guest.
                return;
            } else {
                deny(perm, "permission is not granted by sandbox policy");
            }
        }

        @Override
        public void checkPermission(Permission perm, Object context) {
            checkPermission(perm);
        }

        private void checkFile(FilePermission fp) {
            String actions = fp.getActions();
            // Read of the guest's own working files and the JDK runtime is
            // the minimum viable grant set.
            if (actions.contains("read")) {
                if (isWorkspace(fp.getName()) || isJdkRuntime(fp.getName())) {
                    return;
                }
                deny(fp, "file read outside the workspace is denied by sandbox policy");
            }
            if (actions.contains("write") || actions.contains("delete")) {
                if (isWorkspace(fp.getName())) {
                    return;
                }
                deny(fp, "file write/delete outside the workspace is denied by sandbox policy");
            }
            if (actions.contains("execute")) {
                deny(fp, "process execution is denied by sandbox policy");
            }
        }

        private boolean isWorkspace(String path) {
            String workspace = System.getProperty("user.dir", "");
            if (workspace.isEmpty() || path == null) {
                return false;
            }
            File f = new File(path);
            File ws = new File(workspace);
            return isUnder(f, ws) || f.equals(ws);
        }

        private boolean isJdkRuntime(String path) {
            if (path == null) {
                return false;
            }
            String javaHome = System.getProperty("java.home", "");
            return !javaHome.isEmpty() && path.startsWith(javaHome);
        }

        private boolean isUnder(File child, File root) {
            File cur = child.getAbsoluteFile().getParentFile();
            File absRoot = root.getAbsoluteFile();
            while (cur != null) {
                if (cur.equals(absRoot)) {
                    return true;
                }
                cur = cur.getParentFile();
            }
            return false;
        }

        private void checkProperty(PropertyPermission pp) {
            if (!pp.getActions().contains("write")) {
                return; // reads are fine
            }
            deny(pp, "system property mutation is denied by sandbox policy");
        }

        private void checkRuntime(RuntimePermission rp) {
            String name = rp.getName();
            // Reading the guest's OWN environment (already allowlist-filtered
            // by the host) is benign; the guest cannot see host secrets that
            // were never passed in. System.getenv(name) checks "getenv.<name>"
            // (and bare "getenv" for the whole map) - grant the getenv family.
            // (Plain if instead of `case String s when ...`: the guard source
            // is re-compiled by the GUEST's javac, which may be older than
            // the host JDK - keep it at the project's release floor.)
            if (name.equals("getenv") || name.startsWith("getenv.")) {
                return;
            }
            switch (name) {
                case "exitVM" -> {
                    // The guest's own lifecycle belongs to the guest; the
                    // HOST enforces the real timeout via destroyForcibly.
                    return;
                }
                case "setSecurityManager", "createSecurityManager" -> {
                    // Assembly window: the launcher installs this manager
                    // before guest main runs; afterwards the door is locked.
                    if (!installed.get()) {
                        return;
                    }
                    deny(rp, "replacing the sandbox guard is denied by sandbox policy");
                }
                case "readFileDescriptor", "writeFileDescriptor" -> {
                    return; // stdout/stderr/stdin capture needs these
                }
                case "getStackTrace", "getStackWalk" -> {
                    return; // benign introspection
                }
                // Thread-group access: ProcessHandleImpl and the JDK's own
                // process-tree machinery walk thread groups under
                // doPrivileged during class-init. Guest code creating its
                // own threads still cannot escape: threads stay in the guest
                // JVM, which the host kills wholesale on timeout.
                case "modifyThreadGroup", "modifyThread" -> {
                    return;
                }
                // Self process introspection (ProcessHandle.current): the
                // guest reading its own PID/handle. Runtime.exec is NOT
                // affected - spawning processes goes through FilePermission
                // "execute", which this guard denies.
                case "getProcessHandle", "manageProcess" -> {
                    return;
                }
                // Thread-subclass security audit (Thread.auditSubclass runs
                // under doPrivileged when the guest defines a Thread subclass):
                // same JDK-internal-machinery family as suppressAccessChecks.
                case "accessDeclaredMembers" -> {
                    return;
                }
                default -> deny(rp, "runtime capability '" + name
                        + "' is denied by sandbox policy");
            }
        }

        private void deny(Permission perm, String reason) {
            // The denial message carries the policy tag so downstream
            // SandboxResult classification reads "policy refusal", not
            // "execution error". SecurityException lands in guest stderr;
            // the host captures it verbatim.
            throw new SecurityException(POLICY_DENIAL_PREFIX + " " + reason
                    + " (requested: " + perm.getName() + ")");
        }
    }
}
