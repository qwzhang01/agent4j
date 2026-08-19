package io.github.qwzhang01.agent.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Administrator governance interface for memory entries (Stage 8 D6 - governance layer).
 * <p>
 * Provides the admin operations needed for channel-shared memory governance:
 * <ul>
 *   <li>List entries (including pending review) by scope</li>
 *   <li>Approve / reject pending entries</li>
 *   <li>Edit content (with provenance tracked)</li>
 *   <li>Delete entries (hard purge, e.g. GDPR)</li>
 *   <li>Supersede: write a correction that marks the old entry SUPERSEDED</li>
 *   <li>Add entries directly (admin-authored)</li>
 * </ul>
 */
public class MemoryAdmin {

    private static final Logger log = LoggerFactory.getLogger(MemoryAdmin.class);

    private final MemoryStore store;

    public MemoryAdmin(MemoryStore store) {
        this.store = store;
    }

    /**
     * List all entries in a scope (any status), for the admin governance view.
     */
    public List<MemoryEntry> listByScope(String scope) {
        return store.listByScope(scope);
    }

    /**
     * List entries awaiting review in a scope.
     */
    public List<MemoryEntry> listPending(String scope) {
        return store.listByScope(scope).stream()
                .filter(e -> e.status() == MemoryStatus.PENDING_REVIEW)
                .toList();
    }

    /**
     * Approve a pending entry -> becomes ACTIVE.
     */
    public MemoryEntry approve(String entryId) {
        MemoryEntry entry = requireEntry(entryId);
        if (entry.status() != MemoryStatus.PENDING_REVIEW) {
            throw new IllegalStateException("Entry " + entryId + " is not pending (status=" + entry.status() + ")");
        }
        // If there's an existing ACTIVE entry with the same subject, supersede it first
        store.findActiveBySubject(entry.scope(), entry.subject())
                .ifPresent(old -> store.update(old.withStatus(MemoryStatus.SUPERSEDED)));
        MemoryEntry approved = entry.withStatus(MemoryStatus.ACTIVE);
        store.update(approved);
        log.info("Approved entry {} in scope {}", entryId, entry.scope());
        return approved;
    }

    /**
     * Reject a pending entry -> becomes REJECTED (not retrievable, kept for audit).
     */
    public MemoryEntry reject(String entryId) {
        MemoryEntry entry = requireEntry(entryId);
        MemoryEntry rejected = entry.withStatus(MemoryStatus.REJECTED);
        store.update(rejected);
        log.info("Rejected entry {} in scope {}", entryId, entry.scope());
        return rejected;
    }

    /**
     * Edit the content of an entry (provenance tracked as ADMIN_EDIT).
     */
    public MemoryEntry updateContent(String entryId, String newContent, String adminId) {
        MemoryEntry entry = requireEntry(entryId);
        MemoryEntry updated = new MemoryEntry(
                entry.id(), entry.scope(), entry.type(), entry.subject(), newContent,
                entry.importance(),
                MemoryProvenance.adminEdit(adminId, Instant.now()),
                entry.status(), entry.createdAt(), entry.expireAt()
        );
        store.update(updated);
        log.info("Admin {} edited entry {}", adminId, entryId);
        return updated;
    }

    /**
     * Supersede an entry: mark the old one SUPERSEDED and write a corrected ACTIVE entry.
     * Used when an admin corrects a wrong memory.
     */
    public MemoryEntry supersede(String entryId, String newContent, String adminId) {
        MemoryEntry old = requireEntry(entryId);
        store.update(old.withStatus(MemoryStatus.SUPERSEDED));

        MemoryEntry corrected = new MemoryEntry(
                null, old.scope(), old.type(), old.subject(), newContent,
                old.importance(),
                MemoryProvenance.adminEdit(adminId, Instant.now()),
                MemoryStatus.ACTIVE, Instant.now(), null
        );
        MemoryEntry stored = store.write(corrected);
        log.info("Admin {} superseded entry {} with new entry {}", adminId, entryId, stored.id());
        return stored;
    }

    /**
     * Add a new entry directly (admin-authored, starts ACTIVE).
     */
    public MemoryEntry addEntry(String scope, MemoryType type, String subject,
                                String content, String adminId) {
        MemoryEntry entry = new MemoryEntry(
                null, scope, type, subject, content, 1.0,
                MemoryProvenance.adminEdit(adminId, Instant.now()),
                MemoryStatus.ACTIVE, Instant.now(), null
        );
        MemoryEntry stored = store.write(entry);
        log.info("Admin {} added entry {} in scope {}", adminId, stored.id(), scope);
        return stored;
    }

    /**
     * Set a TTL on an entry (expire at the given instant).
     */
    public MemoryEntry setTtl(String entryId, Instant expireAt) {
        MemoryEntry entry = requireEntry(entryId);
        MemoryEntry withTtl = new MemoryEntry(
                entry.id(), entry.scope(), entry.type(), entry.subject(), entry.content(),
                entry.importance(), entry.provenance(), entry.status(),
                entry.createdAt(), expireAt
        );
        store.update(withTtl);
        return withTtl;
    }

    /**
     * Hard delete an entry (GDPR purge). Prefer supersede for governance.
     */
    public boolean delete(String entryId) {
        boolean deleted = store.delete(entryId);
        if (deleted) {
            log.info("Hard-deleted entry {}", entryId);
        }
        return deleted;
    }

    /**
     * Find an entry by id.
     */
    public Optional<MemoryEntry> findById(String entryId) {
        return store.findById(entryId);
    }

    private MemoryEntry requireEntry(String entryId) {
        return store.findById(entryId)
                .orElseThrow(() -> new IllegalArgumentException("Entry not found: " + entryId));
    }
}
