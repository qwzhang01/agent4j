package io.github.qwzhang01.agent.core.agent;

import io.github.qwzhang01.agent.core.tool.DefaultToolExecutor;
import io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandoffTargetResolverTest {

    private final HandoffTargetResolver resolver = HandoffTargetResolver.graph();

    @Test
    void blankNameReturnsEntry() {
        AgentConfig a = new AgentConfig("A", "a", null, null);
        assertSame(a, resolver.resolve(a, null));
        assertSame(a, resolver.resolve(a, "  "));
        assertSame(a, resolver.resolve(a, "A"));
    }

    @Test
    void walksChain() {
        AgentConfig c = new AgentConfig("C", "c", null, null);
        AgentConfig b = new AgentConfig("B", "b", null, null, 10, null, List.of(HandoffSpec.to(c)));
        AgentConfig a = new AgentConfig("A", "a", null, null, 10, null, List.of(HandoffSpec.to(b)));

        assertSame(c, resolver.resolve(a, "C"));
        assertSame(b, resolver.resolve(a, "B"));
    }

    @Test
    void unknownNameFailsClosed() {
        AgentConfig a = new AgentConfig("A", "a", null, null);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> resolver.resolve(a, "ghost"));
        assertTrue(ex.getMessage().contains("ghost"));
    }

    @Test
    void executorPrefersConfigOwnThenPlain() {
        var registry = new InMemoryToolRegistry();
        DefaultToolExecutor own = new DefaultToolExecutor(registry);
        AgentConfig withOwn = new AgentConfig("B", "b", null, registry, 10, null,
                List.of(), null, own);
        assertSame(own, resolver.executorFor(withOwn));

        AgentConfig plain = new AgentConfig("C", "c", null, registry);
        assertTrue(resolver.executorFor(plain) instanceof DefaultToolExecutor);
    }
}
