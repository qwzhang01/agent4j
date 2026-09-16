package io.github.qwzhang01.agent.sandbox.guard;

import java.lang.reflect.Method;

/**
 * Guest entrypoint wrapper (Stage 4.1).
 * <p>
 * The guest class runs as-is; the LAUNCHER runs first, installs the guard,
 * then invokes {@code <GuestClass>.main(String[])} reflectively. The guest
 * source never mentions the guard - zero coupling between guest code and
 * the enforcement machinery.
 * <p>
 * The guard's {@code createSecurityManager / setSecurityManager} RuntimePermission
 * grant applies to the launcher itself; after install, the guest runs fully
 * under policy.
 */
public class SandboxGuestLauncher {

    public static void main(String[] args) throws Exception {
        String guestClass = args[0];
        String[] guestArgs = new String[args.length - 1];
        System.arraycopy(args, 1, guestArgs, 0, guestArgs.length);

        // Install the guard BEFORE touching the guest class.
        SandboxGuard.install();

        Class<?> clazz = Class.forName(guestClass);
        Method main = clazz.getDeclaredMethod("main", String[].class);
        main.invoke(null, (Object) guestArgs);
    }
}
