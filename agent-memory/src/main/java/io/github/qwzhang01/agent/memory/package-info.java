/**
 * Long-term memory wiring surface.
 * <p>
 * Four pipelines, one Maven module:
 * <pre>
 *   write   extract/   Extractor → Policy → Store
 *   read    (root)     Store → Retriever → context/ Builder → prompt
 *   compact context/   Budget → Compressor → optional archive
 *   govern  (root)     Admin approve / edit / delete
 * </pre>
 * Root types are what a host wires: {@link io.github.qwzhang01.agent.memory.MemoryStore},
 * {@link io.github.qwzhang01.agent.memory.MemoryEntry}, {@link io.github.qwzhang01.agent.memory.MemoryQuery},
 * {@link io.github.qwzhang01.agent.memory.MemoryScope}, {@link io.github.qwzhang01.agent.memory.MemoryExtractor},
 * {@link io.github.qwzhang01.agent.memory.MemoryRetriever}, {@link io.github.qwzhang01.agent.memory.MemoryPolicy},
 * {@link io.github.qwzhang01.agent.memory.MemoryAdmin}.
 * <p>
 * Session history lives in {@code session/}. Model-facing save/search tools live in {@code tools/}.
 */
package io.github.qwzhang01.agent.memory;
