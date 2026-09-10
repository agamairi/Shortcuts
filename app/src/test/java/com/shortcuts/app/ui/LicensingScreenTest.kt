package com.shortcuts.app.ui

import com.shortcuts.app.service.ModelDownloader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LicensingScreenTest {

    @Test
    fun `verify model downloader constants match model attribution`() {
        assertEquals("functiongemma.litertlm", ModelDownloader.MODEL_FILE_NAME)
        assertTrue(ModelDownloader.MODEL_URL.contains("huggingface.co"))
        assertTrue(ModelDownloader.MODEL_URL.contains("litert-community/functiongemma-mobile-actions_q8_ekv1024.litertlm"))
    }

    @Test
    fun `verify gemma terms classification is distinct from OSI open source`() {
        val modelLicenseName = "Gemma Terms of Use"
        val isOsiOpenSource = modelLicenseName.contains("Apache") || modelLicenseName.contains("MIT") || modelLicenseName.contains("GPL")
        assertFalse("Gemma license must be identified as Gemma Terms of Use, not standard OSI open source", isOsiOpenSource)
    }

    @Test
    fun `verify third party dependency list categories`() {
        val dependencies = listOf(
            "com.google.mediapipe:tasks-genai",
            "androidx.core:core-ktx",
            "androidx.compose.material3:material3",
            "androidx.room:room-runtime",
            "com.squareup.okhttp3:okhttp",
            "com.squareup.retrofit2:retrofit"
        )
        assertEquals(6, dependencies.size)
        assertTrue(dependencies.any { it.contains("mediapipe") })
        assertTrue(dependencies.any { it.contains("okhttp") })
    }
}
