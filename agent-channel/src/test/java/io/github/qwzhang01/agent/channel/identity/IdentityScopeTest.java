package io.github.qwzhang01.agent.channel.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link IdentityScope} (Stage 12 M12.1).
 */
class IdentityScopeTest {

    // ============ Construction ============

    @Test
    @DisplayName("of stores defensive immutable copies - mutating the source set has no effect")
    void construction_defensiveCopy() {
        Set<String> source = new HashSet<>(Set.of("git.read"));
        IdentityScope scope = new IdentityScope(source, Set.of(), Set.of());

        source.add("crm.read");

        assertFalse(scope.allows("crm.read"), "source mutation must not leak into the scope");
        assertEquals(Set.of("git.read"), scope.capabilities());
        assertThrows(UnsupportedOperationException.class,
                () -> scope.capabilities().add("ci.trigger"));
    }

    @Test
    @DisplayName("null sets are rejected")
    void construction_nullRejected() {
        assertThrows(NullPointerException.class,
                () -> new IdentityScope(null, Set.of(), Set.of()));
        assertThrows(NullPointerException.class,
                () -> new IdentityScope(Set.of(), null, Set.of()));
    }

    // ============ Factories ============

    @Test
    @DisplayName("capabilities(...) factory grants capabilities only")
    void factory_capabilities() {
        IdentityScope scope = IdentityScope.capabilities("git.read", "ci.trigger");
        assertTrue(scope.allows("git.read"));
        assertTrue(scope.allows("ci.trigger"));
        assertFalse(scope.allows("crm.read"));
        assertTrue(scope.memoryScopes().isEmpty());
        assertTrue(scope.dataClassifications().isEmpty());
    }

    @Test
    @DisplayName("empty() grants nothing and isEmpty() reports true")
    void factory_empty() {
        IdentityScope scope = IdentityScope.empty();
        assertTrue(scope.isEmpty());
        assertFalse(scope.allows("git.read"));
        assertFalse(scope.canReadMemoryScope("channel:team-eng"));
    }

    // ============ Predicates ============

    @Test
    @DisplayName("canReadMemoryScope matches granted namespace strings exactly")
    void predicates_memoryScope() {
        IdentityScope scope = new IdentityScope(
                Set.of(), Set.of("channel:team-eng", "agent:eng-bot"), Set.of());
        assertTrue(scope.canReadMemoryScope("channel:team-eng"));
        assertTrue(scope.canReadMemoryScope("agent:eng-bot"));
        assertFalse(scope.canReadMemoryScope("channel:sales"));   // another channel: isolated
        assertFalse(scope.canReadMemoryScope(null));
        assertFalse(scope.canReadMemoryScope("channel:team-e"));  // no prefix semantics
    }

    // ============ Intersect ============

    @Test
    @DisplayName("intersect intersects all three sets element-wise")
    void intersect_allThreeDimensions() {
        IdentityScope granted = new IdentityScope(
                Set.of("git.read", "ci.trigger"),
                Set.of("channel:team-eng", "agent:eng-bot"),
                Set.of("public", "internal"));
        IdentityScope role = new IdentityScope(
                Set.of("git.read", "calendar.read"),
                Set.of("channel:team-eng"),
                Set.of("internal", "restricted"));

        IdentityScope effective = granted.intersect(role);

        assertEquals(Set.of("git.read"), effective.capabilities(),
                "only git.read survives: agent lacks calendar.read, user lacks ci.trigger");
        assertEquals(Set.of("channel:team-eng"), effective.memoryScopes());
        assertEquals(Set.of("internal"), effective.dataClassifications());
    }

    @Test
    @DisplayName("intersect with a disjoint scope is empty (fail-closed groundwork)")
    void intersect_disjoint_empty() {
        IdentityScope a = IdentityScope.capabilities("crm.read");
        IdentityScope b = IdentityScope.capabilities("git.read");
        assertTrue(a.intersect(b).isEmpty());
    }

    @Test
    @DisplayName("intersect is null-hostile")
    void intersect_nullRejected() {
        assertThrows(NullPointerException.class,
                () -> IdentityScope.empty().intersect(null));
    }
}
