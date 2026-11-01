package dev.anilbeesetti.nextplayer.feature.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.anilbeesetti.nextplayer.core.ui.R as coreUiR
import dev.anilbeesetti.nextplayer.feature.player.state.ItemDragState
import dev.anilbeesetti.nextplayer.feature.player.state.ItemSwipeDirection

/** The panel is measured by geometry in tests, so it carries a tag: it has no text of its own to search for. */
const val ITEM_DRAG_BACKDROP_TEST_TAG = "item_drag_backdrop"

/** How far from the edge the panel prints, so the word clears the system bars and the picture that is leaving. */
private val PANEL_EDGE_INSET = 96.dp

/**
 * Where a portrait swipe may paint, in the frame it draws into: the band on the side the picture is leaving,
 * stopped short of the picture's own edge so the frame that is travelling off stays visible.
 *
 * [pictureTop] and [pictureBottom] are where the surface was laid out, before a swipe moved it; [offset] is that
 * move. A null [direction] - a swipe towards an end of the list - still has a side it came from, which is the one
 * the sign of [offset] says. Null where there is no band to paint.
 */
internal fun backdropBand(
    frameWidth: Float,
    frameHeight: Float,
    pictureTop: Float,
    pictureBottom: Float,
    offset: Float,
    direction: ItemSwipeDirection?,
): Rect? {
    val top = (pictureTop + offset).coerceIn(0f, frameHeight)
    val bottom = (pictureBottom + offset).coerceIn(0f, frameHeight)
    val downwards = direction == ItemSwipeDirection.PREVIOUS || (direction == null && offset > 0f)
    val band = if (downwards) {
        Rect(Offset.Zero, Size(frameWidth, top))
    } else {
        Rect(Offset(0f, bottom), Size(frameWidth, frameHeight - bottom))
    }
    return band.takeIf { it.width > 0f && it.height > 0f }
}

/**
 * What a portrait swipe uncovers around the picture.
 *
 * The player is always dark-themed, and the dark theme's `surface` is a hair above black, so a panel painted with
 * it reads as nothing at all - which is the one thing this screen may not be. It gets a tone the window's black
 * cannot be mistaken for, and the word that says which way the swipe is heading, big enough to read mid-drag.
 *
 * Only [backdropBand] gets painted, not the whole frame: this panel is drawn on top of the surface, so a full
 * screen fill would hide the picture that is still travelling off. [pictureTop] and [pictureBottom] are the
 * surface's own laid out edges and [state] carries how far the finger has moved it since - read while drawing, so
 * the band keeps up with the drag.
 *
 * The word is printed against the edge the picture is vacating, not in the middle: at the start of a drag the
 * picture still covers the centre of the screen, and a headline parked there is exactly what the user cannot read.
 *
 * [state] holds the direction and the item waiting there; the name is missing whenever the queue has none, and the
 * word alone still tells the user what the gesture is about to do.
 *
 * It has no pointer input, so the gesture box keeps receiving the drag.
 */
@Composable
fun ItemDragBackdrop(
    state: ItemDragState,
    pictureTop: Float,
    pictureBottom: Float,
    modifier: Modifier = Modifier,
) {
    val direction = state.backdropDirection
    val title = state.backdropTitle
    val bandColor = MaterialTheme.colorScheme.surfaceVariant
    val alignment = when (direction) {
        ItemSwipeDirection.NEXT -> Alignment.BottomCenter
        ItemSwipeDirection.PREVIOUS -> Alignment.TopCenter
        null -> Alignment.Center
    }
    val edgePadding = when (direction) {
        ItemSwipeDirection.NEXT -> PaddingValues(bottom = PANEL_EDGE_INSET)
        ItemSwipeDirection.PREVIOUS -> PaddingValues(top = PANEL_EDGE_INSET)
        null -> PaddingValues()
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                val band = backdropBand(
                    frameWidth = size.width,
                    frameHeight = size.height,
                    pictureTop = pictureTop,
                    pictureBottom = pictureBottom,
                    offset = state.offset,
                    direction = direction,
                )
                if (band != null) {
                    drawRect(
                        color = bandColor,
                        topLeft = band.topLeft,
                        size = Size(band.width, band.height),
                    )
                }
            }
            .testTag(ITEM_DRAG_BACKDROP_TEST_TAG),
        contentAlignment = alignment,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .widthIn(max = 300.dp)
                .padding(horizontal = 24.dp)
                .padding(edgePadding),
        ) {
            if (direction != null) {
                Text(
                    text = stringResource(
                        if (direction == ItemSwipeDirection.NEXT) {
                            coreUiR.string.next_item
                        } else {
                            coreUiR.string.previous_item
                        },
                    ),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
            }
            if (!title.isNullOrEmpty()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alpha(0.85f),
                )
            }
            Icon(
                painter = painterResource(coreUiR.drawable.ic_play),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .size(56.dp)
                    .alpha(0.5f),
            )
        }
    }
}
