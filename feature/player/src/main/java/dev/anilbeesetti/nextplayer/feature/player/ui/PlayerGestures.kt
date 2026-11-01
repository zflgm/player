package dev.anilbeesetti.nextplayer.feature.player.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.media3.common.Player
import dev.anilbeesetti.nextplayer.feature.player.extensions.detectCustomHorizontalDragGestures
import dev.anilbeesetti.nextplayer.feature.player.extensions.detectCustomTransformGestures
import dev.anilbeesetti.nextplayer.feature.player.extensions.detectCustomVerticalDragGestures
import dev.anilbeesetti.nextplayer.feature.player.state.ControlBarHeights
import dev.anilbeesetti.nextplayer.feature.player.state.ControlsVisibilityState
import dev.anilbeesetti.nextplayer.feature.player.state.ItemDragState
import dev.anilbeesetti.nextplayer.feature.player.state.ItemSwipeDirection
import dev.anilbeesetti.nextplayer.feature.player.state.PictureInPictureState
import dev.anilbeesetti.nextplayer.feature.player.state.SeekGestureState
import dev.anilbeesetti.nextplayer.feature.player.state.TapGestureState
import dev.anilbeesetti.nextplayer.feature.player.state.VideoTransformState
import dev.anilbeesetti.nextplayer.feature.player.state.VolumeAndBrightnessGestureState
import kotlin.math.max

private const val SWIPE_TO_CHANGE_ITEM_THRESHOLD = 0.15f

/** How much of the screen height each edge gets for folding the controls away. */
private const val CONTROL_ZONE_FRACTION = 2f / 6f

/** A portrait swipe past the threshold changes the video, like the short video feeds do. */
internal fun swipeToChangeItemDirection(
    verticalDragDistance: Float,
    screenHeight: Float,
): ItemSwipeDirection? {
    val threshold = screenHeight * SWIPE_TO_CHANGE_ITEM_THRESHOLD
    return when {
        verticalDragDistance <= -threshold -> ItemSwipeDirection.NEXT
        verticalDragDistance >= threshold -> ItemSwipeDirection.PREVIOUS
        else -> null
    }
}

private fun hasNeighbour(player: Player, direction: ItemSwipeDirection): Boolean = when (direction) {
    ItemSwipeDirection.NEXT -> player.hasNextMediaItem()
    ItemSwipeDirection.PREVIOUS -> player.hasPreviousMediaItem()
}

/**
 * The item a released swipe is going to: the threshold has to be crossed and the playlist has to have something
 * that way, so a swipe at the end of the list neither changes the item nor gets to slide the picture off.
 */
internal fun swipeToNeighbour(
    player: Player,
    verticalDragDistance: Float,
    screenHeight: Float,
): ItemSwipeDirection? =
    swipeToChangeItemDirection(verticalDragDistance, screenHeight)?.takeIf { hasNeighbour(player, it) }

/** The item a drag of this length is heading for, by its sign alone. */
private fun draggedDirection(verticalDragDistance: Float): ItemSwipeDirection? = when {
    verticalDragDistance < 0f -> ItemSwipeDirection.NEXT
    verticalDragDistance > 0f -> ItemSwipeDirection.PREVIOUS
    else -> null
}

/**
 * The neighbour the picture is being dragged towards: null once the swipe heads past an end of the list, where
 * there is nothing to promise, so the panel never names an item that is not there.
 */
internal fun draggedNeighbour(
    player: Player,
    verticalDragDistance: Float,
): ItemSwipeDirection? = draggedDirection(verticalDragDistance)?.takeIf { hasNeighbour(player, it) }

/**
 * The name the panel adds under that neighbour, read straight off the playlist, so nothing has to be opened,
 * decoded or fetched. Null for the files that carry no title.
 */
