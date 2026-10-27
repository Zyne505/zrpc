package io.github.zrpc.security

/**
 * Removes anything token-shaped from text before it is logged, shown in the
 * logs screen, written to a crash report or shared.
 *
 * Three layers:
 *  1. the exact current token (if known) is replaced wherever it appears,
 *  2. anything matching Discord's token shape is replaced,
 *  3. Authorization header values and `"token":"..."` JSON fields are replaced
 *     even if they don't look like a token (e.g. truncated ones).
 */
class TokenRedactor(private val currentToken: () -> String? = { null }) {

    fun redact(input: String?): String {
        if (input.isNullOrEmpty()) return input.orEmpty()
        var out: String = input
        currentToken()?.takeIf { it.length >= 8 }?.let { out = out.replace(it, MASK) }
        out = TokenFormat.EMBEDDED.replace(out, MASK)
        out = AUTH_HEADER.replace(out) { "${it.groupValues[1]}$MASK" }
        out = JSON_TOKEN.replace(out) { "${it.groupValues[1]}$MASK\"" }
        return out
    }

    companion object {
        const val MASK = "[REDACTED_TOKEN]"
        private val AUTH_HEADER = Regex("""(?i)(authorization["']?\s*[:=]\s*["']?)[^\s"',}]+""")
        private val JSON_TOKEN = Regex("""(?i)("token"\s*:\s*")[^"]*"""")
    }
}
