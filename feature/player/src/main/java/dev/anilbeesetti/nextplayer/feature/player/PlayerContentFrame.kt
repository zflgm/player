package dev.anilbeesetti.nextplayer.feature.player

import android.graphics.Rect
import android.view.View
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
import androidx.media3.ui.compose.state.rememberPresentationState
import dev.anilbeesetti.nextplayer.feature.player.extensions.forEachTextureView
import dev.anilbeesetti.nextplayer.feature.player.state.ItemDragState
import dev.anilbeesetti.nextplayer.feature.player.state.PictureInPictureState
import dev.anilbeesetti.nextplayer.feature.player.state.VideoTransformState
import dev.anilbeesetti.nextplayer.feature.player.ui.ItemDragBackdrop
import dev.anilbeesetti.nextplayer.feature.player.ui.ShutterView
import dev.anilbeesetti.nextplayer.feature.player.ui.SubtitleConfiguration
import dev.anilbeesetti.nextplayer.feature.player.ui.SubtitleView

@OptIn(UnstableApi::class)
@Composable
fun PlayerContentFrame(
    modifier: Modifier = Modifier,
    player: Player,
    pictureInPictureState: PictureInPictureState,
    itemDragState: ItemDragState,
    videoTransformState: VideoTransformState,
    subtitleConfiguration: SubtitleConfiguration,
) {
    val presentationState = rememberPresentationState(player)
    val rotationDegrees = videoTransformState.rotationDegrees
    val isCanvasRotated = rotationDegrees != 0
    val rootView = LocalView.current

    // A SurfaceView layer does follow a graphicsLayer scale and translation; a rotation is the one transform it
    // cannot take, so the half-turn needs a TextureView with the rotation set on the view itself.
    SideEffect(rotationDegrees) {
        if (isCanvasRotated) {
            rootView.post { rootView.applyCanvasRotation(rotationDegrees) }
        }
    }

    // The surface's laid out edges, before a swipe moves it. The panel is painted over the surface, so it needs
    // to know where the picture is in order to keep off it.
    var pictureTop by remember { mutableFloatStateOf(0f) }
    var pictureBottom by remember { mutableFloatStateOf(0f) }

    Box(modifier.fillMaxSize()) {
        key(isCanvasRotated) {
            PlayerSurface(
                player = player,
                surfaceType = if (isCanvasRotated) SURFACE_TYPE_TEXTURE_VIEW else SURFACE_TYPE_SURFACE_VIEW,
                modifier = Modifier
                    .resizeWithContentScale(
                        contentScale = ContentScale.Fit,
                        sourceSizeDp = presentationState.videoSizeDp?.let { size ->
                            size.copy(
                                width = with(LocalDensity.current) { size.width.toDp().value },
                                height = with(LocalDensity.current) { size.height.toDp().value },
                            )
                        },
                    )
                    .onGloballyPositioned {
                        val bounds = it.boundsInWindow()
                        val rect = Rect(
                            bounds.left.toInt(),
                            bounds.top.toInt(),
                            bounds.right.toInt(),
                            bounds.bottom.toInt(),
                        )
                        pictureInPictureState.setVideoViewRect(rect)
                        val rooted = it.boundsInRoot()
                        pictureTop = rooted.top
                        pictureBottom = rooted.bottom
                        // The surface is recreated when the canvas is rotated, so re-apply on every layout pass.
                        rootView.applyCanvasRotation(rotationDegrees)
                    }
                    .graphicsLayer {
                        scaleX = videoTransformState.zoom
                        scaleY = videoTransformState.zoom
                        translationX = videoTransformState.offset.x
                        translationY = videoTransformState.offset.y + itemDragState.offset
                    },
            )
        }

        // After the surface, and only here: the window paints in the order its content is declared, and the surface
        // wipes the region it lies in, so a panel declared before it is wiped away with everything else under the
        // picture - which is why an earlier version of this never showed at all.
        if (itemDragState.backdropVisible) {
            ItemDragBackdrop(
                state = itemDragState,
                pictureTop = pictureTop,
                pictureBottom = pictureBottom,
            )
        }

        SubtitleView(
            player = player,
            isInPictureInPictureMode = pictureInPictureState.isInPictureInPictureMode,
            configuration = subtitleConfiguration,
        )

        // Held back during a swipe: the item change leaves the player without tracks for a moment, and the shutter
        // is full screen black, which would cover both the panel and the picture still travelling off screen.
        if (presentationState.coverSurface && !itemDragState.backdropVisible) {
            ShutterView()
        }
    }
}

private fun View.applyCanvasRotation(degrees: Int) {
    forEachTextureView { textureView ->
        textureView.rotation = degrees.toFloat()
    }
}
