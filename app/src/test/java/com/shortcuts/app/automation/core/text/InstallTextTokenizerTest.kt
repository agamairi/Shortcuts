package com.shortcuts.app.automation.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallTextTokenizerTest {

    @Test
    fun `token is HMAC SHA-256 with install salt as key`() {
        val tokenizer = InstallTextTokenizer { "key".toByteArray() }

        assertEquals(
            "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
            tokenizer.tokenize("The quick brown fox jumps over the lazy dog")
        )
    }

    @Test
    fun `same normalized title and salt produce the same token across instances`() {
        val salt = "per-install-secret".toByteArray()
        val first = InstallTextTokenizer { salt.copyOf() }
        val second = InstallTextTokenizer { salt.copyOf() }

        val token = first.tokenize("  Caf\u0065\u0301\u00a0  settings\n")

        assertEquals(token, second.tokenize("Caf\u00e9 settings"))
        assertTrue(token.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `different install salts produce different tokens`() {
        val first = InstallTextTokenizer { byteArrayOf(1, 2, 3, 4) }
        val second = InstallTextTokenizer { byteArrayOf(4, 3, 2, 1) }

        assertNotEquals(first.tokenize("Confirmation"), second.tokenize("Confirmation"))
    }

    @Test
    fun `normalization removes zero width characters and collapses whitespace`() {
        val tokenizer = InstallTextTokenizer { byteArrayOf(9, 8, 7, 6) }

        assertEquals(
            tokenizer.tokenize("Account settings"),
            tokenizer.tokenize("\u200B Account\t\nsettings \u2060")
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty install salt is rejected`() {
        InstallTextTokenizer { byteArrayOf() }.tokenize("Title")
    }
}
