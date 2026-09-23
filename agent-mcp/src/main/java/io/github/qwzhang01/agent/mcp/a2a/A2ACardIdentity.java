package io.github.qwzhang01.agent.mcp.a2a;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.Base64;

/**
 * Batch 4 (harness roadmap 6.3): cryptographic identity for the agent card.
 * <p>
 * The trust gap this closes: {@code /.well-known/agent.json} is a
 * SELF-REPORT. The bearer gate (Stage 6.3) covers the request boundary —
 * but the CARD itself, the thing a caller uses to decide "who am I talking
 * to and what can they do", was plaintext. Anyone who can serve bytes on
 * that URL can claim any name, any skill list, any version.
 * <p>
 * The mechanism is deliberately transport-shaped and spec-clean: the card
 * JSON body is served UNCHANGED (a spec peer that knows nothing of this
 * extension sees a normal card); the signature rides a response HEADER
 * ({@link #CARD_SIGNATURE_HEADER}) and covers the card body BYTE-EXACT —
 * no canonicalization step, no ambiguity about what was signed. Identity
 * (name/url), capabilities and version are all inside the signed payload,
 * so a verified card is a trusted answer to "who is this agent", not just
 * "who served this byte stream".
 * <p>
 * Primitive layer only, same discipline as {@link A2ASecurity}: this class
 * knows how to sign and verify. WHICH keys to trust is a host policy
 * decision (a trust store, a CA hierarchy, a TTP directory) — the HTTP
 * server wires a key pair it owns, the HTTP client wires the public keys
 * it is willing to believe.
 * <p>
 * Algorithms: Ed25519 where available (JDK 15+ ships it in SunEC; small
 * keys, small signatures, no parameter choices to get wrong), with an
 * RSA-2048 fallback for environments without it. The algorithm is derived
 * from the key, not chosen by the caller — a caller who picks the
 * algorithm is one confused deputy away from "none".
 * <p>
 * Honest boundaries:
 * <ul>
 *   <li>This is a FRAMEWORK trust extension, not part of the A2A spec's
 *       {@code securitySchemes} — an A2A peer without this extension
 *       neither signs nor verifies, and that peer's cards stay exactly as
 *       trustworthy as before (routing input, not trust input, D7).</li>
 *   <li>Key distribution is the HOST's problem. {@link #keyIdOf} gives a
 *       stable fingerprint so a trust store can pick the right key and a
 *       log can name the key that signed; it is not a PKI.</li>
 *   <li>No revocation story in v1 (short-lived processes; revocation wants
 *       a CRL/OCSP-ish substrate that does not exist here).</li>
 * </ul>
 */
public final class A2ACardIdentity {

    /** Response header carrying the card signature. */
    public static final String CARD_SIGNATURE_HEADER = "X-Agent-Card-Signature";

    /** Header format version tag (kept for forward evolution of the shape). */
    static final String HEADER_VERSION = "v1";

    private static final SecureRandom RANDOM = new SecureRandom();

    private A2ACardIdentity() {
    }


    /**
     * Generate a signing key pair: Ed25519 when the JCA offers it, RSA-2048
     * otherwise. The private half signs cards on the server side; the
     * public half goes into every caller's trust store.
     */
    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
            return generator.generateKeyPair();
        } catch (Exception noEd25519) {
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048, RANDOM);
                return generator.generateKeyPair();
            } catch (Exception e) {
                throw new IllegalStateException("no usable signature keypair generator", e);
            }
        }
    }

    /**
     * Stable fingerprint of a public key: first 16 hex chars of
     * SHA-256(encoded). Two keys with the same id are the same key for
     * trust-store purposes; the id rides the signature header so a
     * verifier can name what signed and pick the matching entry.
     */
    public static String keyIdOf(PublicKey key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(key.getEncoded());
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }


    /**
     * Sign the card body bytes with the private key. The algorithm is
     * derived from the key's own family (Ed25519 key → Ed25519, RSA key →
     * SHA256withRSA).
     */
    public static String sign(byte[] payload, PrivateKey key) {
        try {
            Signature signature = Signature.getInstance(algorithmFor(key.getAlgorithm()));
            signature.initSign(key);
            signature.update(payload);
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException("card signing failed: " + e.getMessage(), e);
        }
    }

    /**
     * Verify a card body against a base64url signature with a public key.
     * Malformed input (bad base64, unknown algorithm family) verifies
     * false — a card that cannot be verified is untrusted, exactly like
     * one that fails verification.
     */
    public static boolean verify(byte[] payload, String signature, PublicKey key) {
        if (payload == null || signature == null || key == null) {
            return false;
        }
        byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(signature);
        } catch (IllegalArgumentException badBase64) {
            return false;
        }
        try {
            Signature verifier = Signature.getInstance(algorithmFor(key.getAlgorithm()));
            verifier.initVerify(key);
            verifier.update(payload);
            return verifier.verify(decoded);
        } catch (Exception e) {
            return false;
        }
    }

    /** JCA signature algorithm for a key algorithm family. */
    static String algorithmFor(String keyAlgorithm) {
        // SunEC (JDK 17) reports Ed25519 key pairs with the FAMILY name
        // "EdDSA"; some providers use "Ed25519" directly. Both map to the
        // same JCA Signature instance.
        if ("Ed25519".equalsIgnoreCase(keyAlgorithm) || "EdDSA".equals(keyAlgorithm)) {
            return "Ed25519";
        }
        if ("RSA".equalsIgnoreCase(keyAlgorithm)) {
            return "SHA256withRSA";
        }
        throw new IllegalArgumentException(
                "unsupported card-signing key algorithm: " + keyAlgorithm);
    }


    /** A parsed card-signature header: who signed, how, and with what value. */
    public record CardSignature(String keyId, String algorithm, String signature) {
    }

    /**
     * Render the header value: {@code v1;keyId=&lt;hex&gt;;alg=&lt;jca
     * name&gt;;sig=&lt;base64url&gt;}. Base64url and hex carry no {@code ;}
     * or {@code =} hazards beyond the {@code key=value} separators.
     */
    public static String headerValue(String keyId, String algorithm, String signature) {
        return HEADER_VERSION + ";keyId=" + keyId + ";alg=" + algorithm
                + ";sig=" + signature;
    }

    /**
     * Parse the header value back. Returns null on any malformation (wrong
     * version tag, missing fields, stray segments) — the caller decides
     * what an unparseable signature means (in {@link HttpA2AClient} with a
     * trust store configured, it means refuse the card).
     */
    public static CardSignature parseHeader(String headerValue) {
        if (headerValue == null) {
            return null;
        }
        String[] segments = headerValue.split(";");
        if (segments.length != 4 || !HEADER_VERSION.equals(segments[0].trim())) {
            return null;
        }
        String keyId = fieldValue(segments[1], "keyId");
        String algorithm = fieldValue(segments[2], "alg");
        String signature = fieldValue(segments[3], "sig");
        if (keyId == null || algorithm == null || signature == null) {
            return null;
        }
        return new CardSignature(keyId, algorithm, signature);
    }

    private static String fieldValue(String segment, String field) {
        String prefix = field + "=";
        if (!segment.startsWith(prefix)) {
            return null;
        }
        String value = segment.substring(prefix.length());
        return value.isEmpty() ? null : value;
    }

    /** UTF-8 bytes of a card body (the exact bytes the header signs). */
    public static byte[] utf8(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }
}
