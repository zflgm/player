package dev.anilbeesetti.nextplayer.feature.player.ui.controls

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onNodeWithTag
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.extractor.metadata.Chapter
import dev.anilbeesetti.nextplayer.core.ui.theme.NextPlayerTheme
import dev.anilbeesetti.nextplayer.feature.player.LocalControlsVisibilityState
import dev.anilbeesetti.nextplayer.feature.player.LocalUseMaterialYouControls
import dev.anilbeesetti.nextplayer.feature.player.state.ControlsVisibilityState
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlayerSeekbarTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun bothStylesTickOnlyWhenScrubbingAcrossChapters() {
        val ticks = mutableListOf<HapticFeedbackType>()
        val haptics = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                ticks += hapticFeedbackType
            }
        }
        var position by mutableFloatStateOf(100f)
        var materialControls by mutableStateOf(false)
        var chapters by mutableStateOf(listOf(0L, 400L, 700L).map { Chapter.Builder().setStartTimeMs(it).build() })
        composeRule.setContent {
            NextPlayerTheme {
                CompositionLocalProvider(
                    LocalHapticFeedback provides haptics,
                    LocalUseMaterialYouControls provides materialControls,
                ) {
                    PlayerSeekbar(
                        position = position,
                        duration = 1_000f,
                        chapters = chapters,
                        onSeek = { position = it },
                        onSeekFinished = {},
                    )
                }
            }
        }

        val slider = composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
        for (material in listOf(false, true)) {
            composeRule.runOnIdle {
                materialControls = material
                position = 100f
                ticks.clear()
            }
            slider.performTouchInput {
                down(Offset(width * 0.1f, centerY))
                moveTo(Offset(width * 0.2f, centerY))
            }
            composeRule.runOnIdle { assertEquals(0, ticks.size) }
            slider.performTouchInput { moveTo(Offset(width * 0.5f, centerY)) }
            composeRule.runOnIdle { assertEquals(listOf(HapticFeedbackType.SegmentTick), ticks) }
            slider.performTouchInput { moveTo(Offset(width * 0.6f, centerY)) }
            composeRule.runOnIdle { assertEquals(1, ticks.size) }
            slider.performTouchInput {
                moveTo(Offset(width * 0.2f, centerY))
                up()
            }
            composeRule.runOnIdle {
                assertEquals(2, ticks.size)
                position = 800f
            }
            composeRule.waitForIdle()
            composeRule.runOnIdle { assertEquals(2, ticks.size) }
            slider.performTouchInput {
                down(Offset(width * 0.8f, centerY))
                moveTo(Offset(width * 0.9f, centerY))
                up()
            }
            composeRule.runOnIdle { assertEquals(2, ticks.size) }
        }

        composeRule.runOnIdle {
            chapters = emptyList()
            ticks.clear()
        }
        slider.performTouchInput {
            down(Offset(width * 0.9f, centerY))
            moveTo(Offset(width * 0.1f, centerY))
            up()
        }
        composeRule.runOnIdle { assertEquals(0, ticks.size) }
    }

    @Test
    fun holdingTheTrackPreviewsAndReleaseCommitsOnce() {
        val sought = mutableListOf<Float>()
        val previews = mutableListOf<Float?>()
        var finished = 0
        var position by mutableFloatStateOf(100f)
        composeRule.setContent {
            NextPlayerTheme {
                PlayerSeekbar(
                    position = position,
                    duration = 1_000f,
                    chapters = emptyList(),
                    onSeek = { sought += it; position = it },
                    onSeekFinished = { finished++ },
                    onScrubbing = { previews += it },
                )
            }
        }

        val scrub = composeRule.onNodeWithTag(SEEK_SCRUB_TEST_TAG)
        scrub.performTouchInput {
            down(Offset(width * 0.3f, centerY))
            moveTo(Offset(width * 0.6f, centerY))
        }
        composeRule.runOnIdle {
            assertEquals(emptyList<Float>(), sought)
            assertEquals(0, finished)
            assertEquals(100f, position)
            val previewed = previews.mapNotNull { it }
            assertTrue(previewed.isNotEmpty())
            assertTrue(previewed.all { it > 100f && it <= 1_000f })
        }

        scrub.performTouchInput { up() }
        composeRule.runOnIdle {
            assertEquals(1, sought.size)
            assertEquals(1, finished)
            assertNull(previews.last())
            val committed = sought.single()
            assertTrue(previews.contains(committed))
            assertTrue(committed > 100f)
        }
    }

    @Test
    fun scrubbingStaysInsideTheTrack() {
        var position by mutableFloatStateOf(100f)
        val previews = mutableListOf<Float?>()
        composeRule.setContent {
            NextPlayerTheme {
                PlayerSeekbar(
                    position = position,
                    duration = 1_000f,
                    chapters = emptyList(),
                    onSeek = { position = it },
                    onSeekFinished = {},
                    onScrubbing = { previews += it },
                )
            }
        }

        val scrub = composeRule.onNodeWithTag(SEEK_SCRUB_TEST_TAG)
        scrub.performTouchInput {
            down(Offset(width * 0.9f, centerY))
            moveTo(Offset(0f, centerY))
        }
        composeRule.runOnIdle { assertEquals(0f, previews.last()!!) }
        scrub.performTouchInput {
            up()
            down(Offset(width * 0.1f, centerY))
            moveTo(Offset(width * 1.5f, centerY))
        }
        composeRule.runOnIdle { assertEquals(1_000f, previews.last()!!) }
    }

    /** A press and release without travelling is still a jump: the user aimed at that spot. */
    @Test
    fun tapOnTheTrackSeeksToTheTappedFraction() {
        var position by mutableFloatStateOf(0f)
        var finished = 0
        composeRule.setContent {
            NextPlayerTheme {
                PlayerSeekbar(
                    position = position,
                    duration = 1_000f,
                    chapters = emptyList(),
                    onSeek = { position = it },
                    onSeekFinished = { finished++ },
                )
            }
        }

        composeRule.onNodeWithTag(SEEK_SCRUB_TEST_TAG)
            .performTouchInput {
                down(Offset(width * 0.25f, centerY))
                up()
            }
        composeRule.runOnIdle {
            assertTrue("position=$position", position in 249f..251f)
            assertEquals(1, finished)
        }
    }

    /**
     * The bar is drawn inside the controls, so an auto hide firing mid drag takes the composable that is running
     * the gesture away with it: the finger lifts and nothing is committed. Holding the bar holds the controls.
     */
    @Test
    fun holdingTheBarKeepsTheControlsUpPastTheAutoHideTimeout() = runTest {
        val player = PlayingPlayer()
        // backgroundScope, because the job a held bar leaves running is the one under test: it waits forever.
        val visibility = ControlsVisibilityState(player = player, hideAfter = 1.seconds, scope = backgroundScope)
        try {
            showSeekbar(visibility)
            visibility.showControls()

            composeRule.onNodeWithTag(SEEK_SCRUB_TEST_TAG).performTouchInput {
                down(Offset(width * 0.3f, centerY))
                moveTo(Offset(width * 0.6f, centerY))
            }
            advanceTimeBy(5_000)

            composeRule.runOnIdle { assertTrue(visibility.controlsVisible) }
        } finally {
            player.release()
        }
    }

    /** And only for as long as the finger is down: the timer is handed back, not cancelled outright. */
    @Test
    fun releasingTheBarHandsTheAutoHideTimerBack() = runTest {
        val player = PlayingPlayer()
        val visibility = ControlsVisibilityState(player = player, hideAfter = 1.seconds, scope = this)
        try {
            showSeekbar(visibility)
            visibility.showControls()

            val scrub = composeRule.onNodeWithTag(SEEK_SCRUB_TEST_TAG)
            scrub.performTouchInput {
                down(Offset(width * 0.3f, centerY))
                moveTo(Offset(width * 0.6f, centerY))
            }
            scrub.performTouchInput { up() }
            advanceTimeBy(5_000)

            composeRule.runOnIdle { assertFalse(visibility.controlsVisible) }
        } finally {
            player.release()
        }
    }

    private fun showSeekbar(visibility: ControlsVisibilityState) {
        var position by mutableFloatStateOf(100f)
        composeRule.setContent {
            NextPlayerTheme {
                CompositionLocalProvider(LocalControlsVisibilityState provides visibility) {
                    PlayerSeekbar(
                        position = position,
                        duration = 1_000f,
                        chapters = emptyList(),
                        onSeek = { position = it },
                        onSeekFinished = {},
                    )
                }
            }
        }
    }

    /** Playing, because the auto hide only ever hides while something is playing. */
    private class PlayingPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        private val state = State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder()
                    .addAll(
                        Player.COMMAND_GET_TIMELINE,
                        Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    )
                    .build(),
            )
            .setPlaylist(listOf(MediaItemData.Builder(0).setDurationUs(60_000_000).build()))
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(Player.STATE_READY)
            .build()

        override fun getState(): State = state
    }
}
