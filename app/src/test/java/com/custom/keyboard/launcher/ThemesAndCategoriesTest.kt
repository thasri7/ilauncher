package com.custom.keyboard.launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThemesAndCategoriesTest {
    @Test
    fun themeJsonRoundTripsAndClampsBadValues() {
        val t = MetroThemes.builtIns.first { it.name == "Glass" }
        assertEquals(t.copy(builtIn = false), MetroThemes.fromJson(MetroThemes.toJson(t)))
        val wild = JSONObject().put("name", "x").put("accent", "#ff0000").put("opacity", 900).put("columns", 99)
        val parsed = MetroThemes.fromJson(wild)!!
        assertEquals(100, parsed.opacity)
        assertEquals(6, parsed.columns)
        assertNull(MetroThemes.fromJson(JSONObject().put("accent", "red")))
    }

    @Test
    fun guessesAppKinds() {
        assertEquals(AppCategories.SOCIAL, AppCategories.guess("com.whatsapp", "WhatsApp"))
        assertEquals(AppCategories.MONEY, AppCategories.guess("com.phonepe.app", "PhonePe"))
        assertEquals(AppCategories.PHOTOS, AppCategories.guess("com.motorola.camera3", "Camera"))
        assertNull(AppCategories.guess("org.example.xyz", "Xyz"))
    }

    @Test
    fun namesFoldersAfterTheirApps() {
        assertEquals("Social", AppCategories.nameFor(listOf("Social", "Social", null), listOf("A", "B", "C")))
        assertEquals("Google", AppCategories.nameFor(listOf(null, null, "Travel"), listOf("Google Maps", "Google Photos", "Uber")))
        assertEquals("Folder", AppCategories.nameFor(listOf(null, null), listOf("Xx", "Yy"), "Folder"))
    }
}
