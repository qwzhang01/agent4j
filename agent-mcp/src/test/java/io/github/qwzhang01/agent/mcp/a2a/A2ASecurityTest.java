package io.github.qwzhang01.agent.mcp.a2a;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 6.3: pins the {@link A2ASecurity} primitives — constant-time bearer,
 * push HMAC round-trip, tamper / window / replay rejection. These are the
 * wire-integrity guarantees the signed-push path of HttpA2AServer depends on.
 */
class A2ASecurityTest {

    @Test
    void bearer_matchesWithAndWithoutPrefix_rejectsWrongOrNull() {
        assertTrue(A2ASecurity.bearerMatches("Bearer letmein", "letmein"));
        assertTrue(A2ASecurity.bearerMatches("letmein", "letmein"));
        assertFalse(A2ASecurity.bearerMatches("Bearer wrong", "letmein"));
        assertFalse(A2ASecurity.bearerMatches(null, "letmein"));
        assertFalse(A2ASecurity.bearerMatches("letmein", null));
    }

    @Test
    void pushSignature_roundTrip() {
        String body = "{\"status\":{\"state\":\"completed\"}}";
        A2ASecurity.PushEnvelope signed =
                A2ASecurity.signPush("task-1", body, "shared-secret");

        A2ASecurity.Verification ok = A2ASecurity.verifyPushSignature("task-1",
                signed, "shared-secret", new A2ASecurity.ReplayCache());
        assertTrue(ok.valid());
        assertEquals("ok", ok.reason());
    }

    @Test
    void pushSignature_tamperedBodyRejected() {
        String body = "{\"status\":{\"state\":\"completed\"}}";
        A2ASecurity.PushEnvelope signed =
                A2ASecurity.signPush("task-1", body, "shared-secret");

        A2ASecurity.PushEnvelope tampered = new A2ASecurity.PushEnvelope(
                signed.signature(), signed.timestamp(), signed.nonce(),
                body.replace("completed", "failed"));
        A2ASecurity.Verification bad = A2ASecurity.verifyPushSignature("task-1",
                tampered, "shared-secret", new A2ASecurity.ReplayCache());
        assertFalse(bad.valid());
        assertEquals("signature mismatch", bad.reason());
    }

    @Test
    void pushSignature_wrongSecretOrTaskIdRejected() {
        String body = "{\"x\":1}";
        A2ASecurity.PushEnvelope signed =
                A2ASecurity.signPush("task-1", body, "shared-secret");

        assertFalse(A2ASecurity.verifyPushSignature("task-1", signed,
                "other-secret", new A2ASecurity.ReplayCache()).valid());
        // The signature binds the taskId: replaying against another task fails.
        assertFalse(A2ASecurity.verifyPushSignature("task-2", signed,
                "shared-secret", new A2ASecurity.ReplayCache()).valid());
    }

    @Test
    void pushSignature_timestampOutsideWindowRejected() {
        String body = "{\"x\":1}";
        String staleTimestamp = String.valueOf(
                java.time.Instant.now().minusSeconds(600).toEpochMilli());  // 10 min old
        // Build a CORRECTLY-signed envelope with a stale timestamp (mirrors
        // the documented wire format) so the signature check passes and the
        // window check is what rejects it.
        String signature = hmacSha256("task-1", staleTimestamp, "some-nonce",
                body, "shared-secret");
        A2ASecurity.PushEnvelope stale = new A2ASecurity.PushEnvelope(
                signature, staleTimestamp, "some-nonce", body);

        A2ASecurity.Verification out = A2ASecurity.verifyPushSignature("task-1",
                stale, "shared-secret", null);
        assertFalse(out.valid());
        assertTrue(out.reason().contains("timestamp"),
                "reason should name the window violation, got: " + out.reason());
    }

    /** Independent HMAC construction per the documented envelope format. */
    private static String hmacSha256(String taskId, String timestamp, String nonce,
                                     String body, String secret) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            String payload = taskId + "\n" + timestamp + "\n" + nonce + "\n" + body;
            return java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(
                            payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void pushSignature_nonceReplayRejected() {
        String body = "{\"x\":1}";
        A2ASecurity.PushEnvelope signed =
                A2ASecurity.signPush("task-1", body, "shared-secret");
        A2ASecurity.ReplayCache cache = new A2ASecurity.ReplayCache();

        assertTrue(A2ASecurity.verifyPushSignature("task-1", signed,
                "shared-secret", cache).valid());
        A2ASecurity.Verification replay = A2ASecurity.verifyPushSignature("task-1",
                signed, "shared-secret", cache);
        assertFalse(replay.valid());
        assertEquals("nonce replayed", replay.reason());
    }

    @Test
    void replayCache_boundedEvictionKeepsFreshNoncesWorking() {
        A2ASecurity.ReplayCache cache = new A2ASecurity.ReplayCache(60_000);
        for (int i = 0; i < 12_000; i++) {
            cache.tryConsume("nonce-" + i);
        }
        // Cache blew past MAX_CACHE; eviction dropped old entries, so a
        // fresh nonce is still consumed and a NEW one is accepted.
        assertTrue(cache.tryConsume("brand-new-nonce"));
        assertFalse(cache.tryConsume("brand-new-nonce"));
    }

    @Test
    void nullEnvelope_rejectedNotThrown() {
        A2ASecurity.Verification out = A2ASecurity.verifyPushSignature("t", null,
                "s", new A2ASecurity.ReplayCache());
        assertFalse(out.valid());
        assertEquals("null envelope", out.reason());
    }
}
