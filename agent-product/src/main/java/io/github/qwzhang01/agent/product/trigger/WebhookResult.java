package io.github.qwzhang01.agent.product.trigger;

/**
 * Outcome of handling a webhook (Stage 13 M13.5, D8).
 * <p>
 * Transport-agnostic: the HTTP layer maps statuses to codes (202/401/404/409),
 * tests assert on this record instead.
 *
 * @param status  outcome category
 * @param message human-readable detail (also what audits log)
 */
public record WebhookResult(Status status, String message) {

    /** 202 Accepted - the run is executing asynchronously. */
    public static WebhookResult accepted(String message) {
        return new WebhookResult(Status.ACCEPTED, message);
    }

    public enum Status {
        /** Verified, fresh, dispatched asynchronously (HTTP 202). */
        ACCEPTED,
        /** Signature mismatch - rejected before any agent ran (HTTP 401). */
        UNAUTHORIZED,
        /** eventId already handled - replay safe (HTTP 200). */
        DUPLICATE,
        /** No route registered for the source (HTTP 404). */
        UNKNOWN_SOURCE,
        /** Payload carries no eventId - idempotency impossible (HTTP 400). */
        NO_EVENT_ID,
        /** Body is not the JSON the route expects (HTTP 400). */
        BAD_PAYLOAD,
        /** Route points at an agent that is not running (HTTP 503). */
        AGENT_NOT_FOUND
    }
}
