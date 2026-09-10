package com.shortcuts.app.planner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GroundingContextTest {
    private val spotify = InstalledApp("Spotify", "com.spotify.music", isUserInstalled = true)
    private val maps = InstalledApp("Google Maps", "com.google.android.apps.maps")
    private val context = GroundingContext(FakeInstalledAppSource(listOf(spotify, maps)))

    @Test fun `resolves an exact label`() = assertEquals("com.spotify.music", context.resolveApp("Spotify")?.app?.packageName)

    @Test fun `resolves labels case insensitively`() = assertEquals("com.spotify.music", context.resolveApp("sPoTiFy")?.app?.packageName)

    @Test fun `resolves a partial label`() = assertEquals("com.spotify.music", context.resolveApp("spotify")?.app?.packageName)

    @Test fun `resolves a package name`() = assertEquals("com.spotify.music", context.resolveApp("com.spotify.music")?.app?.packageName)

    @Test fun `normalizes whitespace and punctuation`() = assertEquals("com.google.android.apps.maps", context.resolveApp(" Google-Maps ")?.app?.packageName)

    @Test fun `does not guess an unmatched app`() = assertNull(context.resolveApp("Not Installed Anywhere"))
}
