package io.github.qwzhang01.agent.observability.routing;

/**
 * One routing verdict (Stage 18 D6): which model serves this call, and why.
 * <p>
 * {@code reason} is REQUIRED at the type level (blank is rejected) because a
 * decision without a reason is an unauditable decision:
 * <ul>
 *   <li>cost reconciliation: "30% of last month's traffic went cheap" must
 *       have per-call answers - {@code reason="remaining 18% < 25% threshold"}
 *       makes every line item explainable</li>
 *   <li>post-hoc attribution: a batch of bad answers traced back to a routing
 *       switch is reproducible history; without reasons it is occult</li>
 *   <li>the Stage 12 IdentityDecision / Stage 9 AuditEvent tradition:
 *       decisions leave a trail - denied and routed are both intelligence</li>
 * </ul>
 *
 * @param modelId key of the chosen candidate in the RoutingModelClient map
 *                (NOT necessarily the provider model string - it addresses
 *                the assembled candidate, e.g. "premium" / "cheap")
 * @param reason  human-readable, non-blank explanation; SHOULD contain the
 *                numbers that drove the decision (remaining percent,
 *                threshold) for audit
 */
public record RouteDecision(String modelId, String reason) {

    public RouteDecision {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("modelId must not be null or blank");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "reason must not be null or blank (routing without explanation is unauditable)");
        }
    }
}
