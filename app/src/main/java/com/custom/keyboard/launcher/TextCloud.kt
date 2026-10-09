package com.custom.keyboard.launcher

import kotlin.math.pow

/**
 * Text page sizing, like tiles: the apps you use most get the biggest names, and an app you stop
 * using shrinks a step at a time as its weight fades. An app with a big tile on Start is never
 * smaller than its tile. Pure, so it is unit-tested.
 */
object TextCloud {
    const val LEVELS = 5

    /** [score] made [then] has faded by [now], halving every [halfLifeDays]. */
    fun decay(score: Double, then: Long, now: Long, halfLifeDays: Double): Double {
        val days = ((now - then).coerceAtLeast(0L)) / 86_400_000.0
        return score * 0.5.pow(days / halfLifeDays)
    }

    /**
     * Size level 0 (smallest) to 4 for each app. Used apps are ranked: the top 6% get level 4,
     * the next 12% level 3, the next 22% level 2, the rest of the used apps level 1. Apps with
     * almost no recent use are level 0. [tileLevel] lifts apps pinned as big tiles.
     */
    fun levels(packages: List<String>, heat: Map<String, Double>, tileLevel: (String) -> Int): Map<String, Int> {
        val used = packages.filter { (heat[it] ?: 0.0) >= 0.5 }.sortedByDescending { heat[it] ?: 0.0 }
        val out = HashMap<String, Int>(packages.size)
        val n = used.size.coerceAtLeast(1)
        used.forEachIndexed { rank, pkg ->
            val f = rank.toDouble() / n
            out[pkg] = when {
                f < 0.06 -> 4
                f < 0.18 -> 3
                f < 0.40 -> 2
                else -> 1
            }
        }
        packages.forEach { pkg -> out[pkg] = maxOf(out[pkg] ?: 0, tileLevel(pkg)).coerceIn(0, LEVELS - 1) }
        return out
    }

    /** Level a tile gives its app: medium 1, wide 2, 3×3 and up 3, the full 4×4 4. */
    fun levelForTile(cols: Int, rows: Int): Int {
        val area = cols * rows
        return when {
            area >= 16 -> 4
            area >= 9 -> 3
            area >= 6 -> 2
            area >= 4 -> 1
            else -> 0
        }
    }

    /** Text size in sp for a level, spread evenly from [minSp] to [maxSp]. */
    fun sizeSp(level: Int, minSp: Int, maxSp: Int): Float {
        val lo = minOf(minSp, maxSp)
        val hi = maxOf(minSp, maxSp)
        return lo + (hi - lo) * (level.coerceIn(0, LEVELS - 1) / (LEVELS - 1f))
    }
}
