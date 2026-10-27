package io.github.zrpc.security

/**
 * Shape checks for Discord user tokens. This never "validates" a token
 * (only Discord can do that); it just rejects obvious garbage before we
 * store or send anything, and gives the redactor a pattern to scrub.
 */
object TokenFormat {
    /** Standard token: base64(userId).timestamp.hmac */
    private val STANDARD = Regex("""^[A-Za-z0-9_-]{20,30}\.[A-Za-z0-9_-]{5,8}\.[A-Za-z0-9_-]{25,110}$""")

    /** Legacy MFA token. */
    private val MFA = Regex("""^mfa\.[A-Za-z0-9_-]{60,120}$""")

    /** Pattern used to find tokens embedded anywhere in free text. */
    val EMBEDDED = Regex(
        """(?<![A-Za-z0-9_-])(?:mfa\.[A-Za-z0-9_-]{60,120}|[A-Za-z0-9_-]{20,30}\.[A-Za-z0-9_-]{5,8}\.[A-Za-z0-9_-]{25,110})(?![A-Za-z0-9_-])"""
    )

    /** Strips whitespace and surrounding quotes people often paste by accident. */
    fun normalize(raw: String): String =
        raw.trim().removeSurrounding("\"").removeSurrounding("'").trim()

    fun isPlausible(raw: String): Boolean {
        val t = normalize(raw)
        return STANDARD.matches(t) || MFA.matches(t)
    }
}
