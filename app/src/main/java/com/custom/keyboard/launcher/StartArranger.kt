package com.custom.keyboard.launcher

import com.custom.keyboard.models.TileItem
import com.custom.keyboard.models.TileSize
import com.custom.keyboard.models.TileType
import java.util.UUID

/**
 * One-tap ways to tidy Start. Each works on a copy of the tile list and keeps group names where
 * they are, so the result can be undone by putting the old list back. Pure, so it is unit-tested.
 */
object StartArranger {

    /** Start split at its group names: the first segment may have no name (header = null). */
    data class Segment(val header: TileItem?, val tiles: List<TileItem>)

    fun segments(tiles: List<TileItem>): List<Segment> {
        val out = ArrayList<Segment>()
        var header: TileItem? = null
        var current = ArrayList<TileItem>()
        for (t in tiles) {
            if (t.type == TileType.SECTION_HEADER) {
                if (header != null || current.isNotEmpty()) out.add(Segment(header, current))
                header = t
                current = ArrayList()
            } else {
                current.add(t)
            }
        }
        if (header != null || current.isNotEmpty()) out.add(Segment(header, current))
        return out
    }

    fun flatten(segments: List<Segment>): List<TileItem> = segments.flatMap { listOfNotNull(it.header) + it.tiles }

    private fun sortEach(tiles: List<TileItem>, sorter: (List<TileItem>) -> List<TileItem>): List<TileItem> =
        flatten(segments(tiles).map { it.copy(tiles = sorter(it.tiles)) })

    /** A to Z inside every group. */
    fun alphabetical(tiles: List<TileItem>): List<TileItem> =
        sortEach(tiles) { list -> list.sortedBy { it.title.lowercase() } }

    /** Most opened first inside every group. */
    fun byUse(tiles: List<TileItem>, launches: (TileItem) -> Int): List<TileItem> =
        sortEach(tiles) { list -> list.sortedByDescending(launches) }

    /**
     * Big tiles first inside every group (taller, then wider), keeping your order among tiles of
     * the same size. The grid then packs small tiles into the gaps, so Start has no holes.
     */
    fun packTightly(tiles: List<TileItem>): List<TileItem> =
        sortEach(tiles) { list -> list.sortedWith(compareByDescending<TileItem> { it.size.rows }.thenByDescending { it.size.cols }) }

    /**
     * Puts app tiles into named groups by kind (Social, Games, …). Tiles that aren't apps (clock,
     * weather, folders, widgets…) stay together at the top. Kinds with a single app go to
     * "Other". Existing group names are replaced.
     */
    fun byCategory(tiles: List<TileItem>, categoryOf: (TileItem) -> String?, launches: (TileItem) -> Int): List<TileItem> {
        val content = tiles.filter { it.type != TileType.SECTION_HEADER }
        val top = content.filter { it.type != TileType.APP_SHORTCUT }
        val apps = content.filter { it.type == TileType.APP_SHORTCUT }
        val byKind = apps.groupBy { categoryOf(it) ?: OTHER }
        val single = byKind.filter { it.value.size < 2 && it.key != OTHER }.values.flatten()
        val groups = byKind.filter { it.value.size >= 2 && it.key != OTHER }
            .toList()
            .sortedByDescending { (_, list) -> list.sumOf(launches) }
        val other = (byKind[OTHER].orEmpty() + single)
        val out = ArrayList<TileItem>(top)
        groups.forEach { (kind, list) ->
            out.add(header(kind))
            out.addAll(list.sortedByDescending(launches))
        }
        if (other.isNotEmpty()) {
            if (groups.isNotEmpty()) out.add(header(OTHER))
            out.addAll(other.sortedByDescending(launches))
        }
        return out
    }

    /** Moves a group (name and tiles) one place up or down. Returns null if it can't move. */
    fun moveGroup(tiles: List<TileItem>, header: TileItem, up: Boolean): List<TileItem>? {
        val segs = segments(tiles).toMutableList()
        val i = segs.indexOfFirst { it.header === header }
        if (i < 0) return null
        val j = if (up) i - 1 else i + 1
        // Never move above the unnamed first segment's position if it has no header.
        if (j < 0 || j >= segs.size || (j == 0 && segs[0].header == null)) return null
        val tmp = segs[i]
        segs[i] = segs[j]
        segs[j] = tmp
        return flatten(segs)
    }

    private const val OTHER = "Other"

    private fun header(name: String) = TileItem(UUID.randomUUID().toString(), TileType.SECTION_HEADER, name, size = TileSize.WIDE)
}
