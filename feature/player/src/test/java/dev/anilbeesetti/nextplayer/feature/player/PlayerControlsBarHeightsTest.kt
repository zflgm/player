package dev.anilbeesetti.nextplayer.feature.player

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.IntSize
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.state.rememberProgressStateWithTickInterval
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.anilbeesetti.nextplayer.core.model.PlayerPreferences
import dev.anilbeesetti.nextplayer.core.ui.theme.NextPlayerTheme
import dev.anilbeesetti.nextplayer.feature.player.state.ControlBarHeights
import dev.anilbeesetti.nextplayer.feature.player.state.ControlsVisibilityState
import dev.anilbeesetti.nextplayer.feature.player.state.rememberChaptersState
import dev.anilbeesetti.nextplayer.feature.player.state.rememberSeekGestureState
import dev.anilbeesetti.nextplayer.feature.player.state.rememberVideoTransformState
import dev.anilbeesetti.nextplayer.feature.player.ui.isControlBarTapRegion
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderMode
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The portrait tap zones are only as good as the bar heights the layout reports, and those depend on
 * the system bar insets and the seekbar rows - so this measures the real layout instead of a fixture.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
class PlayerControlsBarHeightsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun visibleBarsOwnTheEdgesAndLeaveTheVideoStripTappable() {
        val player = composeRule.runOnIdle { TestPlayer() }
        val controlBarHeights = ControlBarHeights()
        var windowSize = IntSize.Zero
        try {
            composeRule.setContent {
                NextPlayerTheme {
                    val scope = rememberCoroutineScope()
                    val progressState = rememberProgressStateWithTickInterval(player)
                    val controlsVisibilityState = remember(player, scope) {
                        ControlsVisibilityState(player = player, hideAfter = 3.seconds, scope = scope)
                    }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .onGloballyPositioned { windowSize = it.size },
                    ) {
                        PlayerControls(
                            player = player,
                            title = "Sample video",
                            playerPreferences = PlayerPreferences(),
                            videoDecoderMode = DecoderMode.HARDWARE,
                            controlsVisibilityState = controlsVisibilityState,
                            seekGestureState = rememberSeekGestureState(
                                player = player,
                                enableSeekGesture = false,
                            ),
                            videoTransformState = rememberVideoTransformState(
                                player = player,
                                enableZoomGesture = false,
                                enablePanGesture = false,
                            ),
                            progressState = progressState,
                            chaptersState = rememberChaptersState(player, progressState),
                            isPipSupported = false,
                            controlBarHeights = controlBarHeights,
                            onShowOverlay = {},
                            onBackClick = {},
                            onToggleTimeDisplay = {},
                            onPictureInPictureClick = {},
                        )
                    }
                }
            }

            val screenHeight = windowSize.height
            assertTrue("bars leave no strip: $controlBarHeights in $windowSize", controlBarHeights.top + controlBarHeights.bottom < screenHeight)
            // The reported heights must be the bars themselves, not the whole screen.
            assertTrue(controlBarHeights.top in 1 until screenHeight / 2)
            assertTrue(controlBarHeights.bottom in 1 until screenHeight / 2)

            // The middle of each bar is a fold-away tap, the middle of the strip in between is a pause tap.
            val topBarMiddle = controlBarHeights.top / 2f
            val bottomBarMiddle = screenHeight - controlBarHeights.bottom / 2f
            val stripMiddle = (controlBarHeights.top + (screenHeight - controlBarHeights.bottom)) / 2f
            assertEquals(true, isControlBarTapRegion(topBarMiddle, screenHeight, controlBarHeights))
            assertEquals(true, isControlBarTapRegion(bottomBarMiddle, screenHeight, controlBarHeights))
            assertEquals(false, isControlBarTapRegion(stripMiddle, screenHeight, controlBarHeights))
        } finally {
            composeRule.runOnIdle { player.release() }
        }
    }
}

private class TestPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
    private var state = State.Builder()
        .setAvailableCommands(
            Player.Commands.Builder()
                .addAll(
                    Player.COMMAND_GET_TIMELINE,
                    Player.COMMAND_GET_TRACKS,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_PLAY_PAUSE,
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                )
                .build(),
        )
        .setPlaylist(List(1) { MediaItemData.Builder(it).setDurationUs(60_000_000).build() })
        .setContentPositionMs(0)
        .setPlaybackState(Player.STATE_READY)
        .build()

    override fun getState(): State = state

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        state = state
            .buildUpon()
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build()
        return Futures.immediateVoidFuture()
    }
}
