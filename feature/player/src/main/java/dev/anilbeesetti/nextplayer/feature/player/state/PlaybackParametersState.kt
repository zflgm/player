package dev.anilbeesetti.nextplayer.feature.player.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import androidx.media3.common.listen
import androidx.media3.common.util.UnstableApi

@UnstableApi
@Composable
fun rememberPlaybackParametersState(player: Player): PlaybackParametersState {
    val playbackParametersState = remember { PlaybackParametersState(player) }
    LaunchedEffect(player) { playbackParametersState.observe() }
    return playbackParametersState
}

@UnstableApi
class PlaybackParametersState(
    private val player: Player,
) {
    var speed: Float by mutableFloatStateOf(1f)
        private set

    fun setPlaybackSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
    }

    suspend fun observe() {
        updateSpeed()

        player.listen { events ->
            if (events.contains(Player.EVENT_PLAYBACK_PARAMETERS_CHANGED)) {
                updateSpeed()
            }
        }
    }

    private fun updateSpeed() {
        speed = player.playbackParameters.speed
    }
}
