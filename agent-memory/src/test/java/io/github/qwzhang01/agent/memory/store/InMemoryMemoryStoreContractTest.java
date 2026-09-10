package io.github.qwzhang01.agent.memory.store;

import io.github.qwzhang01.agent.memory.MemoryStore;

/**
 * The reference implementation runs the port contract on every build —
 * the behaviour promised in {@link MemoryStore}'s javadoc stays
 * machine-checked even when no database is present.
 */
class InMemoryMemoryStoreContractTest extends MemoryStoreContractTest {

    @Override
    protected MemoryStore newStore() {
        return new InMemoryMemoryStore();
    }
}
