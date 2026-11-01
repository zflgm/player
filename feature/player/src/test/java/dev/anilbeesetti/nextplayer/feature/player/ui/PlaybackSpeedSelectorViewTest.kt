package dev.anilbeesetti.nextplayer.feature.player.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.theme.NextPlayerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlaybackSpeedSelectorViewTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun sheetShowsQuarterSpeedPresetsAndNothingElse() {
        val player = composeRule.runOnIdle { TestPlayer() }
        try {
            composeRule.setContent {
                NextPlayerTheme {
                    Box {
                        PlaybackSpeedSelectorView(
                            show = true,
                            player = player,
                        )
                    }
                }
            }
            listOf("0.25x", "0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "1.75x", "2.0x").forEach { label ->
                composeRule.onNodeWithText(label).assertExists()
            }
            composeRule.onNodeWithText("3.0x").assertDoesNotExist()
            composeRule.onNodeWithText(composeRule.activity.getString(R.string.skip_silence)).assertDoesNotExist()
        } finally {
            composeRule.runOnIdle { player.release() }
        }
    }

    private class TestPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        private var state = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().add(Player.COMMAND_SET_SPEED_AND_PITCH).build())
            .setPlaybackParameters(PlaybackParameters(1f))
            .build()

        override fun getState(): State = state
    }
}
