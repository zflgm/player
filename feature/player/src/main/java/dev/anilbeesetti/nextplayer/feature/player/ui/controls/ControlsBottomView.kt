package dev.anilbeesetti.nextplayer.feature.player.ui.controls

import androidx.annotation.OptIn
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.NavigateNext
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util.getStringForTime
import androidx.media3.ui.compose.state.ProgressStateWithTickInterval
import androidx.media3.ui.compose.state.rememberProgressStateWithTickInterval
import dev.anilbeesetti.nextplayer.core.common.extensions.isTelevision
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.extensions.copy
import dev.anilbeesetti.nextplayer.core.ui.theme.NextPlayerTheme
import dev.anilbeesetti.nextplayer.feature.player.buttons.PlaybackModeButton
import dev.anilbeesetti.nextplayer.feature.player.buttons.PlaybackSpeedButton
import dev.anilbeesetti.nextplayer.feature.player.buttons.PlayerButton
import dev.anilbeesetti.nextplayer.feature.player.buttons.PlayerButtonBlackAlpha
import dev.anilbeesetti.nextplayer.feature.player.buttons.RotateButton
import dev.anilbeesetti.nextplayer.feature.player.model.labelRes
import dev.anilbeesetti.nextplayer.feature.player.state.ChaptersState
import dev.anilbeesetti.nextplayer.feature.player.state.rememberChaptersState
import dev.anilbeesetti.nextplayer.feature.player.ui.preview.rememberPreviewPlayer
import dev.anilbeesetti.nextplayer.feature.player.ui.titleOrDefault
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderMode

private const val MILLISECONDS_PER_SECOND = 1_000L
const val LOCK_CONTROLS_TEST_TAG = "lockControls"
const val PICTURE_IN_PICTURE_TEST_TAG = "pictureInPicture"
const val CONTENT_ROTATE_TEST_TAG = "contentRotate"
const val TIME_DISPLAY_TEST_TAG = "timeDisplay"
const val DECODER_TEST_TAG = "selectDecoder"
const val AUDIO_TRACK_TEST_TAG = "selectAudioTrack"
const val SUBTITLE_TRACK_TEST_TAG = "selectSubtitleTrack"

