package com.custom.keyboard.launcher

import com.custom.keyboard.AppLauncherHelper.AppEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class AppSearchTest {
    private val apps = listOf(
        AppEntry("Google Maps", "com.google.android.apps.maps"),
        AppEntry("Gmail", "com.google.android.gm"),
        AppEntry("Camera", "com.android.camera"),
        AppEntry("YouTube Music", "com.google.android.apps.youtube.music"),
        AppEntry("Messages", "com.google.android.apps.messaging")
    )

    private fun names(query: String, nickname: AppEntry? = null) = AppSearch.rank(apps, query, nickname).map { it.name }

    @Test
    fun namePrefixBeatsWordPrefixBeatsSubstring() {
        // "Messages" starts with m; "Maps" and "Music" are later words; Camera and Gmail only contain it.
        assertEquals(listOf("Messages", "Google Maps", "YouTube Music", "Camera", "Gmail"), names("m"))
        assertEquals(listOf("Google Maps", "Gmail"), names("ma"))
        assertEquals(listOf("YouTube Music"), names("mus"))
    }

    @Test
    fun matchesPackageNamesLast() {
        assertEquals(listOf("Messages"), names("messaging"))
    }

    @Test
    fun nicknameGoesFirst() {
        val yt = apps[3]
        assertEquals("YouTube Music", names("yt", yt).first())
    }

    @Test
    fun emptyQueryReturnsEverything() {
        assertEquals(apps.size, names("  ").size)
    }
}
