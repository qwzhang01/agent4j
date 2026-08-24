package io.github.qwzhang01.agent.observability.cost;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChannelQuotaTest {

    @Test
    @DisplayName("pure number container: channelId + monthly budget round-trip")
    void pureNumberContainer() {
        ChannelQuota quota = new ChannelQuota("eng", 50_000L);
        assertEquals("eng", quota.channelId());
        assertEquals(50_000L, quota.monthlyTokenBudget());
    }

    @Test
    @DisplayName("blank channelId rejected")
    void blankChannelRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ChannelQuota(" ", 100L));
        assertThrows(IllegalArgumentException.class, () -> new ChannelQuota(null, 100L));
    }

    @Test
    @DisplayName("non-positive budget rejected: no cap means do not construct (ServiceAccount -1 convention)")
    void nonPositiveBudgetRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ChannelQuota("eng", 0L));
        assertThrows(IllegalArgumentException.class, () -> new ChannelQuota("eng", -1L));
    }
}
