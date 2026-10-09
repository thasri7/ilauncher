package com.custom.keyboard.launcher

/** "Suggested now": the apps you usually open around this hour. Pure, so it can be unit-tested. */
object Suggestions {
    /**
     * Scores each app by its launches in the current hour (weight 3) and the hours either side
     * (weight 1), so 8:55 still counts towards a 9 o'clock habit.
     */
    fun forHour(usage: Map<String, IntArray>, hour: Int, max: Int, exclude: Set<String> = emptySet()): List<String> {
        val h = ((hour % 24) + 24) % 24
        return usage.asSequence()
            .filter { it.key !in exclude }
            .map { (pkg, b) -> pkg to (3 * b[h] + b[(h + 23) % 24] + b[(h + 1) % 24]) }
            .filter { it.second >= 2 }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
            .take(max)
            .map { it.first }
            .toList()
    }

    fun partOfDay(hour: Int): String = when (hour) {
        in 5..11 -> "morning"
        in 12..16 -> "afternoon"
        in 17..21 -> "evening"
        else -> "night"
    }
}
