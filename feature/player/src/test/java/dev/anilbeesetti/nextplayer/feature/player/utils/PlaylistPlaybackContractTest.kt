package dev.anilbeesetti.nextplayer.feature.player.utils

import dev.anilbeesetti.nextplayer.core.model.PlaylistItemRecord
import dev.anilbeesetti.nextplayer.core.model.PlaylistRecord
import dev.anilbeesetti.nextplayer.core.model.PlaylistType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PlaylistPlaybackContractTest {

    @Test
    fun queueKeepsDatabaseOrderAndSelectsRequestedUri() {
        val queue = playlistRecord().toMediaQueue("https://example.com/two")

        assertEquals(
            listOf("https://example.com/one", "https://example.com/two"),
            queue?.mediaItems?.map { it.mediaId },
        )
        assertEquals(1, queue?.startIndex)
    }

    @Test
    fun queueAddsParsedTitleAndArtworkMetadata() {
        val queue = playlistRecord().toMediaQueue("https://example.com/one")

        val item = queue?.mediaItems?.first()
        assertEquals("One", item?.mediaMetadata?.title)
        assertEquals(
            "https://example.com/one.png",
            item?.mediaMetadata?.artworkUri?.toString(),
        )
    }

    @Test
    fun missingSelectedEntryReturnsNullForSingleItemFallback() {
        assertNull(playlistRecord().toMediaQueue("https://example.com/missing"))
    }

    /**
     * The rows store no title, and only the playing item used to get one, so the rest of the queue came in
     * unnamed and the portrait swipe had nothing to show. A name is now read off the URI - except where the URI
     * ends in a MediaStore row id, which is a number rather than a name.
     */
    @Test
    fun queueNamesEveryItemWhereTheUriCarriesAName() {
        val record = PlaylistRecord(
            id = 3,
            name = "List",
            type = PlaylistType.LOCAL,
            source = null,
            items = listOf(
                PlaylistItemRecord(
                    position = 0,
                    uri = "smb://192.0.2.1/Media/%E6%97%A5%E6%9C%AC%E8%AA%9E.mkv?cid=3",
                ),
                PlaylistItemRecord(
                    position = 1,
                    uri = "content://media/external/video/1234",
                ),
            ),
            lastRefreshedAt = null,
        )

        val queue = record.toMediaQueue(selectedUri = "smb://192.0.2.1/Media/%E6%97%A5%E6%9C%AC%E8%AA%9E.mkv?cid=3")

        assertEquals("日本語", queue?.mediaItems?.first()?.mediaMetadata?.title)
        assertNull(queue?.mediaItems?.last()?.mediaMetadata?.title)
    }

    private fun playlistRecord() = PlaylistRecord(
        id = 7,
        name = "Channels",
        type = PlaylistType.M3U_URL,
        source = "https://example.com/list.m3u",
        items = listOf(
            PlaylistItemRecord(
                position = 0,
                uri = "https://example.com/one",
                title = "One",
                tvgLogo = "https://example.com/one.png",
            ),
            PlaylistItemRecord(
                position = 1,
                uri = "https://example.com/two",
                title = "Two",
            ),
        ),
        lastRefreshedAt = 123,
    )
}
