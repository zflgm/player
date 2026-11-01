package dev.anilbeesetti.nextplayer.feature.player.ui.controls

import androidx.annotation.OptIn
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.Chapter
import dev.anilbeesetti.nextplayer.feature.player.LocalControlsVisibilityState
import dev.anilbeesetti.nextplayer.feature.player.LocalUseMaterialYouControls
import dev.anilbeesetti.nextplayer.feature.player.state.currentChapterIndex
import kotlin.math.abs
import kotlin.time.Duration

internal const val SEEK_SCRUB_TEST_TAG = "seekScrub"

@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
@Composable
internal fun PlayerSeekbar(
    modifier: Modifier = Modifier,
    position: Float,
    duration: Float,
    chapters: List<Chapter>,
    onSeek: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    onScrubbing: (Float?) -> Unit = {},
) {
    val hapticFeedback = LocalHapticFeedback.current
    var lastSeekChapterIndex by remember(chapters) { mutableStateOf<Int?>(null) }

    // Crossing a chapter boundary is the only feedback worth a vibration: the pixels themselves carry the
    // precision, since one pixel is duration/barWidth long (~0.2s for a four minute film on a phone).
    val tickChapterChange: (value: Float, previous: Float) -> Unit = { value, previous ->
        val chapterIndex = chapters.currentChapterIndex(value.toLong())
        val previousChapterIndex = lastSeekChapterIndex ?: chapters.currentChapterIndex(previous.toLong())
        if (chapterIndex != previousChapterIndex) {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.SegmentTick)
        }
        lastSeekChapterIndex = chapterIndex
    }

    val onValueChange: (Float) -> Unit = { value ->
        tickChapterChange(value, position)
        onSeek(value)
    }
    val onValueChangeFinished = {
        lastSeekChapterIndex = null
        onSeekFinished()
    }
    var isFocused by remember { mutableStateOf(false) }
    val focusModifier = modifier
        .fillMaxWidth()
        .onFocusChanged { isFocused = it.isFocused }

    // Finger-following scrubbing: the slider is a controlled component, so the caller feeds the previewed
    // position back in as [position]. The running gesture outlives its first composition, so everything it
    // calls is read through rememberUpdatedState: an old player must never receive the committed seek.
    val anchorMs by rememberUpdatedState(position)
    val totalMs by rememberUpdatedState(duration)
    val reportScrubbing by rememberUpdatedState(onScrubbing)
    val tickChapter by rememberUpdatedState(tickChapterChange)
    val seekTo by rememberUpdatedState(onSeek)
    val seekEnded by rememberUpdatedState(onSeekFinished)
    val touchSlop = LocalViewConfiguration.current.touchSlop
    var barWidth by remember { mutableFloatStateOf(0f) }
    // Read through rememberUpdatedState with the rest of what the running gesture calls: the state is provided
    // per player, and a retired one must not be handed the auto hide timer back.
    val controlsVisibilityState by rememberUpdatedState(LocalControlsVisibilityState.current)

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        // The box wraps the slider, so the scrub surface below is exactly as wide as the drawn track.
        // The slider keeps [focusModifier]: it is the focusable node, and the keyboard and switch access
        // paths keep seeking immediately.
        Box {
            if (LocalUseMaterialYouControls.current) {
                MaterialYouSlider(
                    modifier = focusModifier,
                    isFocused = isFocused,
                    chapters = chapters,
                    value = position,
                    valueRange = 0f..(duration.takeIf { it > 0 } ?: 0f),
                    onValueChange = onValueChange,
                    onValueChangeFinished = onValueChangeFinished,
                )
            } else {
                SimpleSlider(
                    modifier = focusModifier,
                    isFocused = isFocused,
                    chapters = chapters,
                    value = position,
                    valueRange = 0f..(duration.takeIf { it > 0 } ?: 0f),
                    onValueChange = onValueChange,
                    onValueChangeFinished = onValueChangeFinished,
                )
            }
            // On top of the slider, so a touch never reaches the slider's own drag handling: pressing must
            // not jump. A track with no known duration falls back to the slider's behaviour.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .testTag(SEEK_SCRUB_TEST_TAG)
                    .onSizeChanged { barWidth = it.width.toFloat() }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val barWidthPx = barWidth
                            if (totalMs <= 0f || barWidthPx <= 0f) return@awaitEachGesture
                            // A finger resting on the bar is reading it: the controls must not fade out from
                            // under it mid drag, which would take this gesture - and the seek it commits - away
                            // with the composable that was running it.
                            controlsVisibilityState?.showControls(Duration.INFINITE)
                            val startMs = anchorMs
                            var targetMs = startMs
                            var previousMs = startMs
                            var dragging = false
                            var lifted = false
                            while (true) {
                                val change =
                                    awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                val travelled = change.position.x - down.position.x
                                if (!dragging && abs(travelled) > touchSlop) dragging = true
                                if (dragging) {
                                    // The slop has already been travelled: discounting it keeps the thumb
                                    // under the finger when the drag starts, not kicking it forward.
                                    val direction = if (travelled < 0f) -1f else 1f
                                    val beyondSlop = (abs(travelled) - touchSlop) * direction
                                    targetMs = (startMs + beyondSlop / barWidthPx * totalMs)
                                        .coerceIn(0f, totalMs)
                                    tickChapter(targetMs, previousMs)
                                    previousMs = targetMs
                                    reportScrubbing(targetMs)
                                }
                                lifted = !change.pressed
                                change.consume()
                                if (lifted) break
                            }
                            if (lifted) {
                                if (!dragging) {
                                    targetMs = (down.position.x / barWidthPx * totalMs).coerceIn(0f, totalMs)
                                }
                                lastSeekChapterIndex = null
                                seekTo(targetMs)
                                seekEnded()
                            }
                            // A gesture taken away by the system commits nothing: the preview just falls
                            // back to where the player really is. Either way the timer goes back on.
                            reportScrubbing(null)
                            controlsVisibilityState?.showControls()
                        }
                    },
            )
        }
    }
}

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MaterialYouSlider(
    modifier: Modifier = Modifier,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    isFocused: Boolean = false,
    chapters: List<Chapter> = emptyList(),
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val interactionSource = remember { MutableInteractionSource() }
    val trackHeight = 8.dp
    val thumbWidth = 4.dp
    val trackThumbGapWidth = 12.dp
    val focusedThumbWidth by animateDpAsState(if (isFocused) 10.dp else thumbWidth, label = "thumbWidth")
    val focusedThumbHeight by animateDpAsState(if (isFocused) 24.dp else 20.dp, label = "thumbHeight")

    Slider(
        value = value,
        valueRange = valueRange,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        interactionSource = interactionSource,
        modifier = modifier.size(24.dp),
        track = { sliderState ->
            val disabledAlpha = 0.4f

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(trackHeight)
                    .chapterGaps(chapters, valueRange.endInclusive),
            ) {
                val min = sliderState.valueRange.start
                val max = sliderState.valueRange.endInclusive
                val range = (max - min).takeIf { it > 0f } ?: 1f
                val playedFraction = ((sliderState.value - min) / range).coerceIn(0f, 1f)
                val playedPixels = size.width * playedFraction

                val endCornerRadius = size.height / 2f
                val insideCornerRadius = 2.dp.toPx()
                val gapHalf = trackThumbGapWidth.toPx() / 2f
                val leftEnd = (playedPixels - gapHalf).coerceIn(0f, size.width)
                val rightStart = (playedPixels + gapHalf).coerceIn(0f, size.width)

                // Inactive track left side
                if (leftEnd > 0f) {
                    drawRoundedRect(
                        offset = Offset(0f, 0f),
                        size = Size(leftEnd, size.height),
                        color = primaryColor.copy(alpha = disabledAlpha),
                        startCornerRadius = endCornerRadius,
                        endCornerRadius = insideCornerRadius,
                    )
                }

                // Inactive track right side
                if (rightStart < size.width) {
                    drawRoundedRect(
                        offset = Offset(rightStart, 0f),
                        size = Size(size.width - rightStart, size.height),
                        color = primaryColor.copy(alpha = disabledAlpha),
                        startCornerRadius = insideCornerRadius,
                        endCornerRadius = endCornerRadius,
                    )
                }

                // Active track
                if (leftEnd > 0f) {
                    drawRoundedRect(
                        offset = Offset(0f, 0f),
                        size = Size(leftEnd, size.height),
                        color = primaryColor,
                        startCornerRadius = endCornerRadius,
                        endCornerRadius = insideCornerRadius,
                    )
                }
            }
        },
        thumb = {
            Box(
                modifier = Modifier
                    .width(focusedThumbWidth)
                    .height(focusedThumbHeight)
                    .background(primaryColor, CircleShape)
                    .then(
                        if (isFocused) {
                            Modifier.border(2.dp, Color.White, CircleShape)
                        } else {
                            Modifier
                        },
                    ),
            )
        },
    )
}

