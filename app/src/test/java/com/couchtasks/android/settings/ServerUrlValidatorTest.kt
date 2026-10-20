package com.couchtasks.android.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerUrlValidatorTest {
    @Test
    fun validateAndNormalizeServerUrl_acceptsAbsoluteHttpsWithHostAndTrimsSlash() {
        assertEquals(
            ServerUrlValidationResult.Valid("https://tasks.example.com/base"),
            validateAndNormalizeServerUrl("  https://tasks.example.com/base///  "),
        )
    }

    @Test
    fun canonicalUrlsIgnoreHostCaseDefaultPortAndTrailingSlashes() {
        assertEquals(validateAndNormalizeServerUrl("https://tasks.example.com"),
            validateAndNormalizeServerUrl(" HTTPS://TASKS.EXAMPLE.COM:443/// "))
    }

    @Test
    fun validateAndNormalizeServerUrl_rejectsEmptyMalformedNonHttpsHostlessAndAmbiguousParts() {
        assertEquals(ServerUrlValidationResult.Missing, validateAndNormalizeServerUrl("   "))
        assertEquals(ServerUrlValidationResult.Malformed, validateAndNormalizeServerUrl("tasks.example.com"))
        assertEquals(ServerUrlValidationResult.NotHttps, validateAndNormalizeServerUrl("http://tasks.example.com"))
        assertEquals(ServerUrlValidationResult.MissingHost, validateAndNormalizeServerUrl("https:///sync"))
        assertEquals(ServerUrlValidationResult.Malformed, validateAndNormalizeServerUrl("https://tasks.example.com/api?x=1"))
        assertEquals(ServerUrlValidationResult.Malformed, validateAndNormalizeServerUrl("https://tasks.example.com/api#fragment"))
        assertEquals(ServerUrlValidationResult.Malformed, validateAndNormalizeServerUrl("https://user:pass@tasks.example.com"))
    }
}