@OptIn(UnstableApi::class)
@Composable
fun ControlsBottomView(
    modifier: Modifier = Modifier,
    player: Player?,
    progressState: ProgressStateWithTickInterval,
    chaptersState: ChaptersState,
    controlsAlignment: Alignment.Horizontal,
    isPipSupported: Boolean,
    isPortrait: Boolean,
    showRemainingTime: Boolean,
    videoDecoderMode: DecoderMode? = null,
    onToggleTimeDisplay: () -> Unit,
    onChaptersClick: () -> Unit,
    onLockControlsClick: () -> Unit,
    onPictureInPictureClick: () -> Unit,
    onContentRotateClick: () -> Unit,
    contentRotated: Boolean,
    onPlaybackSpeedClick: () -> Unit,
    onDecoderClick: () -> Unit = {},
    onAudioClick: () -> Unit = {},
    onSubtitleClick: () -> Unit = {},
    onSeek: (Long) -> Unit,
    onSeekEnd: () -> Unit,
    /** Where a finger is holding the bar, when one is: the position this view shows instead of the player's. */
    scrubbingPositionMs: Float? = null,
    onScrubbing: (Float?) -> Unit = {},
) {
    val systemBarsPadding = WindowInsets.systemBars.union(WindowInsets.displayCutout).asPaddingValues()
    val context = LocalContext.current
    val isTv = remember { context.isTelevision }
    val timeButtonFocusRequester = remember { FocusRequester() }
    val decoderDescription = stringResource(R.string.select_decoders)

    // While a finger is held on the seekbar, this is the position to show: the player has not moved yet, and
    // the whole point of scrubbing is reading where the finger is going to land.
    val displayedPositionMs = scrubbingPositionMs ?: progressState.currentPositionMs.toFloat()
    Column(
        modifier = modifier
            .padding(systemBarsPadding.copy(top = 0.dp))
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp)
            .padding(bottom = 16.dp.takeIf { systemBarsPadding.calculateBottomPadding() == 0.dp } ?: 0.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier
                .focusProperties { onEnter = { timeButtonFocusRequester.requestFocus() } }
                .focusGroup(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PlayerButton(
                modifier = Modifier
                    .testTag(TIME_DISPLAY_TEST_TAG)
                    .focusRequester(timeButtonFocusRequester),
                onClick = onToggleTimeDisplay,
                containerColor = PlayerButtonBlackAlpha,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 1.dp),
            ) {
                // Rounding belongs in the derivation so the label only recomposes when a second ticks over; the
                // scrub target has to be a key as well, since it reaches this scope as a plain value, not a state.
                val timeTextPositionMs by remember(scrubbingPositionMs, progressState) {
                    derivedStateOf {
                        val shownMs = (scrubbingPositionMs ?: progressState.currentPositionMs.toFloat()).toLong()
                        val wholeSeconds = shownMs / MILLISECONDS_PER_SECOND
                        wholeSeconds * MILLISECONDS_PER_SECOND
                    }
                }
                Text(
                    text = buildString {
                        val positionText = when (showRemainingTime) {
                            true -> if (progressState.durationMs != C.TIME_UNSET) {
                                val remainingMs = timeTextPositionMs - progressState.durationMs
                                getStringForTime(remainingMs)
                            } else {
                                getStringForTime(C.TIME_UNSET)
                            }

                            false -> getStringForTime(timeTextPositionMs)
                        }
                        append(positionText)
                        append(" / ")
                        append(getStringForTime(progressState.durationMs))
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            if (chaptersState.chapters.isNotEmpty()) {
                PlayerButton(
                    onClick = onChaptersClick,
                    containerColor = PlayerButtonBlackAlpha,
                    contentPadding = PaddingValues(vertical = 1.dp, horizontal = 8.dp).copy(end = 2.dp),
                ) {
                    Row(
                        modifier = Modifier,
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = chaptersState.chapters.getOrNull(chaptersState.currentChapterIndex)
                                ?.titleOrDefault(chaptersState.currentChapterIndex)
                                ?: stringResource(R.string.chapters),
                            modifier = Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.NavigateNext,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlaybackSpeedButton(
                    player = player,
                    onClick = onPlaybackSpeedClick,
                )

                if (!isTv) {
                    RotateButton()
                }
            }
        }
        PlayerSeekbar(
            position = displayedPositionMs,
            duration = progressState.durationMs.toFloat(),
            chapters = chaptersState.chapters,
            onSeek = { onSeek(it.toLong()) },
            onSeekFinished = { onSeekEnd() },
            onScrubbing = onScrubbing,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = controlsAlignment),
        ) {
            if (!isPortrait) {
                PlayerButton(
                    modifier = Modifier.testTag(LOCK_CONTROLS_TEST_TAG),
                    onClick = onLockControlsClick,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_lock_open),
                        contentDescription = null,
                    )
                }
            }
            if (isPipSupported && !isPortrait) {
                PlayerButton(
                    modifier = Modifier.testTag(PICTURE_IN_PICTURE_TEST_TAG),
                    onClick = onPictureInPictureClick,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_pip),
                        contentDescription = null,
                    )
                }
            }
            PlayerButton(
                modifier = Modifier.testTag(CONTENT_ROTATE_TEST_TAG),
                onClick = onContentRotateClick,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_rotate_half),
                    contentDescription = stringResource(R.string.rotate_video_180),
                    tint = if (contentRotated) MaterialTheme.colorScheme.primary else Color.Unspecified,
                )
            }
            PlaybackModeButton(player = player)

            // Decoder, audio and subtitle picks used to crowd the top bar next to the title; they belong
            // with the other playback switches.
            PlayerButton(
                modifier = Modifier
                    .testTag(DECODER_TEST_TAG)
                    .semantics { contentDescription = decoderDescription },
                onClick = onDecoderClick,
            ) {
                Text(
                    text = stringResource((videoDecoderMode ?: DecoderMode.HARDWARE).labelRes),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
            PlayerButton(
                modifier = Modifier.testTag(AUDIO_TRACK_TEST_TAG),
                onClick = onAudioClick,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_audio_track),
                    contentDescription = null,
                )
            }
            PlayerButton(
                modifier = Modifier.testTag(SUBTITLE_TRACK_TEST_TAG),
                onClick = onSubtitleClick,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_subtitle_track),
                    contentDescription = null,
                )
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Preview
@Composable
private fun ControlsBottomViewPreview() {
    val player = rememberPreviewPlayer()
    val progressState = rememberProgressStateWithTickInterval(player)
    NextPlayerTheme(darkTheme = true) {
        Surface {
            ControlsBottomView(
                player = player,
                progressState = progressState,
                chaptersState = rememberChaptersState(player, progressState),
                controlsAlignment = Alignment.Start,
                isPipSupported = true,
                isPortrait = false,
                showRemainingTime = false,
                onToggleTimeDisplay = {},
                onChaptersClick = {},
                onLockControlsClick = {},
                onPictureInPictureClick = {},
                onContentRotateClick = {},
                contentRotated = false,
                onPlaybackSpeedClick = {},
                onSeek = {},
                onSeekEnd = {},
            )
        }
    }
}
