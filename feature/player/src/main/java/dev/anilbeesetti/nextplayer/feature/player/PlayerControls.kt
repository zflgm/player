package dev.anilbeesetti.nextplayer.feature.player

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.state.ProgressStateWithTickInterval
import dev.anilbeesetti.nextplayer.core.model.ControlButtonsPosition
import dev.anilbeesetti.nextplayer.core.model.PlayerPreferences
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.feature.player.extensions.formatted
import dev.anilbeesetti.nextplayer.feature.player.state.ChaptersState
import dev.anilbeesetti.nextplayer.feature.player.state.ControlBarHeights
import dev.anilbeesetti.nextplayer.feature.player.state.ControlsVisibilityState
import dev.anilbeesetti.nextplayer.feature.player.state.SeekGestureState
import dev.anilbeesetti.nextplayer.feature.player.state.VideoTransformState
import dev.anilbeesetti.nextplayer.feature.player.state.rememberPlaylistState
import dev.anilbeesetti.nextplayer.feature.player.state.seekAmountFormatted
import dev.anilbeesetti.nextplayer.feature.player.state.seekToPositionFormated
import dev.anilbeesetti.nextplayer.feature.player.ui.InfoView
import dev.anilbeesetti.nextplayer.feature.player.ui.OverlayView
import dev.anilbeesetti.nextplayer.feature.player.ui.controls.ControlsBottomView
import dev.anilbeesetti.nextplayer.feature.player.ui.controls.ControlsMiddleView
import dev.anilbeesetti.nextplayer.feature.player.ui.controls.ControlsTopView
import dev.anilbeesetti.nextplayer.feature.player.ui.isPortrait
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderMode
import kotlin.time.Duration.Companion.milliseconds

@OptIn(UnstableApi::class)
@Composable
fun PlayerControls(
    player: Player?,
    title: String,
    playerPreferences: PlayerPreferences,
    videoDecoderMode: DecoderMode?,
    controlsVisibilityState: ControlsVisibilityState,
    seekGestureState: SeekGestureState,
    videoTransformState: VideoTransformState,
    progressState: ProgressStateWithTickInterval,
    chaptersState: ChaptersState,
    isPipSupported: Boolean,
    controlBarHeights: ControlBarHeights,
    onShowOverlay: (OverlayView) -> Unit,
    onBackClick: () -> Unit,
    onToggleTimeDisplay: () -> Unit,
    onPictureInPictureClick: () -> Unit,
    modifier: Modifier = Modifier,
    middleControlsModifier: Modifier = Modifier,
) {
    val isPortrait = LocalConfiguration.current.isPortrait
    // Where a finger is holding the seekbar, reported up by the bar below. It lives at this level because the
    // middle of the screen - the only place it can be read without looking away from the picture - is drawn here.
    var scrubTargetMs by remember { mutableStateOf<Float?>(null) }
    // The queue index is the same number the playlist panel rows are drawn in, so the two can never disagree.
    // A single-item queue has nothing to count, and 0 total means the timeline command hasn't landed yet.
    val playlistState = player?.let { rememberPlaylistState(it) }
    val queueIndex = playlistState?.currentMediaItemIndex ?: C.INDEX_UNSET
    val queueTotal = playlistState?.mediaItemCount ?: 0
    val queuePosition = if (queueIndex != C.INDEX_UNSET && queueTotal > 1) {
        stringResource(R.string.player_queue_position, queueIndex + 1, queueTotal)
    } else {
        null
    }
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column {
            AnimatedVisibility(
                visible = controlsVisibilityState.controlsVisible,
                modifier = Modifier.onSizeChanged { controlBarHeights.top = it.height },
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                ControlsTopView(
                    title = title,
                    queuePosition = queuePosition,
                    onPlaylistClick = { onShowOverlay(OverlayView.PLAYLIST) },
                    onBackClick = onBackClick,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            AnimatedVisibility(
                visible = controlsVisibilityState.controlsVisible && !controlsVisibilityState.controlsLocked,
                modifier = Modifier.onSizeChanged { controlBarHeights.bottom = it.height },
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                ControlsBottomView(
                    player = player,
                    progressState = progressState,
                    chaptersState = chaptersState,
                    onChaptersClick = { onShowOverlay(OverlayView.CHAPTERS) },
                    controlsAlignment = when (playerPreferences.controlButtonsPosition) {
                        ControlButtonsPosition.LEFT -> Alignment.Start
                        ControlButtonsPosition.RIGHT -> Alignment.End
                    },
                    isPipSupported = isPipSupported,
                    isPortrait = isPortrait,
                    showRemainingTime = playerPreferences.showRemainingTime,
                    videoDecoderMode = videoDecoderMode,
                    onDecoderClick = { onShowOverlay(OverlayView.DECODER_SELECTOR) },
                    onAudioClick = { onShowOverlay(OverlayView.AUDIO_SELECTOR) },
                    onSubtitleClick = { onShowOverlay(OverlayView.SUBTITLE_SELECTOR) },
                    onToggleTimeDisplay = onToggleTimeDisplay,
                    onSeek = seekGestureState::onSeek,
                    onSeekEnd = seekGestureState::onSeekEnd,
                    scrubbingPositionMs = scrubTargetMs,
                    onScrubbing = { scrubTargetMs = it },
                    onPlaybackSpeedClick = { onShowOverlay(OverlayView.PLAYBACK_SPEED) },
                    onLockControlsClick = {
                        controlsVisibilityState.showControls()
                        controlsVisibilityState.lockControls()
                    },
                    onContentRotateClick = {
                        controlsVisibilityState.showControls()
                        videoTransformState.rotateCanvas()
                    },
                    contentRotated = videoTransformState.rotationDegrees != 0,
                    onPictureInPictureClick = onPictureInPictureClick,
                )
            }
        }
        val scrubTarget = scrubTargetMs
        when {
            seekGestureState.seekAmount != null -> InfoView(info = "${seekGestureState.seekAmountFormatted}\n[${seekGestureState.seekToPositionFormated}]")
            scrubTarget != null -> InfoView(info = scrubTarget.toLong().milliseconds.formatted())
            videoTransformState.isZooming -> InfoView(info = "${(videoTransformState.zoom * 100).toInt()}%")
            controlsVisibilityState.controlsVisible -> ControlsMiddleView(
                player = player,
                isPortrait = isPortrait,
                modifier = middleControlsModifier,
            )
        }
    }
}
