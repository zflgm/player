package dev.anilbeesetti.nextplayer.feature.player.state

import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Constraints
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class VideoTransformStateTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rotateCanvasTogglesBetweenUprightAndHalfTurn() {
        val player = TestPlayer(playerState(2))
        try {
            val state = VideoTransformState(player, enableZoomGesture = false, enablePanGesture = false, onEvent = {})
            assertEquals(0, state.rotationDegrees)

            state.rotateCanvas()
            assertEquals(180, state.rotationDegrees)

            state.rotateCanvas()
            assertEquals(0, state.rotationDegrees)
        } finally {
            player.release()
        }
    }

    @Test
    fun rotationResetsWhenPlaybackMovesToAnotherItem() = runTest {
        val player = TestPlayer(playerState(2))
        val state = VideoTransformState(player, enableZoomGesture = false, enablePanGesture = false, onEvent = {})
        val observer = backgroundScope.launch { state.observe() }
        try {
            runCurrent()
            state.rotateCanvas()
            assertEquals(180, state.rotationDegrees)

            player.update(player.currentState.buildUpon().setCurrentMediaItemIndex(1).setContentPositionMs(0).build())
            runCurrent()
            assertEquals(0, state.rotationDegrees)
        } finally {
            observer.cancelAndJoin()
            player.release()
        }
    }

    private val viewport = Constraints(maxWidth = 1000, maxHeight = 600)

    @Test
    fun pinchZoomGrowsAndStopsAtMaximum() {
        val player = TestPlayer(playerState(2))
        try {
            val state = VideoTransformState(player, onEvent = {})
            assertEquals(1f, state.zoom, 0.001f)

            repeat(3) { state.onZoomPanGesture(viewport, Offset.Zero, 2f) }

            assertEquals(4f, state.zoom, 0.001f)
            assertTrue(state.isZooming)
        } finally {
            player.release()
        }
    }

    @Test
    fun pinchZoomShrinksAndStopsAtMinimum() {
        val player = TestPlayer(playerState(2))
        try {
            val state = VideoTransformState(player, onEvent = {})

            repeat(4) { state.onZoomPanGesture(viewport, Offset.Zero, 0.5f) }

            assertEquals(0.25f, state.zoom, 0.001f)
        } finally {
            player.release()
        }
    }

    @Test
    fun panFollowsZoomAndStaysInsideZoomedBounds() {
        val player = TestPlayer(playerState(2))
        try {
            val state = VideoTransformState(player, onEvent = {})

            state.onZoomPanGesture(viewport, Offset.Zero, 2f)
            assertEquals(Offset.Zero, state.offset)

            state.onZoomPanGesture(viewport, Offset(5000f, 5000f), 1f)

            assertEquals(Offset(500f, 300f), state.offset)
        } finally {
            player.release()
        }
    }

    @Test
    fun gesturesAreIgnoredWhenZoomDisabled() {
        val player = TestPlayer(playerState(2))
        try {
            val state = VideoTransformState(player, enableZoomGesture = false, onEvent = {})

            state.onZoomPanGesture(viewport, Offset(100f, 100f), 2f)

            assertEquals(1f, state.zoom, 0.001f)
            assertEquals(Offset.Zero, state.offset)
            assertEquals(false, state.isZooming)
        } finally {
            player.release()
        }
    }

    @OptIn(UnstableApi::class)
    @Test
    fun changingGestureSettingsRebuildsTheStateAndItsObserver() {
        val player = TestPlayer(playerState(2))
        val zoomEnabled = mutableStateOf(true)
        val built = mutableListOf<VideoTransformState>()
        try {
            composeRule.setContent {
                built += rememberVideoTransformState(
                    player = player,
                    enableZoomGesture = zoomEnabled.value,
                    enablePanGesture = true,
                    onEvent = {},
                )
            }
            composeRule.waitForIdle()
            assertEquals(1, built.distinct().size)

            composeRule.runOnIdle { zoomEnabled.value = false }
            composeRule.waitForIdle()

            assertEquals("a new settings value must build a new state", 2, built.distinct().size)
            val rebuilt = built.last()
            rebuilt.rotateCanvas()
            assertEquals(180, rebuilt.rotationDegrees)

            // Only an observer attached to the *rebuilt* instance can clear its rotation.
            player.update(player.currentState.buildUpon().setCurrentMediaItemIndex(1).build())
            composeRule.waitForIdle()
            assertEquals(0, rebuilt.rotationDegrees)
        } finally {
            player.release()
        }
    }

    private fun playerState(itemCount: Int): SimpleBasePlayer.State = SimpleBasePlayer.State.Builder()
        .setAvailableCommands(
            Player.Commands.Builder()
                .addAll(
                    Player.COMMAND_GET_TIMELINE,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_NEXT,
                )
                .build(),
        )
        .setPlaylist(List(itemCount) { SimpleBasePlayer.MediaItemData.Builder("$it").setDurationUs(60_000_000).build() })
        .setCurrentMediaItemIndex(0)
        .setPlaybackState(Player.STATE_READY)
        .build()

    private class TestPlayer(var currentState: State) : SimpleBasePlayer(Looper.getMainLooper()) {
        override fun getState(): State = currentState

        fun update(state: State) {
            currentState = state
            invalidateState()
            ShadowLooper.idleMainLooper()
        }
    }
}
