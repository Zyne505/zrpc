package io.github.zrpc

/**
 * Fake but correctly shaped tokens. None of these are real credentials.
 * Assembled at runtime so the source never contains a token-shaped string
 * (which would trip secret scanners such as GitHub push protection).
 */
object TestTokens {
    private fun join(vararg parts: String) = parts.joinToString(".")

    val STANDARD = join("MTIzNDU2Nzg5MDEy" + "MzQ1Njc4OQ", "Ga" + "BcDe", "abcdefghijklmnop" + "qrstuvwxyz0123456789AB")
    val OTHER = join("OTg3NjU0MzIxMDk4" + "NzY1NDMyMQ", "Gz" + "9y8x", "ZYXWVUTSRQPONMLK" + "JIHGFEDCBA9876543210zz")
    val MFA = join("mfa", "a1B2c3D4e5".repeat(8) + "xyzw")
}
