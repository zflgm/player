package dev.anilbeesetti.nextplayer.feature.player.ui

import dev.anilbeesetti.nextplayer.feature.player.state.ItemSwipeDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SwipeToChangeItemDirectionTest {
    @Test
    fun upwardSwipePastThresholdSelectsNextItem() {
        assertEquals(ItemSwipeDirection.NEXT, swipeToChangeItemDirection(verticalDragDistance = -200f, screenHeight = 1000f))
    }

    @Test
    fun downwardSwipePastThresholdSelectsPreviousItem() {
        assertEquals(ItemSwipeDirection.PREVIOUS, swipeToChangeItemDirection(verticalDragDistance = 200f, screenHeight = 1000f))
    }

    @Test
    fun distanceExactlyAtThresholdCountsAsASwipe() {
        assertEquals(ItemSwipeDirection.NEXT, swipeToChangeItemDirection(verticalDragDistance = -150f, screenHeight = 1000f))
        assertEquals(ItemSwipeDirection.PREVIOUS, swipeToChangeItemDirection(verticalDragDistance = 150f, screenHeight = 1000f))
    }

    @Test
    fun shortSwipeKeepsTheCurrentItem() {
        assertNull(swipeToChangeItemDirection(verticalDragDistance = 149f, screenHeight = 1000f))
        assertNull(swipeToChangeItemDirection(verticalDragDistance = -149f, screenHeight = 1000f))
        assertNull(swipeToChangeItemDirection(verticalDragDistance = 0f, screenHeight = 1000f))
    }
}
