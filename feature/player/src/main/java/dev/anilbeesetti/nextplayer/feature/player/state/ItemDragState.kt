package dev.anilbeesetti.nextplayer.feature.player.state

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** How long the picture takes to leave the screen once the swipe has crossed its threshold. */
private const val SLIDE_OFF_SCREEN_DURATION_MS = 200

/** The short swipe that did not reach an item springs back instead of gliding. */
private fun bounceSpec(): AnimationSpec<Float> = spring(stiffness = Spring.StiffnessMediumLow)

/**
 * Which neighbour a portrait swipe is heading for. Public: the state, the gesture box and the panel it uncovers
 * are three declarations in two packages, all of which name it.
 */
enum class ItemSwipeDirection {
    NEXT,
    PREVIOUS,
}

/**
 * The picture a portrait swipe drags along, and the panel it uncovers.
 *
 * [offset] goes on the surface's graphics layer. A SurfaceView layer does follow a translation - it is a rotation
 * it cannot take, which is what the TextureView path in PlayerContentFrame is for - so the video itself moves with
 * the finger and no frame has to be copied to animate the feed.
 *
 * The picture travels over the window's black, so what it leaves behind gets a panel: [backdropVisible] turns it
 * on, [backdropDirection] names the way it is going and [backdropTitle] the item waiting there.
 *
 * While the finger is down [offset] is a plain state write, not an animation: a drag event arrives faster than a
 * frame, and one coroutine per event is a queue the rest of the screen has to wait behind.
 */
@Stable
class ItemDragState(
    private val scope: CoroutineScope,
) {
    /** How far the surface is offset by, in the direction the finger is going. */
    val offset: Float
        get() = if (gliding) glide.value else dragOffset

    /**
     * The neighbour the swipe is heading for, or null where the list has nothing to show that way. Public with
     * this state and the panel that reads it, which both live outside this package.
     */
    var backdropDirection: ItemSwipeDirection? by mutableStateOf(null)
        private set

    var backdropTitle by mutableStateOf<String?>(null)
        private set

    /** On from the first drag pixel until the release animation has run out. */
    var backdropVisible by mutableStateOf(false)
        private set

    private var dragOffset by mutableFloatStateOf(0f)

    private val glide = Animatable(0f)

    /** True while a release animation has taken [offset] off the finger. */
    private var gliding by mutableStateOf(false)

    private var glideJob: Job? = null

    fun onDragStart() {
        stopGlide()
        dragOffset = 0f
        backdropDirection = null
        backdropTitle = null
        backdropVisible = false
    }

    fun onDrag(
        dragAmount: Float,
        screenHeight: Float,
        direction: ItemSwipeDirection?,
        title: String?,
    ) {
        backdropVisible = true
        backdropDirection = direction
        backdropTitle = title
        dragOffset = (dragOffset + dragAmount).coerceIn(-screenHeight, screenHeight)
    }

    fun onDragCancelled() {
        glideTo(target = 0f, spec = bounceSpec(), then = ::hideBackdrop)
    }

    /**
     * [changingItem] is the swipe having crossed its threshold: the picture then leaves the way the finger was
     * travelling and the caller switches the item, while a short swipe springs back to the surface.
     */
    fun onDragEnd(changingItem: Boolean, screenHeight: Float) {
        if (!changingItem) {
            glideTo(target = 0f, spec = bounceSpec(), then = ::hideBackdrop)
            return
        }
        glideTo(
            target = slideOutTarget(dragOffset, screenHeight),
            spec = tween(
                durationMillis = SLIDE_OFF_SCREEN_DURATION_MS,
                easing = FastOutLinearInEasing,
            ),
            then = {
                // The switch is already under way, so the picture is put back rather than slid in: sliding back
                // in would carry the frame that just left.
                dragOffset = 0f
                hideBackdrop()
            },
        )
    }

    /** Takes [offset] off the finger and runs it to [target], handing the picture back through [then] at the end. */
    private fun glideTo(
        target: Float,
        spec: AnimationSpec<Float>,
        then: () -> Unit,
    ) {
        stopGlide()
        // Runs on the main dispatcher, so it cannot be overtaken by a drag starting in the same frame.
        glideJob = scope.launch {
            glide.snapTo(dragOffset)
            gliding = true
            glide.animateTo(targetValue = target, animationSpec = spec)
            dragOffset = target
            gliding = false
            then()
        }
    }

    private fun stopGlide() {
        glideJob?.cancel()
        glideJob = null
        gliding = false
    }

    private fun hideBackdrop() {
        backdropDirection = null
        backdropTitle = null
        backdropVisible = false
    }
}

/**
 * Where a released picture finishes its travel: off the top for a drag upwards, which is the next item, and off
 * the bottom otherwise.
 */
internal fun slideOutTarget(offset: Float, screenHeight: Float): Float =
    if (offset < 0f) -screenHeight else screenHeight
