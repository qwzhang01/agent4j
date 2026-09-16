package io.github.qwzhang01.agent.mcp.a2a;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Stage 6.3: pluggable persistence for A2A tasks.
 * <p>
 * Roadmap: "Task Store 从内存升级为可持久化接口" + "Task Lease、续跑、取消、
 * 过期、去重" + "跨实例恢复 A2A Task". The {@code HttpA2AServer} used to hold
 * tasks in a hard-coded {@code ConcurrentHashMap} — restart = amnesia. This
 * interface splits storage from serving: the in-memory default stays (tests,
 * single-node dev), but a host can plug Redis/Postgres/etc. behind the same
 * surface and survive restarts.
 * <p>
 * Contract rules (the server relies on all of them):
 * <ul>
 *   <li>All methods must be thread-safe (the server's handler pool hits them
 *       concurrently).</li>
 *   <li>{@link #save} is an upsert keyed by taskId.</li>
 *   <li>{@link #find} returns the LATEST stored state, never a partial write.</li>
 *   <li>{@link #acquireLease}/{@link #renewLease} implement cross-instance
 *       work claiming: at most ONE holder at a time, lease expires by wall
 *       clock so a dead instance's claim self-releases.</li>
 *   <li>{@link #expireOlderThan} enables retention sweeps (a completed task
 *       has no business living forever).</li>
 * </ul>
 */
public interface A2ATaskStore {

    /**
     * A stored task snapshot: the wire-visible fields plus server-internal
     * state (agent memory + push url) needed to resume or serve tasks/get.
     */
    record StoredA2ATask(
            String taskId,
            String contextId,
            A2ATaskStatus status,
            String statusMessage,
            List<A2AArtifact> artifacts,
            String serializedState,
            String pushUrl,
            String pendingText,
            Instant createdAt,
            Instant updatedAt) {

        public StoredA2ATask {
            Objects.requireNonNull(taskId, "taskId");
            Objects.requireNonNull(status, "status");
            artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
            Instant now = Instant.now();
            createdAt = createdAt != null ? createdAt : now;
            updatedAt = updatedAt != null ? updatedAt : now;
        }

        public StoredA2ATask withStatus(A2ATaskStatus status, String message,
                                        List<A2AArtifact> artifacts) {
            return new StoredA2ATask(taskId, contextId, status, message,
                    artifacts, serializedState, pushUrl, pendingText, createdAt, Instant.now());
        }

        /** Attach / replace a push webhook url (tasks/pushNotification/set). */
        public StoredA2ATask withPushUrl(String url) {
            return new StoredA2ATask(taskId, contextId, status, statusMessage,
                    artifacts, serializedState, url, pendingText, createdAt, Instant.now());
        }

        /** Carry the accepted wire text into the run phase without persisting it twice. */
        public StoredA2ATask withPendingText(String text) {
            return new StoredA2ATask(taskId, contextId, status, statusMessage,
                    artifacts, serializedState, pushUrl, text, createdAt, Instant.now());
        }
    }

    /** Upsert a task snapshot. */
    void save(StoredA2ATask task);

    /** Latest snapshot for a taskId, or empty. */
    Optional<StoredA2ATask> find(String taskId);

    /** All tasks with a contextId, newest first (resume-a-conversation view). */
    List<StoredA2ATask> findByContext(String contextId);

    /**
     * Atomically claim a task for exclusive work (cross-instance recovery:
     * the winner runs the task, losers see it as claimed). A lease held by
     * a DEAD instance self-releases after its TTL — the sweep's job.
     *
     * @param holderId identifies the claiming instance (e.g. "host-1")
     * @param ttl      how long the claim is valid
     * @return true if this caller now holds the lease
     */
    boolean acquireLease(String taskId, String holderId, java.time.Duration ttl);

    /** Extend a lease this holder already owns. False if lost/expired. */
    boolean renewLease(String taskId, String holderId, java.time.Duration ttl);

    /** Release a lease (task finished / canceled). No-op if not held. */
    void releaseLease(String taskId, String holderId);

    /** Remove tasks older than the cutoff (retention sweep). */
    int expireOlderThan(java.time.Instant cutoff);

    /**
     * Deduplicate a contextId that must hold at most one live task: if a
     * non-terminal task exists for this context, return it instead of
     * creating a new one.
     *
     * @return the existing live task, or empty if none (caller may create)
     */
    Optional<StoredA2ATask> findLiveByContext(String contextId);
}
