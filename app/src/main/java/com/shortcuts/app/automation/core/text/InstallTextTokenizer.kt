package com.shortcuts.app.automation.core.text

import com.shortcuts.app.automation.model.TextTokenizer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Produces privacy-preserving, install-scoped tokens for recorded and observed UI text.
 *
 * Capture and replay must share the same salt provider. Normalization happens here so callers
 * cannot accidentally hash different representations of the same visible text.
 */
class InstallTextTokenizer(
    private val saltProvider: () -> ByteArray
) : TextTokenizer {

    override fun tokenize(text: String): String {
        val salt = saltProvider().copyOf()
        require(salt.isNotEmpty()) { "Install text-token salt must not be empty" }

        val normalized = TextNormalizer.normalize(text).orEmpty()
        val mac = Mac.getInstance(HMAC_SHA_256)
        mac.init(SecretKeySpec(salt, HMAC_SHA_256))
        return mac.doFinal(normalized.toByteArray(Charsets.UTF_8)).toLowerHex()
    }

    private fun ByteArray.toLowerHex(): String = buildString(size * 2) {
        for (byte in this@toLowerHex) {
            val value = byte.toInt() and 0xff
            append(HEX[value ushr 4])
            append(HEX[value and 0x0f])
        }
    }

    private companion object {
        const val HMAC_SHA_256 = "HmacSHA256"
        const val HEX = "0123456789abcdef"
    }
}
