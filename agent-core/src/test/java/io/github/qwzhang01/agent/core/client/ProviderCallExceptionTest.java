package io.github.qwzhang01.agent.core.client;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 *  pins the provider error taxonomy — nine categories (adds
 * PARSE_ERROR / CANCELED over 's seven), the accessor surface
 * (retryAfter / statusCode / providerName), retryability, and the legacy
 * folding rules that keep pre-Stage-6 catch sites working.
 */
class ProviderCallExceptionTest {

    @Test
    void taxonomy_nineCodesExist() {
        // Auth, rate-limit, request, server, network, parse, cancel, timeout, unknown.
        assertEquals(9, ProviderCallException.ProviderErrorCode.values().length);
    }

    @Test
    void accessors_carryTheFullStory() {
        ProviderCallException ex = new ProviderCallException(
                ProviderCallException.ProviderErrorCode.RATE_LIMITED,
                "slow down", null, Duration.ofSeconds(3), 429, "openai");

        assertEquals(ProviderCallException.ProviderErrorCode.RATE_LIMITED, ex.getProviderCode());
        assertEquals(Duration.ofSeconds(3), ex.getRetryAfter());
        assertEquals(429, ex.getStatusCode());
        assertEquals("openai", ex.getProviderName());
        assertEquals(ModelException.ErrorCode.RATE_LIMITED, ex.getCode());
    }

    @Test
    void accessors_defaultsWhenUnset() {
        ProviderCallException ex = new ProviderCallException(
                ProviderCallException.ProviderErrorCode.MODEL_ERROR, "boom");

        assertNull(ex.getRetryAfter());
        assertEquals(0, ex.getStatusCode());
        assertEquals("unknown", ex.getProviderName());
    }

    @Test
    void retryability_perTaxonomy() {
        // Worth retrying: the condition may clear on its own.
        assertTrue(retryable(ProviderCallException.ProviderErrorCode.RATE_LIMITED));
        assertTrue(retryable(ProviderCallException.ProviderErrorCode.NETWORK_ERROR));
        assertTrue(retryable(ProviderCallException.ProviderErrorCode.TIMEOUT));
        assertTrue(retryable(ProviderCallException.ProviderErrorCode.MODEL_ERROR));
        assertTrue(retryable(ProviderCallException.ProviderErrorCode.UNKNOWN));

        // Terminal: a retry cannot fix these.
        assertFalse(retryable(ProviderCallException.ProviderErrorCode.AUTH_ERROR));
        assertFalse(retryable(ProviderCallException.ProviderErrorCode.INVALID_REQUEST));
        assertFalse(retryable(ProviderCallException.ProviderErrorCode.PARSE_ERROR));
        assertFalse(retryable(ProviderCallException.ProviderErrorCode.CANCELED));
    }

    @Test
    void legacyMapping_roundTripsAllSeven() {
        for (ModelException.ErrorCode legacy : ModelException.ErrorCode.values()) {
            assertEquals(legacy,
                    ProviderCallException.ProviderErrorCode.fromLegacy(legacy).toLegacy());
        }
    }

    @Test
    void legacyParsing_foldsIntoModelError() {
        assertEquals(ModelException.ErrorCode.MODEL_ERROR,
                ProviderCallException.ProviderErrorCode.PARSE_ERROR.toLegacy());
    }

    @Test
    void legacyCanceled_foldsIntoNetworkError() {
        // Caller aborts used to look like network noise and got retried —
        // the worst outcome. On the new taxonomy they never are.
        assertEquals(ModelException.ErrorCode.NETWORK_ERROR,
                ProviderCallException.ProviderErrorCode.CANCELED.toLegacy());
        assertFalse(retryable(ProviderCallException.ProviderErrorCode.CANCELED));
    }

    @Test
    void inheritance_existingCatchSitesStillMatch() {
        // Every pre-Stage-6 catch (ModelException) must still catch it.
        ProviderCallException ex = new ProviderCallException(
                ProviderCallException.ProviderErrorCode.AUTH_ERROR, "401");
        assertTrue(ex instanceof ModelException);
    }

    private static boolean retryable(ProviderCallException.ProviderErrorCode code) {
        return new ProviderCallException(code, "x").isRetryable();
    }
}
