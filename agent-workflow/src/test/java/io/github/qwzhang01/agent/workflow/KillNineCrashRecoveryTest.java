package io.github.qwzhang01.agent.workflow;

import io.github.qwzhang01.agent.workflow.nodes.ActionNode;
import io.github.qwzhang01.agent.workflow.nodes.HumanApprovalNode;
import io.github.qwzhang01.agent.workflow.runtime.FileCheckpointStore;
import io.github.qwzhang01.agent.workflow.runtime.RunManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Debt 5 (KP8): REAL kill -9 crash recovery, across real OS processes.
 *
 * <p>E8SideEffectGapExperimentTest already proved cursor/isResuming/idempotency
 * semantics, but its honest boundary says: the "crash" was a fresh RunManager
 * in the SAME JVM — no real process death. This test pays that debt:
 * <ol>
 *   <li>A real child JVM (forked via ProcessBuilder) runs
 *       charge &rarr; approval &rarr; payout with a FileCheckpointStore, pauses
 *       at approval, persists the checkpoint, writes a paused.marker with the
 *       runId, then idles.</li>
 *   <li>The test JVM — a DIFFERENT OS process — calls
 *       {@link Process#destroyForcibly()} (SIGKILL on POSIX). No shutdown
 *       hooks, no in-memory residue: everything the recovery can use must
 *       already be on disk.</li>
 *   <li>The test JVM builds a brand-new RunManager over the dead process's
 *       checkpoint files and resumes. It must succeed, and the pre-pause
 *       side effect (charge) must NOT fire again in the recovering process.</li>
 * </ol>
 *
 * <p>What this proves beyond E8: the checkpoint survives REAL process death
 * (no shared memory, no warm caches, different JVM), and recovery relies on
 * nothing but the persisted files.
 *
 * <p>Honest boundaries of this test:
 * <ul>
 *   <li>The kill lands while the child is IDLE at the pause point — the
 *       checkpoint write is already complete. A kill DURING the checkpoint
 *       write (torn write) is a different failure mode, not covered here.</li>
 *   <li>POSIX only (SIGKILL semantics asserted: exit code 128+9=137);
 *       disabled on Windows where destroyForcibly is TerminateProcess.</li>
 *   <li>Single child, single machine, no concurrent writers on the store.</li>
 * </ul>
 */
class KillNineCrashRecoveryTest {

    private static final String INPUT = "refund#kill9";

    @TempDir
    Path shared;

    // ============ The test ============

    @Test
    @Timeout(90)
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void realKillNineAcrossProcessesRecoversFromCheckpoint() throws Exception {
        // ---- 1. Fork a real child JVM running the workflow to its pause point
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");
        ProcessBuilder pb = new ProcessBuilder(
                javaBin, "-cp", classpath, Child.class.getName(), shared.toString());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(
                shared.resolve("child.log").toFile()));
        Process child = pb.start();

        // ---- 2. Wait until the child is paused and the checkpoint is on disk
        Path marker = shared.resolve("paused.marker");
        long deadline = System.currentTimeMillis() + 60_000;
        while (!Files.exists(marker) && System.currentTimeMillis() < deadline) {
            if (!child.isAlive()) {
                fail("child died before reaching the pause point; log:\n" + childLog());
            }
            Thread.sleep(100);
        }
        assertTrue(Files.exists(marker), "child never paused; log:\n" + childLog());
        String runId = Files.readString(marker).trim();

        Path checkpointFile = shared.resolve("checkpoints").resolve(runId + ".json");
        assertTrue(Files.exists(checkpointFile),
                "checkpoint must be persisted BEFORE the kill - recovery cannot rely on memory");
        assertTrue(Files.exists(shared.resolve("request-" + runId + ".txt")),
                "approval request artifact from the child must be on disk");

        // The external side effect (charge) fired exactly once, by the child.
        assertEquals(1, chargeCount(), "charge must fire exactly once before the crash");

        // ---- 3. Kill -9 while the child is alive and idle at the pause point
        assertTrue(child.isAlive(),
                "child must still be alive at the pause point when we kill it");
        child.destroyForcibly();
        assertTrue(child.waitFor(10, TimeUnit.SECONDS), "child must die under SIGKILL");
        assertEquals(137, child.exitValue(),
                "POSIX convention: killed by signal 9 (SIGKILL) reports 128+9");

        // ---- 4. Human approves, in the world that survived the crash
        Files.writeString(shared.resolve("decision-" + runId + ".txt"), "APPROVED");

        // ---- 5. Recover: brand-new RunManager in THIS process over the dead
        //          process's checkpoint directory
        FileApprovalService approval = new FileApprovalService(shared);
        RunManager mgr = new RunManager(new FileCheckpointStore(shared.resolve("checkpoints")));
        ExecutionResult recovered = mgr.resume(runId, kill9Workflow(shared, approval));

        assertTrue(recovered.isSucceeded(),
                "resume after real kill -9 must succeed, got " + recovered.status()
                        + (recovered.errorMessage() != null ? " / " + recovered.errorMessage() : ""));
        assertEquals("payout for: charged:" + INPUT, recovered.output(),
                "blackboard state (charge output) must have survived the crash");

        // ---- 6. The recovering process must NOT re-execute the pre-pause node
        assertEquals(1, chargeCount(),
                "charge must not replay in the recovering process");
        long chargeSuccesses = recovered.trace().stream()
                .filter(r -> "charge".equals(r.nodeId()))
                .filter(r -> r.status() == StepRecord.Status.SUCCESS)
                .count();
        assertEquals(1, chargeSuccesses,
                "recovered trace must contain exactly one SUCCESS record for charge");
    }

    // ============ Workflow under test ============

    /**
     * charge (side effect + blackboard write) &rarr; approval (pause point)
     * &rarr; payout. The SAME static builder is used in BOTH processes; the
     * recovering process's charge lambda would append to the SAME
     * side-effects log if it were (wrongly) re-executed — the log is the
     * replay detector.
     */
    private static Workflow kill9Workflow(Path shared, ApprovalService approval) {
        Path sideEffects = shared.resolve("side-effects.log");
        return Workflow.builder("kill9-flow")
                .node(ActionNode.of("charge", ctx -> {
                    Files.writeString(sideEffects, "CHARGED:" + ctx.input() + "\n",
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    return "charged:" + ctx.input();
                }))
                .node(HumanApprovalNode.of("approval", "kill9 crash-recovery refund", approval))
                .node(ActionNode.of("payout", ctx -> "payout for: " + ctx.input()))
                .edge(Workflow.START, "charge")
                .edge("charge", "approval")
                .edge("approval", "payout")
                .edge("payout", Workflow.END)
                .build();
    }

    private long chargeCount() throws Exception {
        Path log = shared.resolve("side-effects.log");
        if (!Files.exists(log)) {
            return 0;
        }
        try (var reader = Files.newBufferedReader(log)) {
            return reader.lines().filter(l -> !l.isBlank()).count();
        }
    }

    private String childLog() {
        try {
            Path log = shared.resolve("child.log");
            return Files.exists(log) ? Files.readString(log) : "<no child log>";
        } catch (Exception e) {
            return "<unreadable: " + e + ">";
        }
    }

    // ============ Cross-process approval service ============

    /**
     * File-backed ApprovalService so the decision survives process death:
     * the child writes request-&lt;runId&gt;.txt, the test JVM writes
     * decision-&lt;runId&gt;.txt, and the recovered run reads it back.
     */
    static final class FileApprovalService implements ApprovalService {

        private final Path dir;

        FileApprovalService(Path dir) {
            this.dir = dir;
        }

        @Override
        public boolean approve(Request request) {
            throw new UnsupportedOperationException("sync mode not used in this test");
        }

        @Override
        public void requestApproval(String runId, String nodeId, String summary, Object payload) {
            try {
                Path tmp = dir.resolve("request-" + runId + ".tmp");
                Files.writeString(tmp, nodeId + "|" + summary + "|" + payload);
                Files.move(tmp, dir.resolve("request-" + runId + ".txt"),
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.io.IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public Boolean checkDecision(String runId, String nodeId) {
            Path decision = dir.resolve("decision-" + runId + ".txt");
            if (!Files.exists(decision)) {
                return null;
            }
            try {
                return "APPROVED".equals(Files.readString(decision).trim());
            } catch (java.io.IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    // ============ Child process entry point ============

    /**
     * The process that gets killed. Runs the workflow to the pause point,
     * persists the checkpoint via RunManager + FileCheckpointStore, writes
     * paused.marker (atomically) with the runId, then idles until SIGKILL.
     *
     * <p>Exit codes: 2 = workflow did not pause (bug), 3 = safety timeout
     * (we were never killed — the test's isAlive assertion catches this).
     */
    static class Child {

        public static void main(String[] args) throws Exception {
            Path shared = Path.of(args[0]);
            try {
                FileApprovalService approval = new FileApprovalService(shared);
                RunManager mgr = new RunManager(
                        new FileCheckpointStore(shared.resolve("checkpoints")));
                ExecutionResult r = mgr.start(kill9Workflow(shared, approval), INPUT);

                if (!r.isPaused()) {
                    System.err.println("child: expected PAUSED, got " + r.status()
                            + (r.errorMessage() != null ? " / " + r.errorMessage() : ""));
                    System.exit(2);
                }

                String runId = r.resumeToken().runId();
                Path tmp = shared.resolve("paused.marker.tmp");
                Files.writeString(tmp, runId);
                Files.move(tmp, shared.resolve("paused.marker"),
                        StandardCopyOption.ATOMIC_MOVE);

                // Idle at the pause point until killed. The 60s cap is a
                // safety valve so a forgotten child cannot hang a build.
                long deadline = System.currentTimeMillis() + 60_000;
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(200);
                }
                System.err.println("child: safety timeout hit, exiting without being killed");
                System.exit(3);
            } catch (Throwable t) {
                t.printStackTrace();
                System.exit(4);
            }
        }
    }
}
