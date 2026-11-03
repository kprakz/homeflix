package com.homeflix.app.util

import com.homeflix.app.data.LibraryItem

data class SeriesInfo(val name: String, val season: Int, val episode: Int)

object Series {
    private val pattern = Regex("""^(.*?)[\s-]*S(\d{1,2})\s*E(\d{1,3})\b""", RegexOption.IGNORE_CASE)

    /** The server now sends show/season/ep; keep a fallback for older HOMEFLIX versions. */
    fun of(item: LibraryItem): SeriesInfo? {
        val show = item.show
        val season = item.season
        val episode = item.ep
        if (!show.isNullOrBlank() && season != null && episode != null) {
            return SeriesInfo(show, season, episode)
        }
        val match = pattern.find(item.title) ?: return null
        val name = match.groupValues[1].trim().ifBlank { return null }
        return SeriesInfo(
            name = name,
            season = match.groupValues[2].toIntOrNull() ?: return null,
            episode = match.groupValues[3].toIntOrNull() ?: return null,
        )
    }

    fun group(items: List<LibraryItem>): Map<String, List<LibraryItem>> {
        val shows = LinkedHashMap<String, MutableList<LibraryItem>>()
        for (item in items) {
            val info = of(item) ?: continue
            shows.getOrPut(info.name) { mutableListOf() }.add(item)
        }
        return shows.mapValues { (_, episodes) ->
            episodes.sortedWith(compareBy({ of(it)?.season ?: 0 }, { of(it)?.episode ?: 0 }))
        }
    }

    fun nextEpisode(items: List<LibraryItem>, current: LibraryItem): LibraryItem? {
        val info = of(current) ?: return null
        val episodes = group(items)[info.name] ?: return null
        val index = episodes.indexOfFirst { it.id == current.id }
        if (index < 0) return null
        return episodes.getOrNull(index + 1)
    }

    fun label(item: LibraryItem): String = of(item)?.let { "S${it.season}:E${it.episode}" } ?: ""
}
