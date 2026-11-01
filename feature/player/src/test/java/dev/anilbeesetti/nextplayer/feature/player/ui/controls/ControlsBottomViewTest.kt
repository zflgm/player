package dev.anilbeesetti.nextplayer.feature.player.ui.controls

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Label
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.extractor.metadata.Chapter
import androidx.media3.ui.compose.state.rememberProgressStateWithTickInterval
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.theme.NextPlayerTheme
import dev.anilbeesetti.nextplayer.feature.player.state.rememberChaptersState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ControlsBottomViewTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun repeatedKeyboardSeeksAccumulateBelowOneSecondWhilePaused() {
        val player = composeRule.runOnIdle { TestPlayer() }
        try {
            showControls(player)
            val seekbar = composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            seekbar.requestFocus().assertIsFocused()

            repeat(3) { index ->
                seekbar.performKeyInput { pressKey(Key.DirectionRight) }
                composeRule.runOnIdle {
                    assertEquals((index + 1) * 600L, player.currentPosition)
                    assertFalse(player.playWhenReady)
                }
            }
        } finally {
            composeRule.runOnIdle { player.release() }
        }
    }

    @Test
    fun pausedSeeksSelectChaptersAtExactSubsecondBoundaries() {
        val player = composeRule.runOnIdle { TestPlayer() }
        try {
            showControls(player)
            composeRule.runOnIdle { player.seekTo(1_500) }
            composeRule.onNodeWithText("Second").assertExists()

            composeRule.runOnIdle { player.seekTo(1_750) }
            composeRule.onNodeWithText("Third").assertExists()

            composeRule.runOnIdle { player.seekTo(1_749) }
            composeRule.onNodeWithText("Second").assertExists()
            composeRule.runOnIdle { assertFalse(player.playWhenReady) }
        } finally {
            composeRule.runOnIdle { player.release() }
        }
    }

    @Test
    fun timeLabelChangesOnWholeSecondsAndSwitchesToRemainingTime() {
        val player = composeRule.runOnIdle { TestPlayer() }
        try {
            showControls(player)
            composeRule.runOnIdle { player.seekTo(1_999) }
            composeRule.onNodeWithText("00:01 / 01:00").performTouchInput { click() }
            composeRule.onNodeWithText("-00:59 / 01:00").assertExists()

            composeRule.runOnIdle { player.seekTo(2_000) }
            composeRule.onNodeWithText("-00:58 / 01:00").performTouchInput { click() }
            composeRule.onNodeWithText("00:02 / 01:00").assertExists()
        } finally {
            composeRule.runOnIdle { player.release() }
        }
    }

    @Test
    fun upFromSeekbarEntersTimeThenRightVisitsChaptersAndSpeed() {
        val player = composeRule.runOnIdle { TestPlayer() }
        try {
            showControls(player)
            val seekbar = composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            seekbar.requestFocus().performKeyInput { pressKey(Key.DirectionUp) }
            composeRule.onNodeWithText("00:00 / 01:00")
                .assertIsFocused()
                .performKeyInput { pressKey(Key.DirectionCenter) }
            composeRule.onNodeWithText("-01:00 / 01:00")
                .assertIsFocused()
                .performKeyInput { pressKey(Key.DirectionRight) }
            composeRule.onNodeWithText("Opening")
                .assertIsFocused()
                .performKeyInput { pressKey(Key.DirectionRight) }
            composeRule.onNodeWithText("1.0")
                .assertIsFocused()
                .performKeyInput { pressKey(Key.DirectionDown) }
            seekbar.assertIsFocused().performKeyInput { pressKey(Key.DirectionUp) }
            composeRule.onNodeWithText("-01:00 / 01:00").assertIsFocused()
        } finally {
            composeRule.runOnIdle { player.release() }
        }
    }

    @Test
    fun portraitHidesLockAndPictureInPictureButtons() {
        val player = composeRule.runOnIdle { TestPlayer() }
        try {
            showControls(player, isPortrait = true, isPipSupported = true)
            composeRule.onNodeWithTag(LOCK_CONTROLS_TEST_TAG).assertDoesNotExist()
            composeRule.onNodeWithTag(PICTURE_IN_PICTURE_TEST_TAG).assertDoesNotExist()
            composeRule.onNodeWithTag(CONTENT_ROTATE_TEST_TAG).assertExists()
        } finally {
            composeRule.runOnIdle { player.release() }
        }
    }

    @Test
    fun landscapeShowsLockPictureInPictureAndRotateButtons() {
        val player = composeRule.runOnIdle { TestPlayer() }
        try {
            showControls(player, isPortrait = false, isPipSupported = true)
            composeRule.onNodeWithTag(LOCK_CONTROLS_TEST_TAG).assertExists()
            composeRule.onNodeWithTag(PICTURE_IN_PICTURE_TEST_TAG).assertExists()
            composeRule.onNodeWithTag(CONTENT_ROTATE_TEST_TAG).assertExists()
        } finally {
            composeRule.runOnIdle { player.release() }
        }
    }

    @Test
    fun rotateButtonInvokesCallback() {
        val player = composeRule.runOnIdle { TestPlayer() }
        var rotateClicks = 0
        try {
            showControls(player, onContentRotateClick = { rotateClicks++ })
            composeRule.onNodeWithTag(CONTENT_ROTATE_TEST_TAG).performClick()
            composeRule.runOnIdle { assertEquals(1, rotateClicks) }
        } finally {
            composeRule.runOnIdle { player.release() }
        }
    }

    @Test
    fun heldDragFeedsTheTargetTimeToTheChipBeforeSeeking() {
        val player = composeRule.runOnIdle { TestPlayer() }
        try {
            showControls(player)
            val scrub = composeRule.onNodeWithTag(SEEK_SCRUB_TEST_TAG)
            scrub.performTouchInput {
                down(Offset(1f, centerY))
                moveTo(Offset(width * 0.5f, centerY))
            }
            val previewedTime = timeChipText()
            composeRule.runOnIdle {
                assertNotEquals("00:00 / 01:00", previewedTime)
                assertEquals(0L, player.currentPosition)
            }

            scrub.performTouchInput { up() }
            composeRule.runOnIdle {
                assertEquals(previewedTime, timeChipText())
                assertTrue(player.currentPosition > 0L)
            }
        } finally {
            composeRule.runOnIdle { player.release() }
        }
    }

    @Test
    fun orientationAndSpeedButtonsShareTheGrownTapTarget() {
        val player = composeRule.runOnIdle { TestPlayer() }
        try {
            showControls(player, isPortrait = true)
            val orientation = composeRule
                .onNodeWithContentDescription(composeRule.activity.getString(R.string.screen_rotation))
                .fetchSemanticsNode().boundsInRoot.width
            val speed = composeRule
                .onNodeWithContentDescription(composeRule.activity.getString(R.string.select_playback_speed))
                .fetchSemanticsNode().boundsInRoot.width

            assertEquals(56f, orientation, 0.5f)
            assertEquals(orientation, speed, 0.5f)
        } finally {
            composeRule.runOnIdle { player.release() }
        }
    }

    @Test
    fun decoderAudioAndSubtitleButtonsFollowTheLoopButton() {
        val player = composeRule.runOnIdle { TestPlayer() }
        var decoderClicks = 0
        try {
            showControls(player, onDecoderClick = { decoderClicks++ })
            val rotateLeft = composeRule.onNodeWithTag(CONTENT_ROTATE_TEST_TAG)
                .fetchSemanticsNode().boundsInRoot.left
            val decoder = composeRule.onNodeWithTag(DECODER_TEST_TAG)
            decoder.assertIsDisplayed().performClick()
            composeRule.onNodeWithTag(AUDIO_TRACK_TEST_TAG).assertIsDisplayed()
            composeRule.onNodeWithTag(SUBTITLE_TRACK_TEST_TAG).assertIsDisplayed()

            assertTrue(decoder.fetchSemanticsNode().boundsInRoot.left > rotateLeft)
            composeRule.runOnIdle { assertEquals(1, decoderClicks) }
        } finally {
            composeRule.runOnIdle { player.release() }
        }
    }

    private fun timeChipText(): String =
        composeRule.onNodeWithTag(TIME_DISPLAY_TEST_TAG)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .joinToString(separator = "") { it.text }

    private fun showControls(
        player: Player,
        isPortrait: Boolean = false,
        isPipSupported: Boolean = false,
        onContentRotateClick: () -> Unit = {},
        onDecoderClick: () -> Unit = {},
    ) {
        composeRule.setContent {
            NextPlayerTheme {
                val progress = rememberProgressStateWithTickInterval(player)
                var showRemainingTime by remember { mutableStateOf(false) }
                // The preview is held one level up in production, where the middle of the screen reads it too.
                var scrubbingPositionMs by remember { mutableStateOf<Float?>(null) }
                ControlsBottomView(
                    player = player,
                    progressState = progress,
                    chaptersState = rememberChaptersState(player, progress),
                    controlsAlignment = Alignment.Start,
                    isPipSupported = isPipSupported,
                    isPortrait = isPortrait,
                    showRemainingTime = showRemainingTime,
                    videoDecoderMode = null,
                    onToggleTimeDisplay = { showRemainingTime = !showRemainingTime },
                    onChaptersClick = {},
                    onLockControlsClick = {},
                    onPictureInPictureClick = {},
                    onContentRotateClick = onContentRotateClick,
                    contentRotated = false,
                    onPlaybackSpeedClick = {},
                    onDecoderClick = onDecoderClick,
                    onAudioClick = {},
                    onSubtitleClick = {},
                    onSeek = player::seekTo,
                    onSeekEnd = {},
                    scrubbingPositionMs = scrubbingPositionMs,
                    onScrubbing = { scrubbingPositionMs = it },
                )
            }
        }
    }

    private class TestPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        private val chapters = listOf(0L to "Opening", 1_500L to "Second", 1_750L to "Third").map { (start, title) ->
            Chapter.Builder().setStartTimeMs(start).setTitle(Label(null, title)).build()
        }
        private val tracks = Tracks(
            listOf(
                Tracks.Group(
                    TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setMetadata(Metadata(chapters)).build()),
                    false,
                    intArrayOf(C.FORMAT_HANDLED),
                    booleanArrayOf(true),
                ),
            ),
        )
        private var state = State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder()
                    .addAll(
                        Player.COMMAND_GET_TIMELINE,
                        Player.COMMAND_GET_TRACKS,
                        Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_SET_SPEED_AND_PITCH,
                    )
                    .build(),
            )
            .setPlaylist(listOf(MediaItemData.Builder("chapters").setDurationUs(60_000_000).setTracks(tracks).build()))
            .setContentPositionMs(0)
            .setPlaybackState(Player.STATE_READY)
            .build()

        override fun getState(): State = state

        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
            state = state.buildUpon()
                .setContentPositionMs(positionMs)
                .setPositionDiscontinuity(Player.DISCONTINUITY_REASON_SEEK, positionMs)
                .build()
            return Futures.immediateVoidFuture()
        }
    }
}
