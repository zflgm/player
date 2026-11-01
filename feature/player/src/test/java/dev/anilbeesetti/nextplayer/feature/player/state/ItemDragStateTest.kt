package dev.anilbeesetti.nextplayer.feature.player.state

import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemDragStateTest {

    @Test
    fun dragUpwardsSlidesThePictureOffTheTop() {
        assertEquals(-1000f, slideOutTarget(offset = -300f, screenHeight = 1000f), 0f)
    }

    @Test
    fun dragDownwardsSlidesThePictureOffTheBottom() {
        assertEquals(1000f, slideOutTarget(offset = 300f, screenHeight = 1000f), 0f)
    }

    /** A release that has not moved the picture yet still has to land somewhere, and it goes down. */
    @Test
    fun unmovedPictureSlidesOffTheBottom() {
        assertEquals(1000f, slideOutTarget(offset = 0f, screenHeight = 1000f), 0f)
    }

    /** The panel the picture uncovers carries the way it is going and the item waiting there. */
    @Test
    fun dragUncoversThePanelWithItsDirectionAndName() = runTest {
        val state = ItemDragState(scope = this)

        state.onDragStart()
        state.onDrag(
            dragAmount = -10f,
            screenHeight = 1000f,
            direction = ItemSwipeDirection.NEXT,
            title = "Second video",
        )

        assertTrue(state.backdropVisible)
        assertEquals(ItemSwipeDirection.NEXT, state.backdropDirection)
        assertEquals("Second video", state.backdropTitle)
    }

    @Test
    fun aNewDragClearsThePanelTheLastOneLeft() = runTest {
        val state = ItemDragState(scope = this)
        state.onDrag(
            dragAmount = -10f,
            screenHeight = 1000f,
            direction = ItemSwipeDirection.NEXT,
            title = "Second video",
        )

        state.onDragStart()

        assertFalse(state.backdropVisible)
        assertNull(state.backdropDirection)
        assertNull(state.backdropTitle)
    }

    /** Heading past an end of the list the panel is still there, with nothing to promise on it. */
    @Test
    fun panelStaysBlankWhereTheListOfItemsEnds() = runTest {
        val state = ItemDragState(scope = this)

        state.onDrag(
            dragAmount = -10f,
            screenHeight = 1000f,
            direction = null,
            title = null,
        )

        assertTrue(state.backdropVisible)
        assertNull(state.backdropDirection)
        assertNull(state.backdropTitle)
    }

    /**
     * The picture answers the finger where it is, not one animation later: a drag event is a write, and events
     * arrive faster than frames.
     */
    @Test
    fun everyDragPixelMovesThePictureImmediately() = runTest {
        val state = ItemDragState(scope = this)
        state.onDragStart()

        state.onDrag(dragAmount = -40f, screenHeight = 1000f, direction = ItemSwipeDirection.NEXT, title = null)
        state.onDrag(dragAmount = -10f, screenHeight = 1000f, direction = ItemSwipeDirection.NEXT, title = null)

        assertEquals(-50f, state.offset, 0f)
    }

    @Test
    fun aDragLongerThanTheScreenStopsAtItsEdge() = runTest {
        val state = ItemDragState(scope = this)
        state.onDragStart()

        state.onDrag(dragAmount = -5000f, screenHeight = 1000f, direction = ItemSwipeDirection.NEXT, title = null)

        assertEquals(-1000f, state.offset, 0f)
    }

    /** Throwing a second swipe while the first picture is still gliding back: the finger owns it from pixel one. */
    @Test
    fun aNewDragTakesThePictureBackFromAGlide() {
        val state = ItemDragState(scope = glideScope())
        state.onDrag(dragAmount = -100f, screenHeight = 1000f, direction = ItemSwipeDirection.NEXT, title = null)

        state.onDragEnd(changingItem = false, screenHeight = 1000f)
        state.onDragStart()
        state.onDrag(dragAmount = 30f, screenHeight = 1000f, direction = ItemSwipeDirection.PREVIOUS, title = null)

        assertEquals(30f, state.offset, 0f)
    }

    /** A scope that can run an animation on the JVM: the release glide waits on a frame clock of its own. */
    private fun glideScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + BroadcastFrameClock())
}