private fun DrawScope.drawRoundedRect(
    offset: Offset,
    size: Size,
    color: Color,
    startCornerRadius: Float,
    endCornerRadius: Float,
) {
    val startCorner = CornerRadius(startCornerRadius, startCornerRadius)
    val endCorner = CornerRadius(endCornerRadius, endCornerRadius)
    val track = RoundRect(
        rect = Rect(Offset(offset.x, 0f), size = Size(size.width, size.height)),
        topLeft = startCorner,
        topRight = endCorner,
        bottomRight = endCorner,
        bottomLeft = startCorner,
    )
    drawPath(
        path = Path().apply {
            addRoundRect(track)
        },
        color = color,
    )
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleSlider(
    modifier: Modifier = Modifier,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    isFocused: Boolean = false,
    chapters: List<Chapter> = emptyList(),
) {
    val thumbSize by animateDpAsState(if (isFocused) 22.dp else 16.dp, label = "thumbSize")
    Slider(
        value = value,
        valueRange = valueRange,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        modifier = modifier.height(24.dp),
        thumb = {
            Box(
                modifier = Modifier
                    .size(thumbSize)
                    .shadow(4.dp, CircleShape)
                    .background(Color.White)
                    .then(
                        if (isFocused) {
                            Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        } else {
                            Modifier
                        },
                    ),
            )
        },
        track = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .chapterGaps(chapters, valueRange.endInclusive)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(Color.White.copy(0.5f)),
            ) {
                if (valueRange.endInclusive > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(value / valueRange.endInclusive)
                            .height(4.dp)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        },
    )
}

@OptIn(UnstableApi::class)
private fun Modifier.chapterGaps(chapters: List<Chapter>, duration: Float): Modifier = drawWithCache {
    val gaps = Path()
    if (duration > 0f) {
        val halfGap = 1.5.dp.toPx()
        chapters.forEach { chapter ->
            if (chapter.startTimeMs > 0 && chapter.startTimeMs < duration) {
                val x = size.width * (chapter.startTimeMs / duration)
                gaps.addRect(Rect(x - halfGap, 0f, x + halfGap, size.height))
            }
        }
    }
    onDrawWithContent {
        clipPath(gaps, ClipOp.Difference) { this@onDrawWithContent.drawContent() }
    }
}
