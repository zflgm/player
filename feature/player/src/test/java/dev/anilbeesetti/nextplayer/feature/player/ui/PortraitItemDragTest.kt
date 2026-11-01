package dev.anilbeesetti.nextplayer.feature.player.ui

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import dev.anilbeesetti.nextplayer.feature.player.state.ItemSwipeDirection.NEXT
import dev.anilbeesetti.nextplayer.feature.player.state.ItemSwipeDirection.PREVIOUS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PortraitItemDragTest {

    /** A three item playlist, standing on the middle one: there is something on both sides of it. */
    private fun playlistPlayer(
        currentIndex: Int = 1,
        titles: List<String?> = listOf("first", "middle", "last"),
    ): Player = object : SimpleBasePlayer(Looper.getMainLooper()) {
        private val state = State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder()
                    .addAll(
                        Player.COMMAND_GET_TIMELINE,
                        Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    )
                    .build(),
            )
            .setPlaylist(
                titles.mapIndexed { index, title ->
                    MediaItemData.Builder(index)
                        .setMediaItem(
                            MediaItem.Builder()
                                .setMediaId("item$index")
                                .setMediaMetadata(
                                    MediaMetadata.Builder().setTitle(title).build(),
                                )
                                .build(),
                        )
                        .setDurationUs(60_000_000)
                        .build()
                },
            )
            .setCurrentMediaItemIndex(currentIndex)
            .setPlaybackState(Player.STATE_READY)
            .build()

        override fun getState(): State = state
    }

    @Test
    fun swipePastThresholdWithANeighbourIsTheItemItIsHeadingFor() {
        val player = playlistPlayer()
        try {
            assertEquals(NEXT, swipeToNeighbour(player, verticalDragDistance = -200f, screenHeight = 1000f))
            assertEquals(PREVIOUS, swipeToNeighbour(player, verticalDragDistance = 200f, screenHeight = 1000f))
        } finally {
            player.release()
        }
    }

    @Test
    fun shortSwipeChangesNothing() {
        val player = playlistPlayer()
        try {
            assertNull(swipeToNeighbour(player, verticalDragDistance = -149f, screenHeight = 1000f))
            assertNull(swipeToNeighbour(player, verticalDragDistance = 149f, screenHeight = 1000f))
        } finally {
            player.release()
        }
    }

    /** The last item has nothing after it, so a full swipe upwards neither changes nor commits anything. */
    @Test
    fun swipePastTheEndOfTheListChangesNothing() {
        val player = playlistPlayer(currentIndex = 2)
        try {
            assertNull(swipeToNeighbour(player, verticalDragDistance = -200f, screenHeight = 1000f))
            assertEquals(PREVIOUS, swipeToNeighbour(player, verticalDragDistance = 200f, screenHeight = 1000f))
        } finally {
            player.release()
        }
    }

    @Test
    fun firstItemHasNothingBeforeIt() {
        val player = playlistPlayer(currentIndex = 0)
        try {
            assertNull(swipeToNeighbour(player, verticalDragDistance = 200f, screenHeight = 1000f))
            assertEquals(NEXT, swipeToNeighbour(player, verticalDragDistance = -200f, screenHeight = 1000f))
        } finally {
            player.release()
        }
    }

    @Test
    fun panelCarriesTheTitleOfTheNeighbourInTheDraggedDirection() {
        val player = playlistPlayer()
        try {
            assertEquals("last", draggedItemTitle(player, verticalDragDistance = -1f))
            assertEquals("first", draggedItemTitle(player, verticalDragDistance = 1f))
        } finally {
            player.release()
        }
    }

    /** Half way through a reversal the finger is on zero: nothing is being dragged towards, so nothing is named. */
    @Test
    fun stillDragNamesNoItem() {
        val player = playlistPlayer()
        try {
            assertNull(draggedItemTitle(player, verticalDragDistance = 0f))
            assertNull(draggedNeighbour(player, verticalDragDistance = 0f))
        } finally {
            player.release()
        }
    }

    /** The sign of the drag alone picks the way the panel names, with no threshold involved. */
    @Test
    fun dragBetweenItemsIsNamedByItsDirection() {
        val player = playlistPlayer()
        try {
            assertEquals(NEXT, draggedNeighbour(player, verticalDragDistance = -1f))
            assertEquals(PREVIOUS, draggedNeighbour(player, verticalDragDistance = 1f))
        } finally {
            player.release()
        }
    }

    /** Past an end of the list there is no item to promise, so the panel gets no direction to show either. */
    @Test
    fun dragTowardsAnEndOfTheListHasNoDirection() {
        val player = playlistPlayer(currentIndex = 2)
        try {
            assertNull(draggedNeighbour(player, verticalDragDistance = -1f))
            assertEquals(PREVIOUS, draggedNeighbour(player, verticalDragDistance = 1f))
        } finally {
            player.release()
        }
    }

    @Test
    fun panelStaysQuietAtTheEndOfTheList() {
        val player = playlistPlayer(currentIndex = 2)
        try {
            assertNull(draggedItemTitle(player, verticalDragDistance = -1f))
            assertEquals("middle", draggedItemTitle(player, verticalDragDistance = 1f))
        } finally {
            player.release()
        }
    }

    /** Files with no title still get the panel, just without a line of text. */
    @Test
    fun untitledNeighbourNamesNoItem() {
        val player = playlistPlayer(titles = listOf("first", "middle", null))
        try {
            assertNull(draggedItemTitle(player, verticalDragDistance = -1f))
            assertEquals(NEXT, draggedNeighbour(player, verticalDragDistance = -1f))
        } finally {
            player.release()
        }
    }
}
