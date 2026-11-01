package dev.anilbeesetti.nextplayer.feature.player.ui.controls

import androidx.annotation.OptIn
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.state.rememberPlayPauseButtonState
import dev.anilbeesetti.nextplayer.core.ui.R as coreUiR
import dev.anilbeesetti.nextplayer.core.ui.theme.NextPlayerTheme
import dev.anilbeesetti.nextplayer.feature.player.buttons.NextButton
import dev.anilbeesetti.nextplayer.feature.player.buttons.PlayPauseButton
import dev.anilbeesetti.nextplayer.feature.player.buttons.PreviousButton
import dev.anilbeesetti.nextplayer.feature.player.ui.preview.rememberPreviewPlayer

const val PORTRAIT_PAUSE_INDICATOR_TEST_TAG = "portraitPauseIndicator"

@OptIn(UnstableApi::class)
@Composable
fun ControlsMiddleView(
    modifier: Modifier = Modifier,
    player: Player?,
    isPortrait: Boolean,
) {
    val playPauseState = rememberPlayPauseButtonState(player)

    // A short-video feed keeps the middle of the screen free of buttons: portrait taps pause, and item
    // changes come from the vertical swipe. Only the paused state earns a big ghost triangle — it says
    // "tap here to resume" without hiding the picture, and it stays put because controls never auto-hide
    // while paused.
    if (isPortrait) {
        if (playPauseState.showPlay) {
            Icon(
                painter = painterResource(coreUiR.drawable.ic_play),
                contentDescription = null,
                // Fixed white: the indicator sits on the video, not on the app's surface colour, so following the
                // theme can render it near-black on a light theme and make it disappear.
                tint = Color.White,
                modifier = modifier
                    .testTag(PORTRAIT_PAUSE_INDICATOR_TEST_TAG)
                    .size(96.dp)
                    .alpha(0.6f),
            )
        }
        return
    }

    val playPauseFocusRequester = remember { FocusRequester() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .focusProperties {
                onEnter = { playPauseFocusRequester.requestFocus() }
            }
            .focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(40.dp, alignment = Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PreviousButton(player = player)
        PlayPauseButton(
            player = player,
            modifier = Modifier.focusRequester(playPauseFocusRequester),
        )
        NextButton(player = player)
    }
}

@OptIn(UnstableApi::class)
@Preview
@Composable
private fun ControlsMiddleViewPreview() {
    NextPlayerTheme(darkTheme = true) {
        Surface {
            ControlsMiddleView(
                player = rememberPreviewPlayer(),
                isPortrait = false,
            )
        }
    }
}
