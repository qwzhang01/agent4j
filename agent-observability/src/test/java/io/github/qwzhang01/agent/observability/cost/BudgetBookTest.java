package io.github.qwzhang01.agent.observability.cost;

import io.github.qwzhang01.agent.observability.metrics.ModelCallMetrics;
import io.github.qwzhang01.agent.observability.metrics.MetricsSink;
import io.github.qwzhang01.agent.observability.metrics.RunMetrics;
import io.github.qwzhang01.agent.observability.metrics.ToolCallMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BudgetBookTest {

    /** Alarm collector - proves the default onAlarm evolution works (only 3 abstract methods). */
    static final class AlarmCollector implements MetricsSink {
        final List<BudgetAlarmEvent> alarms = new ArrayList<>();

        @Override
        public void onModelCall(ModelCallMetrics metrics) {
        }

        @Override
        public void onToolCall(ToolCallMetrics metrics) {
        }

        @Override
        public void onRun(RunMetrics metrics) {
        }

        @Override
        public void onAlarm(BudgetAlarmEvent alarm) {
            alarms.add(alarm);
        }
    }

    // ============ unconfigured ============

    @Test
    @DisplayName("unconfigured (dimension, key): Ok + limitOf -1 + remainingOf MAX_VALUE (unlimited by absence)")
    void unconfiguredIsUnlimited() {
        BudgetBook book = BudgetBook.builder().build();
        assertInstanceOf(BudgetCheck.Ok.class, book.requireBudget(BudgetDimension.USER, "alice", 1_000_000));
        assertEquals(-1L, book.limitOf(BudgetDimension.USER, "alice"));
        assertEquals(Long.MAX_VALUE, book.remainingOf(BudgetDimension.USER, "alice"));
    }

    @Test
    @DisplayName("unconfigured keys still count usage (spend visible before a cap is decided)")
    void unconfiguredStillCounts() {
        BudgetBook book = BudgetBook.builder().build();
        book.recordUsage(BudgetDimension.CHANNEL, "eng", 500);
        assertEquals(500L, book.usedOf(BudgetDimension.CHANNEL, "eng"));
    }

    // ============ the three-state gate ============

    @Test
    @DisplayName("healthy budget: Ok, no alarm")
    void healthyOk() {
        AlarmCollector sink = new AlarmCollector();
        BudgetBook book = BudgetBook.builder()
                .budget(BudgetDimension.USER, "alice", 10_000L)
                .alarmSink(sink)
                .build();

        assertInstanceOf(BudgetCheck.Ok.class, book.requireBudget(BudgetDimension.USER, "alice", 1_000L));
        assertTrue(sink.alarms.isEmpty());
    }

    @Test
    @DisplayName("WARN: used at 83% -> Warn(percent=83) + alarm event; the call itself proceeds by type")
    void warnEmittedNotBlocking() {
        AlarmCollector sink = new AlarmCollector();
        BudgetBook book = BudgetBook.builder()
                .budget(BudgetDimension.USER, "alice", 10_000L)
                .alarmSink(sink)
                .build();

        book.recordUsage(BudgetDimension.USER, "alice", 8_300L);
        BudgetCheck check = book.requireBudget(BudgetDimension.USER, "alice", 1_000L);

        BudgetCheck.Warn warn = assertInstanceOf(BudgetCheck.Warn.class, check);
        assertEquals(83, warn.percentUsed());
        assertEquals(8_300L, warn.usedTokens());
        assertEquals(10_000L, warn.limitTokens());
        assertEquals(1, sink.alarms.size());
        assertEquals(83, sink.alarms.get(0).percentUsed());
        assertEquals(BudgetDimension.USER, sink.alarms.get(0).dimension());
        assertEquals("alice", sink.alarms.get(0).key());
    }

    @Test
    @DisplayName("DENIED: projected usage (used + estimate) exceeds limit -> fail-closed")
    void deniedOnProjection() {
        BudgetBook book = BudgetBook.builder()
                .budget(BudgetDimension.USER, "alice", 10_000L)
                .build();

        book.recordUsage(BudgetDimension.USER, "alice", 9_500L);
        BudgetCheck check = book.requireBudget(BudgetDimension.USER, "alice", 600L);

        BudgetCheck.Denied denied = assertInstanceOf(BudgetCheck.Denied.class, check);
        assertEquals(9_500L, denied.usedTokens());
        assertEquals(10_000L, denied.limitTokens());
    }

    @Test
    @DisplayName("exact exhaustion (used + est == limit) is allowed: denial is for overdraft, not landing on the line")
    void exactExhaustionAllowed() {
        BudgetBook book = BudgetBook.builder()
                .budget(BudgetDimension.USER, "alice", 1_000L)
                .warnAtPercent(99)
                .build();

        book.recordUsage(BudgetDimension.USER, "alice", 900L);
        BudgetCheck check = book.requireBudget(BudgetDimension.USER, "alice", 100L);
        assertInstanceOf(BudgetCheck.Ok.class, check,
                "900 + 100 == 1000 lands exactly on the line: allowed, and 90% < 99% warn line means plain Ok");
    }

    @Test
    @DisplayName("recordUsage replaces the estimate honestly: remaining drops, next gate uses real usage")
    void honestLedger() {
        BudgetBook book = BudgetBook.builder()
                .budget(BudgetDimension.RUN, "run-1", 1_000L)
                .build();

        book.recordUsage(BudgetDimension.RUN, "run-1", 400L);
        assertEquals(600L, book.remainingOf(BudgetDimension.RUN, "run-1"));

        book.recordUsage(BudgetDimension.RUN, "run-1", 250L);
        assertEquals(350L, book.remainingOf(BudgetDimension.RUN, "run-1"));
        assertEquals(650L, book.usedOf(BudgetDimension.RUN, "run-1"));
    }

    // ============ five dimensions, five gates ============

    @Test
    @DisplayName("all five dimensions gate independently (RUN/USER/TENANT/CHANNEL/AGENT)")
    void fiveIndependentGates() {
        for (BudgetDimension dim : BudgetDimension.values()) {
            BudgetBook book = BudgetBook.builder().budget(dim, "k", 100L).build();
            book.recordUsage(dim, "k", 100L);
            BudgetCheck check = book.requireBudget(dim, "k", 1L);
            assertInstanceOf(BudgetCheck.Denied.class, check, dim + " must gate");
        }
    }

    @Test
    @DisplayName("same dimension, different keys: budgets and usage are independent")
    void keysAreIndependent() {
        BudgetBook book = BudgetBook.builder()
                .budget(BudgetDimension.USER, "alice", 100L)
                .budget(BudgetDimension.USER, "bob", 100L)
                .build();

        book.recordUsage(BudgetDimension.USER, "alice", 100L);
        assertInstanceOf(BudgetCheck.Denied.class, book.requireBudget(BudgetDimension.USER, "alice", 1L));
        assertInstanceOf(BudgetCheck.Ok.class, book.requireBudget(BudgetDimension.USER, "bob", 50L));
    }

    // ============ warning behaviour ============

    @Test
    @DisplayName("custom warnAtPercent=50: alarm fires earlier")
    void customWarnThreshold() {
        AlarmCollector sink = new AlarmCollector();
        BudgetBook book = BudgetBook.builder()
                .budget(BudgetDimension.TENANT, "acme", 1_000L)
                .warnAtPercent(50)
                .alarmSink(sink)
                .build();

        book.recordUsage(BudgetDimension.TENANT, "acme", 500L);
        BudgetCheck check = book.requireBudget(BudgetDimension.TENANT, "acme", 100L);
        BudgetCheck.Warn warn = assertInstanceOf(BudgetCheck.Warn.class, check);
        assertEquals(50, warn.percentUsed());
        assertEquals(1, sink.alarms.size());
    }

    @Test
    @DisplayName("every crossing re-fires the alarm (v1 has no rate limiting - the sink's concern, not the ledger's)")
    void alarmRefires() {
        AlarmCollector sink = new AlarmCollector();
        BudgetBook book = BudgetBook.builder()
                .budget(BudgetDimension.USER, "alice", 10_000L)
                .alarmSink(sink)
                .build();

        book.recordUsage(BudgetDimension.USER, "alice", 8_000L);
        book.requireBudget(BudgetDimension.USER, "alice", 100L);
        book.requireBudget(BudgetDimension.USER, "alice", 100L);

        assertEquals(2, sink.alarms.size(), "both crossings warn, no dedup in the ledger");
    }

    @Test
    @DisplayName("no alarm sink wired: warnings still returned, nothing explodes")
    void noSinkStillWarns() {
        BudgetBook book = BudgetBook.builder()
                .budget(BudgetDimension.USER, "alice", 100L)
                .build();

        book.recordUsage(BudgetDimension.USER, "alice", 85L);
        assertInstanceOf(BudgetCheck.Warn.class, book.requireBudget(BudgetDimension.USER, "alice", 5L));
    }

    @Test
    @DisplayName("alarm sink throwing must not break the gate (alarms are a side channel)")
    void sinkFailureSwallowed() {
        MetricsSink exploding = new MetricsSink() {
            @Override
            public void onModelCall(ModelCallMetrics metrics) {
            }

            @Override
            public void onToolCall(ToolCallMetrics metrics) {
            }

            @Override
            public void onRun(RunMetrics metrics) {
            }

            @Override
            public void onAlarm(BudgetAlarmEvent alarm) {
                throw new IllegalStateException("sink broken");
            }
        };
        BudgetBook book = BudgetBook.builder()
                .budget(BudgetDimension.USER, "alice", 100L)
                .alarmSink(exploding)
                .build();

        book.recordUsage(BudgetDimension.USER, "alice", 90L);
        assertInstanceOf(BudgetCheck.Warn.class,
                assertDoesNotThrow(() -> book.requireBudget(BudgetDimension.USER, "alice", 5L)));
    }

    // ============ guards ============

    @Test
    @DisplayName("builder guards: non-positive limits and out-of-range warn percent rejected at assembly")
    void builderGuards() {
        BudgetBook.Builder b = BudgetBook.builder();
        assertThrows(IllegalArgumentException.class, () -> b.budget(BudgetDimension.USER, "k", 0L));
        assertThrows(IllegalArgumentException.class, () -> b.budget(BudgetDimension.USER, "k", -5L));
        assertThrows(IllegalArgumentException.class, () -> b.budget(BudgetDimension.USER, " ", 100L));
        assertThrows(IllegalArgumentException.class, () -> b.warnAtPercent(0));
        assertThrows(IllegalArgumentException.class, () -> b.warnAtPercent(100));
    }

    @Test
    @DisplayName("gate guards: null/blank key and negative estimates rejected fail-fast")
    void gateGuards() {
        BudgetBook book = BudgetBook.builder().build();
        assertThrows(NullPointerException.class, () -> book.requireBudget(null, "k", 1L));
        assertThrows(IllegalArgumentException.class, () -> book.requireBudget(BudgetDimension.USER, " ", 1L));
        assertThrows(IllegalArgumentException.class, () -> book.requireBudget(BudgetDimension.USER, "k", -1L));
        assertThrows(IllegalArgumentException.class, () -> book.recordUsage(BudgetDimension.USER, "k", -1L));
    }
}