internal fun draggedItemTitle(
    player: Player,
    verticalDragDistance: Float,
): String? {
    val direction = draggedNeighbour(player = player, verticalDragDistance = verticalDragDistance) ?: return null
    val index = when (direction) {
        ItemSwipeDirection.NEXT -> player.nextMediaItemIndex
        ItemSwipeDirection.PREVIOUS -> player.previousMediaItemIndex
    }
    return player.getMediaItemAt(index).mediaMetadata.title?.toString()?.takeIf { it.isNotEmpty() }
}

/**
 * Portrait taps are zoned in thirds, with the measured bars as a floor: the top and bottom thirds fold the
 * controls away and only the strip between them pauses. The bars themselves would be a much narrower zone, and
 * reaching for the picture to hide them is a fussy target, so a third of the screen is handed to each edge. A bar
 * taller than its third (large font scale, chapter row) keeps its own whole extent, so a control is never behind a
 * tap that pauses.
 */
internal fun isControlBarTapRegion(
    tapY: Float,
    screenHeight: Int,
    controlBarHeights: ControlBarHeights,
): Boolean {
    val edgeZone = screenHeight * CONTROL_ZONE_FRACTION
    if (tapY < max(controlBarHeights.top.toFloat(), edgeZone)) return true
    return tapY >= screenHeight - max(controlBarHeights.bottom.toFloat(), edgeZone)
}

/**
 * A tap on either bar only folds the controls away or brings them back; a tap on the video strip between them
 * pauses, and brings the controls along with it so the pause indicator and the timeline are on screen. Paused
 * controls never auto-hide, so the timeline stays put for scrubbing. Landscape keeps a single behaviour.
 */
internal fun handleSingleTap(
    isPortrait: Boolean,
    tapY: Float,
    screenHeight: Int,
    controlBarHeights: ControlBarHeights,
    player: Player,
    controlsVisibilityState: ControlsVisibilityState,
) {
    if (!isPortrait) {
        controlsVisibilityState.toggleControlsVisibility()
        return
    }
    if (isControlBarTapRegion(tapY, screenHeight, controlBarHeights)) {
        if (controlsVisibilityState.controlsVisible) {
            controlsVisibilityState.hideControls()
        } else {
            controlsVisibilityState.showControls()
        }
        return
    }
    if (!controlsVisibilityState.controlsVisible) controlsVisibilityState.showControls()
    when (player.isPlaying) {
        true -> player.pause()
        false -> player.play()
    }
}

