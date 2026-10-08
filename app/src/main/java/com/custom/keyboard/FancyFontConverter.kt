package com.custom.keyboard

class FancyFontConverter {

    enum class Style {
        BOLD_SANS,
        ITALIC_SANS,
        MONOSPACE,
        DOUBLE_STRUCK
    }

    fun convert(text: String, style: Style): String {
        val sb = StringBuilder()
        for (ch in text) {
            val converted = when (style) {
                Style.BOLD_SANS -> toBoldSans(ch)
                Style.ITALIC_SANS -> toItalicSans(ch)
                Style.MONOSPACE -> toMonospace(ch)
                Style.DOUBLE_STRUCK -> toDoubleStruck(ch)
            }
            sb.append(converted)
        }
        return sb.toString()
    }

    private fun toBoldSans(c: Char): String {
        return when (c) {
            in 'A'..'Z' -> String(Character.toChars(0x1D5D4 + (c - 'A')))
            in 'a'..'z' -> String(Character.toChars(0x1D5EE + (c - 'a')))
            in '0'..'9' -> String(Character.toChars(0x1D7EC + (c - '0')))
            else -> c.toString()
        }
    }

    private fun toItalicSans(c: Char): String {
        return when (c) {
            in 'A'..'Z' -> String(Character.toChars(0x1D608 + (c - 'A')))
            in 'a'..'z' -> String(Character.toChars(0x1D622 + (c - 'a')))
            else -> c.toString()
        }
    }

    private fun toMonospace(c: Char): String {
        return when (c) {
            in 'A'..'Z' -> String(Character.toChars(0x1D670 + (c - 'A')))
            in 'a'..'z' -> String(Character.toChars(0x1D68A + (c - 'a')))
            in '0'..'9' -> String(Character.toChars(0x1D7F6 + (c - '0')))
            else -> c.toString()
        }
    }

    private fun toDoubleStruck(c: Char): String {
        return when (c) {
            in 'A'..'Z' -> String(Character.toChars(0x1D538 + (c - 'A')))
            in 'a'..'z' -> String(Character.toChars(0x1D552 + (c - 'a')))
            in '0'..'9' -> String(Character.toChars(0x1D7D8 + (c - '0')))
            else -> c.toString()
        }
    }
}
