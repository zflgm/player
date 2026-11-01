package dev.anilbeesetti.nextplayer.feature.player.ui.controls

import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.theme.NextPlayerTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ControlsTopViewTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun enteringGroupFocusesTheOnlyTopButtonWithoutTrappingNavigationToBack() {
        val groupFocusRequester = FocusRequester()
        var backClicked = false
        composeRule.setContent {
            NextPlayerTheme {
                ControlsTopView(
                    modifier = Modifier.focusRequester(groupFocusRequester),
                    title = "Video",
                    onBackClick = { backClicked = true },
                )
            }
        }
        val playlist = composeRule.onNodeWithTag(PLAYLIST_TEST_TAG)

        composeRule.runOnIdle { groupFocusRequester.requestFocus() }
        playlist.assertIsFocused().performKeyInput {
            pressKey(Key.DirectionLeft)
            pressKey(Key.DirectionCenter)
        }
        composeRule.runOnIdle { assertTrue(backClicked) }
        playlist.performKeyInput { pressKey(Key.DirectionRight) }
        playlist.assertIsFocused()
    }

    @Test
    fun decoderAudioAndSubtitleEntriesLeftTheTopBar() {
        composeRule.setContent {
            NextPlayerTheme {
                ControlsTopView(title = "Video")
            }
        }

        composeRule.onNodeWithContentDescription(composeRule.activity.getString(R.string.select_decoders))
            .assertDoesNotExist()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.decoder_mode_hardware)).assertDoesNotExist()
    }

    @Test
    fun queuePositionReadsUnderTheTitleWhileControlsAreUp() {
        val expected = composeRule.activity.getString(R.string.player_queue_position, 200, 3000)
        composeRule.setContent {
            NextPlayerTheme {
                ControlsTopView(
                    title = "Video",
                    queuePosition = expected,
                )
            }
        }

        composeRule.onNodeWithText("Video").assertIsDisplayed()
        composeRule.onNodeWithText(expected).assertIsDisplayed()
    }

    @Test
    fun aQueueOfOneSaysNothingAboutPosition() {
        composeRule.setContent {
            NextPlayerTheme {
                ControlsTopView(title = "Video")
            }
        }

        val anyPosition = composeRule.activity.getString(R.string.player_queue_position, 1, 1)
        composeRule.onNodeWithText(anyPosition).assertDoesNotExist()
    }
}
