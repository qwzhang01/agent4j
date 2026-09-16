package io.github.qwzhang01.agent.spring;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Stage 8.2 graceful shutdown: gate closed → drain bounded → stragglers
 * cancelled → report.
 */
class GracefulShutdownCoordinatorTest {

    @Test
    void gateRejectsNewRunsAfterBeginShutdown() {
        GracefulShutdownCoordinator coordinator = new GracefulShutdownCoordinator();
        coordinator.checkStartAllowed(); // no throw before
        coordinator.beginShutdown();
        assertThatThrownBy(coordinator::checkStartAllowed)
                .isInstanceOf(GracefulShutdownCoordinator.ShutdownInProgressException.class)
                .hasMessageContaining("refusing new run start");
    }

    @Test
    void drainWaitsForRunsThatFinishInTime() throws Exception {
        GracefulShutdownCoordinator coordinator = new GracefulShutdownCoordinator();
        CountDownLatch finished = new CountDownLatch(1);
        coordinator.track(new StubHandle("run-1", finished, false));
        coordinator.beginShutdown();
        finished.countDown(); // the run completes right as drain begins
        GracefulShutdownCoordinator.ShutdownReport report = coordinator.drain(1_000);
        assertThat(report.total()).isEqualTo(1);
        assertThat(report.runIds()).containsExactly("run-1");
        assertThat(report.toString()).contains("run-1");
    }

    @Test
    void stragglerIsCancelledWhenTimeoutExpires() throws Exception {
        GracefulShutdownCoordinator coordinator = new GracefulShutdownCoordinator();
        // A run that never finishes: awaitDrain always times out.
        StubHandle stuck = new StubHandle("run-stuck", null, false);
        coordinator.track(stuck);
        coordinator.beginShutdown();
        GracefulShutdownCoordinator.ShutdownReport report = coordinator.drain(100);
        assertThat(report.total()).isEqualTo(1);
        assertThat(stuck.cancelled().get()).isTrue();
    }

    @Test
    void shutdownReportIsAToStringRecord() throws Exception {
        GracefulShutdownCoordinator coordinator = new GracefulShutdownCoordinator();
        coordinator.track(new StubHandle("run-a", new CountDownLatch(1), false));
        coordinator.beginShutdown();
        GracefulShutdownCoordinator.ShutdownReport report = coordinator.drain(50);
        assertThat(report.runIds()).containsExactly("run-a");
        assertThat(report.toString()).contains("ShutdownReport");
    }

    /** Simple handle stub: finishes when the latch fires, else times out. */
    private static final class StubHandle
            implements GracefulShutdownCoordinator.RunHandle {

        private final String runId;
        private final CountDownLatch finished;
        private final boolean immediatelyHealthy;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        StubHandle(String runId, CountDownLatch finished, boolean immediatelyHealthy) {
            this.runId = runId;
            this.finished = finished;
            this.immediatelyHealthy = immediatelyHealthy;
        }

        @Override
        public String runId() {
            return runId;
        }

        @Override
        public boolean awaitDrain(long timeoutMs) throws InterruptedException {
            if (immediatelyHealthy) {
                return true;
            }
            if (finished == null) {
                Thread.sleep(timeoutMs);
                return false;
            }
            return finished.await(timeoutMs, TimeUnit.MILLISECONDS);
        }

        @Override
        public void cancel() {
            cancelled.set(true);
        }

        AtomicBoolean cancelled() {
            return cancelled;
        }
    }
}
