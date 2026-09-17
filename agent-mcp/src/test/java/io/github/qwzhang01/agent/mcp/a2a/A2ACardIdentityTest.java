package io.github.qwzhang01.agent.mcp.a2a;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Batch 4 unit tests for the card identity primitives: key generation
 * (Ed25519 preferred, RSA fallback), stable key ids, sign/verify round
 * trips, tamper rejection, malformed-input rejections, and the wire
 * header codec.
 */
class A2ACardIdentityTest {

    @Test
    void generatedKeyPairSignsAndVerifies() {
        KeyPair pair = A2ACardIdentity.generateKeyPair();
        byte[] payload = A2ACardIdentity.utf8("{\"name\":\"agent\"}");
        String signature = A2ACardIdentity.sign(payload, pair.getPrivate());
        assertTrue(A2ACardIdentity.verify(payload, signature, pair.getPublic()),
                "a fresh signature must verify with the matching public key");
    }

    @Test
    void keyIdIsStableForTheSameKey() {
        KeyPair pair = A2ACardIdentity.generateKeyPair();
        String first = A2ACardIdentity.keyIdOf(pair.getPublic());
        String second = A2ACardIdentity.keyIdOf(pair.getPublic());
        assertEquals(first, second, "the same key must produce the same id");
        assertEquals(16, first.length(), "id is a 16-hex-char fingerprint");
    }

    @Test
    void keyIdsOfDifferentKeysDiffer() {
        KeyPair a = A2ACardIdentity.generateKeyPair();
        KeyPair b = A2ACardIdentity.generateKeyPair();
        assertNotEquals(A2ACardIdentity.keyIdOf(a.getPublic()),
                A2ACardIdentity.keyIdOf(b.getPublic()),
                "two random keys must not collide on the fingerprint");
    }

    @Test
    void verifyRejectsTamperedPayload() {
        KeyPair pair = A2ACardIdentity.generateKeyPair();
        String signature = A2ACardIdentity.sign(
                A2ACardIdentity.utf8("{\"name\":\"honest\"}"), pair.getPrivate());
        byte[] tampered = A2ACardIdentity.utf8("{\"name\":\"evil\"}");
        assertFalse(A2ACardIdentity.verify(tampered, signature, pair.getPublic()),
                "a signature over other bytes must not verify");
    }

    @Test
    void verifyRejectsSignatureByAnotherKey() {
        KeyPair signer = A2ACardIdentity.generateKeyPair();
        KeyPair other = A2ACardIdentity.generateKeyPair();
        byte[] payload = A2ACardIdentity.utf8("{\"name\":\"agent\"}");
        String signature = A2ACardIdentity.sign(payload, signer.getPrivate());
        assertFalse(A2ACardIdentity.verify(payload, signature, other.getPublic()),
                "a signature by an untrusted key must not verify");
    }

    @Test
    void verifyRejectsMalformedSignature() {
        KeyPair pair = A2ACardIdentity.generateKeyPair();
        byte[] payload = A2ACardIdentity.utf8("{\"name\":\"agent\"}");
        assertFalse(A2ACardIdentity.verify(payload, "not-base64!!!", pair.getPublic()),
                "garbage signature text must verify false, not throw");
        assertFalse(A2ACardIdentity.verify(payload, (String) null, pair.getPublic()),
                "null signature must verify false");
        byte[] notBase64Signature = Base64.getUrlEncoder().withoutPadding()
                .encode(new byte[]{1, 2, 3});
        assertFalse(A2ACardIdentity.verify(payload,
                new String(notBase64Signature, StandardCharsets.UTF_8), pair.getPublic()),
                "well-formed base64 that is not a signature must verify false");
    }

    @Test
    void headerRoundTripsThroughParse() {
        String value = A2ACardIdentity.headerValue("deadbeefdeadbeef", "Ed25519", "sig123");
        A2ACardIdentity.CardSignature parsed = A2ACardIdentity.parseHeader(value);
        assertNotNull(parsed);
        assertEquals("deadbeefdeadbeef", parsed.keyId());
        assertEquals("Ed25519", parsed.algorithm());
        assertEquals("sig123", parsed.signature());
    }

    @Test
    void parseRejectsMalformedHeaders() {
        assertNull(A2ACardIdentity.parseHeader(null));
        assertNull(A2ACardIdentity.parseHeader("v2;keyId=a;alg=b;sig=c"),
                "unknown version tag must not parse");
        assertNull(A2ACardIdentity.parseHeader("v1;keyId=a;alg=b"),
                "missing segment must not parse");
        assertNull(A2ACardIdentity.parseHeader("v1;keyId=a;alg=b;sig=c;extra=d"),
                "extra segment must not parse");
        assertNull(A2ACardIdentity.parseHeader("v1;wrong=a;alg=b;sig=c"),
                "wrong field name must not parse");
        assertNull(A2ACardIdentity.parseHeader("v1;keyId=;alg=b;sig=c"),
                "empty field value must not parse");
    }

    @Test
    void algorithmForMapsKeyFamilies() {
        assertEquals("Ed25519", A2ACardIdentity.algorithmFor("Ed25519"));
        // SunEC (JDK 17) reports Ed25519 keys with the family name "EdDSA"
        assertEquals("Ed25519", A2ACardIdentity.algorithmFor("EdDSA"));
        assertEquals("SHA256withRSA", A2ACardIdentity.algorithmFor("RSA"));
        assertThrows(IllegalArgumentException.class,
                () -> A2ACardIdentity.algorithmFor("DSA"),
                "an unmapped key family must fail loud, not guess");
    }
}
