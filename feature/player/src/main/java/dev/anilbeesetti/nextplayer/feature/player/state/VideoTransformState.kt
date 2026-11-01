package dev.anilbeesetti.nextplayer.feature.player.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Constraints
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.listen
import androidx.media3.common.util.UnstableApi
import dev.anilbeesetti.nextplayer.feature.player.extensions.copy
import dev.anilbeesetti.nextplayer.feature.player.extensions.videoZoom
import kotlin.math.abs

@UnstableApi
@Composable
fun rememberVideoTransformState(
    player: Player,
    enableZoomGesture: Boolean,
    enablePanGesture: Boolean,
    onEvent: (VideoZoomEvent) -> Unit = {},
): VideoTransformState {
    val videoTransformState = remember(player, enableZoomGesture, enablePanGesture) {
        VideoTransformState(
            player = player,
            enableZoomGesture = enableZoomGesture,
            enablePanGesture = enablePanGesture,
            onEvent = onEvent,
        )
    }
    LaunchedEffect(videoTransformState) { videoTransformState.observe() }
    return videoTransformState
}

@Stable
class VideoTransformState(
    private val player: Player,
    private val enableZoomGesture: Boolean = true,
    private val enablePanGesture: Boolean = true,
    private val onEvent: (VideoZoomEvent) -> Unit,
) {
    companion object {
        private const val MIN_ZOOM = 0.25f
        private const val MAX_ZOOM = 4f
        private const val HALF_TURN_DEGREES = 180
    }

    var zoom: Float by mutableFloatStateOf(1f)
        private set

    var offset: Offset by mutableStateOf(Offset.Zero)
        private set

    var isZooming: Boolean by mutableStateOf(false)
        private set

    /** Half-turn only: it flips an upside-down frame without changing its aspect ratio. */
    var rotationDegrees: Int by mutableIntStateOf(0)
        private set

    /**
     * The picture sits exactly where the surface draws it. A portrait swipe adds its own translation to the same
     * graphics layer, and a half-turned canvas would send that translation the wrong way, so only an untransformed
     * picture gets dragged.
     */
    val isUnTransformed: Boolean
        get() = zoom == 1f && offset == Offset.Zero && rotationDegrees == 0

    fun rotateCanvas() {
        rotationDegrees = if (rotationDegrees == 0) HALF_TURN_DEGREES else 0
    }

    fun onZoomPanGesture(constraints: Constraints, panChange: Offset, zoomChange: Float) {
        if (player.duration == C.TIME_UNSET) return
        if (!enableZoomGesture) return

        isZooming = true
        zoom = (zoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)

        val extraWidth = (zoom - 1) * constraints.maxWidth
        val extraHeight = (zoom - 1) * constraints.maxHeight

        val maxX = abs(extraWidth / 2)
        val maxY = abs(extraHeight / 2)

        if (enablePanGesture) {
            offset = Offset(
                x = (offset.x + zoom * panChange.x).coerceIn(-maxX, maxX),
                y = (offset.y + zoom * panChange.y).coerceIn(-maxY, maxY),
            )
        }
    }

    fun onZoomPanGestureEnd() {
        isZooming = false
        updateVideoScaleMetadataAndSendEvent()
    }

    suspend fun observe() {
        zoom = player.currentMediaItem?.mediaMetadata?.videoZoom ?: 1f
        player.listen { events ->
            if (events.contains(Player.EVENT_MEDIA_METADATA_CHANGED)) {
                zoom = player.currentMediaItem?.mediaMetadata?.videoZoom ?: 1f
            }
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                rotationDegrees = 0
            }
        }
    }

    private fun updateVideoScaleMetadataAndSendEvent(zoom: Float = this.zoom) {
        val currentMediaItem = player.currentMediaItem ?: return
        player.replaceMediaItem(
            player.currentMediaItemIndex,
            currentMediaItem.copy(videoZoom = zoom),
        )
        onEvent(VideoZoomEvent(currentMediaItem, zoom))
    }
}

@Stable
data class VideoZoomEvent(val mediaItem: MediaItem, val zoom: Float)
