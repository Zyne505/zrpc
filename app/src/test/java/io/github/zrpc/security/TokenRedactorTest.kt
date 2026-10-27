package io.github.zrpc.security

import io.github.zrpc.TestTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenRedactorTest {
    private val redactor = TokenRedactor()

    @Test fun `redacts standard token in free text`() {
        val out = redactor.redact("connecting with ${TestTokens.STANDARD} now")
        assertFalse(out.contains(TestTokens.STANDARD))
        assertTrue(out.contains(TokenRedactor.MASK))
    }

    @Test fun `redacts mfa token`() {
        assertFalse(redactor.redact("t=${TestTokens.MFA}").contains(TestTokens.MFA))
    }

    @Test fun `redacts authorization header even if malformed`() {
        val out = redactor.redact("Authorization: abc.def")
        assertFalse(out.contains("abc.def"))
    }

    @Test fun `redacts json token field`() {
        val out = redactor.redact("""{"op":2,"d":{"token":"short","properties":{}}}""")
        assertFalse(out.contains("short"))
        assertTrue(out.contains("\"token\":\"${TokenRedactor.MASK}\""))
    }

    @Test fun `redacts the exact live token even in odd formats`() {
        val live = "weird-live-token-format-123"
        val r = TokenRedactor { live }
        assertFalse(r.redact("x${live}y").contains(live))
    }

    @Test fun `leaves normal text alone`() {
        val msg = "Presence: Minecraft · online (attempt 3) at 12:00:01.123"
        assertEquals(msg, redactor.redact(msg))
    }

    @Test fun `redacts multiple tokens`() {
        val out = redactor.redact("${TestTokens.STANDARD} and ${TestTokens.OTHER}")
        assertFalse(out.contains(TestTokens.STANDARD) || out.contains(TestTokens.OTHER))
    }
}

class TokenFormatTest {
    @Test fun `accepts plausible tokens`() {
        assertTrue(TokenFormat.isPlausible(TestTokens.STANDARD))
        assertTrue(TokenFormat.isPlausible(TestTokens.MFA))
    }

    @Test fun `normalizes quotes and whitespace`() {
        assertTrue(TokenFormat.isPlausible("  \"${TestTokens.STANDARD}\"\n"))
        assertEquals(TestTokens.STANDARD, TokenFormat.normalize("'${TestTokens.STANDARD}'"))
    }

    @Test fun `rejects garbage`() {
        listOf("", "hello", "a.b.c", "Bot abc", "https://discord.com", TestTokens.STANDARD + " extra")
            .forEach { assertFalse(it, TokenFormat.isPlausible(it)) }
    }
}
