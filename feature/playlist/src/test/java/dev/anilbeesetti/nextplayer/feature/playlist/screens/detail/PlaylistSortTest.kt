package dev.anilbeesetti.nextplayer.feature.playlist.screens.detail

import dev.anilbeesetti.nextplayer.core.model.M3UPlaylistItem
import dev.anilbeesetti.nextplayer.core.model.PlaylistItem
import dev.anilbeesetti.nextplayer.core.model.Video
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PlaylistSortTest {

    @Test
    fun nameSortReadsDigitsTheWayTheLibraryGridDoes() {
        val items = listOf(
            item(uri = "10", title = "Episode 10"),
            item(uri = "2", title = "Episode 2"),
            item(uri = "1", title = "episode 1"),
        )

        assertEquals(
            listOf("1", "2", "10"),
            playlistUrisForSort(items, PlaylistSort.NAME_ASCENDING),
        )
        assertEquals(
            listOf("10", "2", "1"),
            playlistUrisForSort(items, PlaylistSort.NAME_DESCENDING),
        )
    }

    @Test
    fun durationSortUsesLocalVideoMillisecondsAndKeepsUnknownRowsLast() {
        val items = listOf(
            item(uri = "unknown", title = "No length"),
            item(uri = "long", title = "Long", videoDuration = 90_000),
            item(uri = "short", title = "Short", videoDuration = 5_000),
        )

        assertEquals(
            listOf("short", "long", "unknown"),
            playlistUrisForSort(items, PlaylistSort.DURATION_ASCENDING),
        )
        assertEquals(
            listOf("long", "short", "unknown"),
            playlistUrisForSort(items, PlaylistSort.DURATION_DESCENDING),
        )
    }

    @Test
    fun durationSortFallsBackToTheM3UExtInfSeconds() {
        val items = listOf(
            item(uri = "wide", title = "Wide", extInfSeconds = 120),
            item(uri = "clip", title = "Clip", extInfSeconds = 30),
        )

        assertEquals(
            listOf("clip", "wide"),
            playlistUrisForSort(items, PlaylistSort.DURATION_ASCENDING),
        )
    }

    @Test
    fun shuffleKeepsEveryItemAndRepeatsForTheSameSeed() {
        val items = (1..6).map { item(uri = it.toString(), title = "Item $it") }

        val shuffled = playlistUrisForSort(items, PlaylistSort.SHUFFLE, random = Random(7))

        assertEquals(items.map { it.uri }.toSet(), shuffled.toSet())
        assertEquals(shuffled, playlistUrisForSort(items, PlaylistSort.SHUFFLE, random = Random(7)))
    }

    @Test
    fun shuffleOfADifferentSeedMovesTheItems() {
        val items = (1..12).map { item(uri = it.toString(), title = "Item $it") }

        val first = playlistUrisForSort(items, PlaylistSort.SHUFFLE, random = Random(1))
        val second = playlistUrisForSort(items, PlaylistSort.SHUFFLE, random = Random(2))

        assertNotEquals(first, second)
    }

    @Test
    fun listsWithoutAnyKnownDurationHideTheDurationRows() {
        val items = listOf(item(uri = "a", title = "A"), item(uri = "b", title = "B"))

        assertFalse(items.hasSortableDuration())
        assertFalse(PlaylistSort.DURATION_ASCENDING.showsFor(hasDuration = false))
        assertFalse(PlaylistSort.DURATION_DESCENDING.showsFor(hasDuration = false))
        assertTrue(PlaylistSort.NAME_ASCENDING.showsFor(hasDuration = false))
        assertTrue(PlaylistSort.INSERTION_ORDER.showsFor(hasDuration = false))
    }

    @Test
    fun listsWhereOneItemKnowsItsDurationOfferBothDurationRows() {
        val items = listOf(
            item(uri = "a", title = "A"),
            item(uri = "b", title = "B", videoDuration = 1_000),
        )

        assertTrue(items.hasSortableDuration())
        assertTrue(PlaylistSort.DURATION_ASCENDING.showsFor(hasDuration = true))
    }

    private fun item(
        uri: String,
        title: String,
        extInfSeconds: Int = M3UPlaylistItem.UNKNOWN_DURATION,
        videoDuration: Long = 0,
    ) = PlaylistItem(
        position = 0,
        uri = uri,
        title = title,
        tvgLogo = null,
        duration = extInfSeconds,
        groupTitle = null,
        video = videoDuration.takeIf { it > 0 }?.let { duration ->
            Video(
                id = uri.hashCode().toLong(),
                path = "/videos/$title.mp4",
                duration = duration,
                uriString = uri,
                nameWithExtension = "$title.mp4",
                width = 1920,
                height = 1080,
                size = 1_000,
            )
        },
    )
}
