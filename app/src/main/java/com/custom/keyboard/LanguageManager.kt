package com.custom.keyboard

class LanguageManager {

    enum class Language(val code: String, val displayName: String) {
        ENGLISH("en", "English"),
        ARABIC("ar", "العربية"),
        FRENCH("fr", "Français"),
        SPANISH("es", "Español")
    }

    // Arabic Standard Layout Rows
    val arabicRow1 = listOf("ض", "ص", "ث", "ق", "ف", "غ", "ع", "ه", "خ", "ح", "ج", "د")
    val arabicRow2 = listOf("ش", "س", "ي", "ب", "ل", "ا", "ت", "ن", "م", "ك", "ط")
    val arabicRow3 = listOf("ئ", "ء", "ؤ", "ر", "لا", "ى", "ة", "و", "ز", "ظ")

    // French AZERTY Rows
    val frenchRow1 = listOf("a", "z", "e", "r", "t", "y", "u", "i", "o", "p")
    val frenchRow2 = listOf("q", "s", "d", "f", "g", "h", "j", "k", "l", "m")
    val frenchRow3 = listOf("w", "x", "c", "v", "b", "n", "é", "è", "à", "ç")
}
