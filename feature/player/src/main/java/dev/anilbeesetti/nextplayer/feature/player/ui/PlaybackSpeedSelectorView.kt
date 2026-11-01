package dev.anilbeesetti.nextplayer.feature.player.ui

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import dev.anilbeesetti.nextplayer.core.common.extensions.round
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.feature.player.state.rememberPlaybackParametersState

// Quarter-speed steps up to 2x, which is the whole range worth reaching for mid-video. Anything finer came from a
// slider, and a slider in this sheet could land on values like 1.35 that no preset row could ever show as selected.
private val playbackSpeeds = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

@OptIn(UnstableApi::class)
@Composable
fun BoxScope.PlaybackSpeedSelectorView(
    modifier: Modifier = Modifier,
    show: Boolean,
    player: Player,
) {
    val playbackParametersState = rememberPlaybackParametersState(player)
    val selectedSpeed = playbackParametersState.speed.round(2)

    OverlayView(
        modifier = modifier,
        show = show,
        title = stringResource(R.string.select_playback_speed),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FlowRow(
                maxItemsInEachRow = 4,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                playbackSpeeds.forEach { speed ->
                    val selected = speed == selectedSpeed
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                            .border(
                                width = 1.dp,
                                color = LocalContentColor.current,
                                shape = CircleShape,
                            )
                            .clickable { playbackParametersState.setPlaybackSpeed(speed) }
                            .padding(
                                horizontal = 8.dp,
                                vertical = 8.dp,
                            )
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "${speed}x",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                }
            }
        }
    }
}
