package com.custom.keyboard

import com.custom.keyboard.launcher.QuickAnswers

/**
 * Unit answers for the keyboard's suggestion strip ("10km", "70kg", "25c"). Real conversions
 * from [QuickAnswers]; currencies are left out here because they need live rates.
 */
class UnitConverter {
    fun convert(query: String): String? {
        val q = query.trim()
        if (!Regex("""^\d+(?:\.\d+)?\s*[a-zA-Z/°]+$""").matches(q)) return null
        val answer = QuickAnswers.answer(q, rates = null) ?: return null
        return answer.text.removePrefix("= ")
    }
}
