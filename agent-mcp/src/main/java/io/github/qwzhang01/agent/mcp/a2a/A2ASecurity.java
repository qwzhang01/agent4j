package io.github.qwzhang01.agent.mcp.a2a;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

/**
 *  HTTP authentication + push-notification integrity for A2A.
 * <p>
 * Roadmap: "HTTP 请求认证、授权、签名验证" + "Push Notification 签名和重放保护".
 * The framework-level split: this class is the PRIMITIVE layer (constant-time
 * compare, HMAC, timestamp window, nonce replay cache); authorization POLICY
 * (who may call which agent) stays with the host/governance layers, same
 * discipline as every other boundary in this codebase.
 * <p>
 * What ships:
 * <ul>
 *   <li><b>Bearer check</b> — the wire-side auth gate for
 *       {@code HttpA2AServer}: a host-issued token, compared constant-time
 *       (never String.equals on secrets).</li>
 *   <li><b>Push HMAC-SHA256</b> — sign(taskId + timestamp + nonce + body)
 *       with a shared secret; receivers verify with
 *       {@link #verifyPushSignature}. The signature binds the payload, so a
 *       network observer cannot forge notifications.</li>
 *   <li><b>Replay window</b> — a fresh nonce + timestamp inside a window;
 *       the nonce cache rejects replays. An attacker who copies a signed
 *       notification cannot replay it within the window (and after the
 *       window the timestamp check rejects it).</li>
 * </ul>
 */
public final class A2ASecurity {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long DEFAULT_WINDOW_MS = 5 * 60 * 1000;
    private static final int MAX_CACHE = 10_000;

    private A2ASecurity() {
    }

    // Bearer auth (wire gate)

    /**
     * Constant-time bearer check. Timing leaks in token compares are a real
     * exploit class; MessageDigest.isEqual is the JDK's constant-time path.
     */
    public static boolean bearerMatches(String presented, String expected) {
        if (presented == null || expected == null) {
            return false;
        }
        String normalized = presented.startsWith("Bearer ") ? presented.substring(7) : presented;
        return MessageDigest.isEqual(
                normalized.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }

    /** A signed push envelope: body + the headers a receiver verifies. */
    public record PushEnvelope(String signature, String timestamp, String nonce, String body) {
    }

    /** Sign a push body with the shared secret. */
    public static PushEnvelope signPush(String taskId, String body, String secret) {
        String timestamp = String.valueOf(Instant.now().toEpochMilli());
        String nonce = newNonce();
        String signature = hmac(taskId, timestamp, nonce, body, secret);
        return new PushEnvelope(signature, timestamp, nonce, body);
    }

    /**
     * Verify a push envelope: correct HMAC, fresh timestamp, unused nonce.
     * All three must hold; failure reason is in the return (not thrown —
     * the receiver logs/audits it).
     */
    public static Verification verifyPushSignature(String taskId, PushEnvelope envelope,
                                                   String secret, ReplayCache replayCache) {
        if (envelope == null) {
            return new Verification(false, "null envelope");
        }
        String expected = hmac(taskId, envelope.timestamp(), envelope.nonce(),
                envelope.body(), secret);
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                envelope.signature().getBytes(StandardCharsets.UTF_8))) {
            return new Verification(false, "signature mismatch");
        }
        long ts;
        try {
            ts = Long.parseLong(envelope.timestamp());
        } catch (NumberFormatException e) {
            return new Verification(false, "bad timestamp");
        }
        long age = Math.abs(Instant.now().toEpochMilli() - ts);
        if (age > DEFAULT_WINDOW_MS) {
            return new Verification(false, "timestamp outside window (" + age + "ms old)");
        }
        if (replayCache != null && !replayCache.tryConsume(envelope.nonce())) {
            return new Verification(false, "nonce replayed");
        }
        return new Verification(true, "ok");
    }

    /** Verification outcome with a machine-readable reason. */
    public record Verification(boolean valid, String reason) {
    }

    /** Bounded in-memory nonce cache; a real deployment shares it (e.g. Redis). */
    public static final class ReplayCache {
        private final java.util.concurrent.ConcurrentHashMap<String, Long> seen =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final long windowMs;

        public ReplayCache() {
            this(DEFAULT_WINDOW_MS);
        }

        public ReplayCache(long windowMs) {
            this.windowMs = windowMs;
        }

        /** True if the nonce is fresh (and now consumed); false if replayed. */
        public boolean tryConsume(String nonce) {
            long now = Instant.now().toEpochMilli();
            evict(now);
            Long previous = seen.putIfAbsent(nonce, now);
            if (previous != null && now - previous <= windowMs) {
                return false; // seen within the window: replay
            }
            return true;
        }

        private void evict(long now) {
            if (seen.size() < MAX_CACHE) {
                return;
            }
            seen.values().removeIf(seenAt -> now - seenAt > windowMs);
        }
    }

    private static String hmac(String taskId, String timestamp, String nonce,
                               String body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String payload = taskId + "\n" + timestamp + "\n" + nonce + "\n" + body;
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    private static String newNonce() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
