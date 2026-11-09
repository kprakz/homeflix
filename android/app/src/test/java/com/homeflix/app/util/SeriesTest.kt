package com.homeflix.app.util

import com.homeflix.app.data.LibraryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeriesTest {
    @Test
    fun `server metadata wins`() {
        val info = Series.of(LibraryItem(id = "1", title = "Whatever", show = "Dark", season = 2, ep = 3))
        assertEquals(SeriesInfo("Dark", 2, 3), info)
    }

    @Test
    fun `old servers are parsed from the file name`() {
        val info = Series.of(LibraryItem(id = "1", title = "The Bear S03E07 1080p"))
        assertEquals(SeriesInfo("The Bear", 3, 7), info)
    }

    @Test
    fun `movies have no series info`() {
        assertNull(Series.of(LibraryItem(id = "1", title = "Heat (1995)")))
    }

    @Test
    fun `episodes are ordered and next is found`() {
        val items = listOf(
            LibraryItem(id = "b", title = "Show S01E02", show = "Show", season = 1, ep = 2),
            LibraryItem(id = "a", title = "Show S01E01", show = "Show", season = 1, ep = 1),
            LibraryItem(id = "c", title = "Show S01E03", show = "Show", season = 1, ep = 3),
            LibraryItem(id = "movie", title = "Movie"),
        )
        assertEquals(listOf("a", "b", "c"), Series.group(items)["Show"]?.map { it.id })
        assertEquals("b", Series.nextEpisode(items, items[1])?.id)
        assertNull(Series.nextEpisode(items, items[2]))
    }
}
