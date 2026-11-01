package dev.anilbeesetti.nextplayer.feature.player.ui

import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.anilbeesetti.nextplayer.feature.player.state.ControlBarHeights
import dev.anilbeesetti.nextplayer.feature.player.state.ControlsVisibilityState
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HandleSingleTapTest {

    /** Same numbers the layout reports on a portrait phone: a title row and a seekbar row. */
    private fun controlBarHeights(top: Int = 200, bottom: Int = 300): ControlBarHeights =
        ControlBarHeights().also {
            it.top = top
            it.bottom = bottom
        }

    @Test
    fun portraitTapOnVideoStripPausesAndShowsControls() = runTest {
        val player = TestPlayer(playing = true)
        try {
            val visibility = controlsVisibilityState(player)

            handleSingleTap(
                isPortrait = true,
                tapY = 500f,
                screenHeight = SCREEN_HEIGHT,
                controlBarHeights = controlBarHeights(),
                player = player,
                controlsVisibilityState = visibility,
            )

            assertEquals(false, player.isPlaying)
            assertEquals(true, visibility.controlsVisible)
        } finally {
            player.release()
        }
    }

    @Test
    fun portraitTapResumesWhenPaused() = runTest {
        val player = TestPlayer(playing = false)
        try {
            val visibility = controlsVisibilityState(player)

            handleSingleTap(
                isPortrait = true,
                tapY = 500f,
                screenHeight = SCREEN_HEIGHT,
                controlBarHeights = controlBarHeights(),
                player = player,
                controlsVisibilityState = visibility,
            )

            assertEquals(true, player.isPlaying)
        } finally {
            player.release()
        }
    }

    @Test
    fun portraitTapOverTopBarHidesControlsWithoutPausing() = runTest {
        val player = TestPlayer(playing = true)
        try {
            val visibility = controlsVisibilityState(player)
            assertEquals(true, visibility.controlsVisible)

            handleSingleTap(
                isPortrait = true,
                tapY = 100f,
                screenHeight = SCREEN_HEIGHT,
                controlBarHeights = controlBarHeights(),
                player = player,
                controlsVisibilityState = visibility,
            )

            assertEquals(false, visibility.controlsVisible)
            assertEquals(true, player.isPlaying)
        } finally {
            player.release()
        }
    }

    @Test
    fun portraitTapBelowSeekbarHidesControlsWithoutPausing() = runTest {
        val player = TestPlayer(playing = true)
        try {
            val visibility = controlsVisibilityState(player)

            handleSingleTap(
                isPortrait = true,
                tapY = 800f,
                screenHeight = SCREEN_HEIGHT,
                controlBarHeights = controlBarHeights(),
                player = player,
                controlsVisibilityState = visibility,
            )

            assertEquals(false, visibility.controlsVisible)
            assertEquals(true, player.isPlaying)
        } finally {
            player.release()
        }
    }

    /** The first tap on the strip has to pause; making the user tap twice for one pause reads as a dead zone. */
    @Test
    fun portraitTapWhileHiddenPausesAndShowsControls() = runTest {
        val player = TestPlayer(playing = true)
        try {
            val visibility = controlsVisibilityState(player)
            visibility.hideControls()

            // Over the bottom bar's own position: folded controls are what he reached for, so no pause.
            handleSingleTap(
                isPortrait = true,
                tapY = 900f,
                screenHeight = SCREEN_HEIGHT,
                controlBarHeights = controlBarHeights(),
                player = player,
                controlsVisibilityState = visibility,
            )
            assertEquals(true, visibility.controlsVisible)
            assertEquals(true, player.isPlaying)

            visibility.hideControls()
            handleSingleTap(
                isPortrait = true,
                tapY = 500f,
                screenHeight = SCREEN_HEIGHT,
                controlBarHeights = controlBarHeights(),
                player = player,
                controlsVisibilityState = visibility,
            )
            assertEquals(true, visibility.controlsVisible)
            assertEquals(false, player.isPlaying)

            // With the controls already up, the same strip tap still pauses - here it resumes.
            handleSingleTap(
                isPortrait = true,
                tapY = 500f,
                screenHeight = SCREEN_HEIGHT,
                controlBarHeights = controlBarHeights(),
                player = player,
                controlsVisibilityState = visibility,
            )
            assertEquals(true, player.isPlaying)
        } finally {
            player.release()
        }
    }

    @Test
    fun landscapeTapOnlyTogglesControls() = runTest {
        val player = TestPlayer(playing = true)
        try {
            val visibility = controlsVisibilityState(player)
            assertEquals(true, visibility.controlsVisible)

            handleSingleTap(
                isPortrait = false,
                tapY = 100f,
                screenHeight = SCREEN_HEIGHT,
                controlBarHeights = controlBarHeights(),
                player = player,
                controlsVisibilityState = visibility,
            )

            assertEquals(false, visibility.controlsVisible)
            assertEquals(true, player.isPlaying)

            handleSingleTap(
                isPortrait = false,
                tapY = 100f,
                screenHeight = SCREEN_HEIGHT,
                controlBarHeights = controlBarHeights(),
                player = player,
                controlsVisibilityState = visibility,
            )

            assertEquals(true, visibility.controlsVisible)
        } finally {
            player.release()
        }
    }

    @Test
    fun tapBoundsAreTheInnerThirds() {
        val heights = controlBarHeights(top = 200, bottom = 300)

        // A third of the 1000px screen belongs to each edge: [0, 333) and (667, 1000] fold the controls away,
        // and only the strip between them pauses.
        assertEquals(true, isControlBarTapRegion(tapY = 333f, screenHeight = SCREEN_HEIGHT, controlBarHeights = heights))
        assertEquals(false, isControlBarTapRegion(tapY = 334f, screenHeight = SCREEN_HEIGHT, controlBarHeights = heights))
        assertEquals(false, isControlBarTapRegion(tapY = 666f, screenHeight = SCREEN_HEIGHT, controlBarHeights = heights))
        assertEquals(true, isControlBarTapRegion(tapY = 667f, screenHeight = SCREEN_HEIGHT, controlBarHeights = heights))
    }

    /** A bar taller than its third keeps its whole extent, so a control never sits behind a pause tap. */
    @Test
    fun measuredBarsAreAFloorNotACeiling() {
        val tallBottom = controlBarHeights(top = 200, bottom = 500)

        assertEquals(true, isControlBarTapRegion(tapY = 550f, screenHeight = SCREEN_HEIGHT, controlBarHeights = tallBottom))
        assertEquals(false, isControlBarTapRegion(tapY = 400f, screenHeight = SCREEN_HEIGHT, controlBarHeights = tallBottom))
    }

    /** Before the first layout pass nothing is measured, and the thirds still hold. */
    @Test
    fun unmeasuredBarsStillZoneInThirds() {
        val heights = controlBarHeights(top = 0, bottom = 0)

        assertEquals(true, isControlBarTapRegion(tapY = 0f, screenHeight = SCREEN_HEIGHT, controlBarHeights = heights))
        assertEquals(false, isControlBarTapRegion(tapY = 500f, screenHeight = SCREEN_HEIGHT, controlBarHeights = heights))
        assertEquals(true, isControlBarTapRegion(tapY = 999f, screenHeight = SCREEN_HEIGHT, controlBarHeights = heights))
    }

    private fun TestScope.controlsVisibilityState(player: Player): ControlsVisibilityState =
        ControlsVisibilityState(player = player, hideAfter = 3.seconds, scope = this)

    private class TestPlayer(playing: Boolean) : SimpleBasePlayer(Looper.getMainLooper()) {
        private var state = State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder()
                    .addAll(
                        Player.COMMAND_GET_TIMELINE,
                        Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_PLAY_PAUSE,
                    )
                    .build(),
            )
            .setPlaylist(List(1) { MediaItemData.Builder(0).setDurationUs(60_000_000).build() })
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(Player.STATE_READY)
            .build()

        override fun getState(): State = state

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            state = state
                .buildUpon()
                .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .build()
            invalidateState()
            shadowOf(Looper.getMainLooper()).idle()
            return Futures.immediateVoidFuture()
        }
    }

    private companion object {
        const val SCREEN_HEIGHT = 1000
    }
}