@Composable
fun PlayerGestures(
    modifier: Modifier = Modifier,
    player: Player,
    controlsVisibilityState: ControlsVisibilityState,
    tapGestureState: TapGestureState,
    pictureInPictureState: PictureInPictureState,
    seekGestureState: SeekGestureState,
    videoTransformState: VideoTransformState,
    volumeAndBrightnessGestureState: VolumeAndBrightnessGestureState,
    isPortrait: Boolean,
    controlBarHeights: ControlBarHeights,
    itemDragState: ItemDragState,
    onSwipeToPreviousItem: () -> Unit,
    onSwipeToNextItem: () -> Unit,
) {
    BoxWithConstraints {
        Box(
            modifier = modifier
                .fillMaxSize()
                .pointerInput(
                    isPortrait,
                    pictureInPictureState.isInPictureInPictureMode,
                ) {
                    if (pictureInPictureState.isInPictureInPictureMode) return@pointerInput

                    detectTapGestures(
                        onTap = { offset ->
                            if (tapGestureState.seekMillis != 0L) return@detectTapGestures
                            handleSingleTap(
                                isPortrait = isPortrait,
                                tapY = offset.y,
                                screenHeight = size.height,
                                controlBarHeights = controlBarHeights,
                                player = player,
                                controlsVisibilityState = controlsVisibilityState,
                            )
                        },
                        onDoubleTap = {
                            if (controlsVisibilityState.controlsLocked) return@detectTapGestures
                            tapGestureState.handleDoubleTap(offset = it, size = size)
                        },
                        onPress = {
                            tryAwaitRelease()
                            tapGestureState.handleOnLongPressRelease()
                        },
                        onLongPress = {
                            if (controlsVisibilityState.controlsLocked) return@detectTapGestures
                            tapGestureState.handleLongPress(offset = it)
                        },
                    )
                }
                .pointerInput(
                    controlsVisibilityState.controlsLocked,
                    pictureInPictureState.isInPictureInPictureMode,
                ) {
                    if (controlsVisibilityState.controlsLocked) return@pointerInput
                    if (pictureInPictureState.isInPictureInPictureMode) return@pointerInput

                    detectCustomHorizontalDragGestures(
                        onDragStart = seekGestureState::onDragStart,
                        onHorizontalDrag = seekGestureState::onDrag,
                        onDragCancel = seekGestureState::onDragEnd,
                        onDragEnd = seekGestureState::onDragEnd,
                    )
                }
                .pointerInput(
                    isPortrait,
                    controlsVisibilityState.controlsLocked,
                    pictureInPictureState.isInPictureInPictureMode,
                ) {
                    if (controlsVisibilityState.controlsLocked) return@pointerInput
                    if (pictureInPictureState.isInPictureInPictureMode) return@pointerInput

                    if (isPortrait) {
                        var verticalDragDistance = 0f
                        // Decided once, when the finger goes down: a zoomed or rotated canvas is drawn through a
                        // graphics layer of its own, and a second offset on top of it would fight the transform.
                        var animatedDrag = false
                        detectCustomVerticalDragGestures(
                            onDragStart = {
                                verticalDragDistance = 0f
                                animatedDrag = videoTransformState.isUnTransformed
                                if (animatedDrag) itemDragState.onDragStart()
                            },
                            onVerticalDrag = { _, dragAmount ->
                                verticalDragDistance += dragAmount
                                if (animatedDrag) {
                                    itemDragState.onDrag(
                                        dragAmount = dragAmount,
                                        screenHeight = size.height.toFloat(),
                                        direction = draggedNeighbour(player, verticalDragDistance),
                                        title = draggedItemTitle(player, verticalDragDistance),
                                    )
                                }
                            },
                            onDragCancel = {
                                verticalDragDistance = 0f
                                if (animatedDrag) itemDragState.onDragCancelled()
                                animatedDrag = false
                            },
                            onDragEnd = {
                                val direction = swipeToNeighbour(
                                    player = player,
                                    verticalDragDistance = verticalDragDistance,
                                    screenHeight = size.height.toFloat(),
                                )
                                verticalDragDistance = 0f
                                if (animatedDrag) {
                                    itemDragState.onDragEnd(
                                        changingItem = direction != null,
                                        screenHeight = size.height.toFloat(),
                                    )
                                }
                                animatedDrag = false
                                when (direction) {
                                    ItemSwipeDirection.NEXT -> onSwipeToNextItem()
                                    ItemSwipeDirection.PREVIOUS -> onSwipeToPreviousItem()
                                    null -> Unit
                                }
                            },
                        )
                    } else {
                        detectCustomVerticalDragGestures(
                            onDragStart = { volumeAndBrightnessGestureState.onDragStart(it, size) },
                            onVerticalDrag = volumeAndBrightnessGestureState::onDrag,
                            onDragCancel = volumeAndBrightnessGestureState::onDragEnd,
                            onDragEnd = volumeAndBrightnessGestureState::onDragEnd,
                        )
                    }
                }
                .pointerInput(
                    controlsVisibilityState.controlsLocked,
                    pictureInPictureState.isInPictureInPictureMode,
                ) {
                    if (controlsVisibilityState.controlsLocked) return@pointerInput
                    if (pictureInPictureState.isInPictureInPictureMode) return@pointerInput

                    detectCustomTransformGestures(
                        onGesture = { _, panChange, zoomChange, _ ->
                            if (tapGestureState.isLongPressGestureInAction) return@detectCustomTransformGestures
                            videoTransformState.onZoomPanGesture(
                                constraints = this@BoxWithConstraints.constraints,
                                panChange = panChange,
                                zoomChange = zoomChange,
                            )
                        },
                        onGestureEnd = {
                            videoTransformState.onZoomPanGestureEnd()
                        },
                    )
                },
        )
    }
}
