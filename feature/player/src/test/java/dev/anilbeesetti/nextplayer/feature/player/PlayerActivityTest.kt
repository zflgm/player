package dev.anilbeesetti.nextplayer.feature.player

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerActivityTest {

    @Test
    fun currentUriWithExplicitPlaylistStartsNewPlaybackQueue() {
        assertFalse(
            shouldResumeExistingPlayback(
                returningFromBackground = false,
                isRequestedUriCurrent = true,
                hasExplicitPlaylist = true,
                hasNextMediaItem = true,
            ),
        )
    }

    @Test
    fun currentUriWithNextItemResumesExistingPlaylist() {
        assertTrue(
            shouldResumeExistingPlayback(
                returningFromBackground = false,
                isRequestedUriCurrent = true,
                hasExplicitPlaylist = false,
                hasNextMediaItem = true,
            ),
        )
    }

    @Test
    fun currentUriWithoutNextItemRebuildsAutomaticPlaylist() {
        assertFalse(
            shouldResumeExistingPlayback(
                returningFromBackground = false,
                isRequestedUriCurrent = true,
                hasExplicitPlaylist = false,
                hasNextMediaItem = false,
            ),
        )
    }

    @Test
    fun returningFromBackgroundAlwaysResumesExistingPlayback() {
        assertTrue(
            shouldResumeExistingPlayback(
                returningFromBackground = true,
                isRequestedUriCurrent = true,
                hasExplicitPlaylist = true,
                hasNextMediaItem = false,
            ),
        )
    }

    @Test
    fun autoAdvanceAndSwipeBothRecordTheItemToResumeFrom() {
        for (reason in listOf(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)) {
            assertEquals(
                7L to "content://two",
                playlistItemToMarkPlayed(
                    playlistId = 7L,
                    mediaItemUri = "content://two",
                    transitionReason = reason,
                ),
            )
        }
    }

    @Test
    fun singleVideoPlaybackHasNoPlaylistToReportTo() {
        assertNull(
            playlistItemToMarkPlayed(
                playlistId = null,
                mediaItemUri = "content://two",
                transitionReason = Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
            ),
        )
    }

    @Test
    fun repeatingTheSameItemIsNotProgress() {
        assertNull(
            playlistItemToMarkPlayed(
                playlistId = 7L,
                mediaItemUri = "content://two",
                transitionReason = Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT,
            ),
        )
    }

    @Test
    fun transitionWithoutAMediaItemIsIgnored() {
        assertNull(
            playlistItemToMarkPlayed(
                playlistId = 7L,
                mediaItemUri = null,
                transitionReason = Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
            ),
        )
    }

}
