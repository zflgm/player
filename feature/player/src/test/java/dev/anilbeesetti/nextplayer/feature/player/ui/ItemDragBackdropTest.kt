package dev.anilbeesetti.nextplayer.feature.player.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import dev.anilbeesetti.nextplayer.core.ui.theme.NextPlayerTheme
import dev.anilbeesetti.nextplayer.feature.player.state.ItemDragState
import dev.anilbeesetti.nextplayer.feature.player.state.ItemSwipeDirection
import dev.anilbeesetti.nextplayer.feature.player.state.ItemSwipeDirection.NEXT
import dev.anilbeesetti.nextplayer.feature.player.state.ItemSwipeDirection.PREVIOUS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-port")
class ItemDragBackdropTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** A panel that stops short of the space it is handed leaves the window's black beside the picture. */
    @Test
    fun panelFillsTheSpaceItIsGiven() {
        setContent(direction = NEXT, title = "Episode Seven")

        val space = composeRule.onNodeWithTag(TEST_SPACE_TAG).fetchSemanticsNode().boundsInRoot
        val panel = composeRule.onNodeWithTag(ITEM_DRAG_BACKDROP_TEST_TAG).fetchSemanticsNode().boundsInRoot

        assertTrue("the panel was given no space", space.width > 0f && space.height > 0f)
        assertEquals("left", space.left, panel.left, 0f)
        assertEquals("top", space.top, panel.top, 0f)
        assertEquals("width", space.width, panel.width, 0f)
        assertEquals("height", space.height, panel.height, 0f)
    }

    @Test
    fun directionIsShownAsAHeadlineWithTheItemUnderIt() {
        setContent(direction = NEXT, title = "Episode Seven")

        composeRule.onNodeWithText("Next").assertIsDisplayed()
        composeRule.onNodeWithText("Episode Seven").assertIsDisplayed()
    }

    @Test
    fun downwardDragIsShownAsThePreviousItem() {
        setContent(direction = PREVIOUS, title = "Episode Six")

        composeRule.onNodeWithText("Previous").assertIsDisplayed()
    }

    /** The word is what has to be read while the picture is still moving, so it is set larger than the name. */
    @Test
    fun directionWordIsPrintedLargerThanTheItemName() {
        setContent(direction = NEXT, title = "Episode Seven")

        val direction = composeRule.onNodeWithText("Next").fetchSemanticsNode().boundsInRoot
        val name = composeRule.onNodeWithText("Episode Seven").fetchSemanticsNode().boundsInRoot

        assertTrue(
            "the word was ${direction.height}px tall against a name of ${name.height}px",
            direction.height > name.height * 1.4f,
        )
    }

    /**
     * The panel replaces the window's black, so a tone that reads as black again is the one regression this
     * screen has to keep out. Measured against the player's own dark scheme, not a default one.
     */
    @Test
    fun panelToneIsAFarCryFromTheBlackItReplaces() {
        composeRule.setContent {
            NextPlayerTheme(darkTheme = true, dynamicColor = false) {
                val scheme = MaterialTheme.colorScheme
                val panel = lightness(scheme.surfaceVariant)
                assertTrue("panel tone is too dark to read: $panel", panel > 0.2f)
                // The tone this panel used to be painted with, which read as nothing against the window.
                assertTrue(
                    "panel tone is too close to the near-black it replaces",
                    panel > lightness(scheme.surface) * 3f,
                )
            }
        }
    }

    /**
     * The word is printed in the band the picture is vacating. Dead centre it is covered by the picture that has
     * not left yet, which is the half of the drag the user is still watching.
     */
    @Test
    fun wordIsPrintedBelowThePictureThatIsLeavingUpwards() {
        setContent(direction = NEXT, title = "Episode Seven")

        val space = composeRule.onNodeWithTag(TEST_SPACE_TAG).fetchSemanticsNode().boundsInRoot
        val word = composeRule.onNodeWithText("Next").fetchSemanticsNode().boundsInRoot

        assertTrue(
            "the word sat at ${word.top} in a space of height ${space.height}",
            word.top > space.top + space.height / 2f,
        )
    }

    @Test
    fun wordIsPrintedAboveThePictureThatIsLeavingDownwards() {
        setContent(direction = PREVIOUS, title = "Episode Six")

        val space = composeRule.onNodeWithTag(TEST_SPACE_TAG).fetchSemanticsNode().boundsInRoot
        val word = composeRule.onNodeWithText("Previous").fetchSemanticsNode().boundsInRoot

        assertTrue(
            "the word sat at ${word.bottom} in a space of height ${space.height}",
            word.bottom < space.top + space.height / 2f,
        )
    }

    /** The name is the part a queue may not have, and the direction still says what the gesture is about to do. */
    @Test
    fun directionIsShownWithoutAName() {
        setContent(direction = NEXT, title = null)

        composeRule.onNodeWithText("Next").assertIsDisplayed()
    }

    @Test
    fun blankNameAddsNoLine() {
        setContent(direction = NEXT, title = "")

        composeRule.onNodeWithText("Next").assertIsDisplayed()
        composeRule.onAllNodes(matcher = hasText("")).assertCountEquals(0)
    }

    /** Past an end of the list there is no item to promise, so the panel names no direction either. */
    @Test
    fun noDirectionIsNamedWhereTheListOfItemsEnds() {
        setContent(direction = null, title = "Episode Seven")

        composeRule.onNodeWithText("Next").assertDoesNotExist()
        composeRule.onNodeWithText("Previous").assertDoesNotExist()
    }

    /**
     * The panel is drawn over the surface, so the one thing it may not do is hide the picture: what it paints is
     * the band on the side the picture is letting go of, and nothing over the frame that is still travelling.
     */
    @Test
    fun upwardsDragPaintsUnderThePictureAndNotOverIt() {
        val image = capturePanel(direction = NEXT)

        // A column at the left edge, clear of the centred word and name: there the band is the only paint.
        val overThePicture = image.getPixel(EDGE_SAMPLE_X, (image.height * 0.5f).toInt())
        val belowThePicture = image.getPixel(EDGE_SAMPLE_X, (image.height * 0.85f).toInt())

        assertTrue(
            "nothing was painted in the band the picture left: $belowThePicture",
            isPanelTone(belowThePicture),
        )
        assertTrue(
            "the panel painted over the picture: $overThePicture",
            !isPanelTone(overThePicture),
        )
    }

    @Test
    fun downwardsDragPaintsAboveThePictureAndNotOverIt() {
        val image = capturePanel(direction = PREVIOUS)

        assertTrue(
            "nothing was painted in the band the picture left",
            isPanelTone(image.getPixel(EDGE_SAMPLE_X, (image.height * 0.15f).toInt())),
        )
        assertTrue(
            "the panel painted over the picture",
            !isPanelTone(image.getPixel(EDGE_SAMPLE_X, (image.height * 0.5f).toInt())),
        )
    }

    /** The frame the phone reports: 1080x2340 with a 16:9 picture centred in it. */
    @Test
    fun upwardDragPaintsFromThePicturesNewBottomEdgeToTheFrame() {
        val band = backdropBand(
            frameWidth = 1080f,
            frameHeight = 2340f,
            pictureTop = 866f,
            pictureBottom = 1474f,
            offset = -500f,
            direction = NEXT,
        )

        assertNotNull(band)
        assertEquals("left", 0f, band!!.left, 0f)
        assertEquals("top", 974f, band.top, 0f)
        assertEquals("right", 1080f, band.right, 0f)
        assertEquals("bottom", 2340f, band.bottom, 0f)
    }

    @Test
    fun downwardDragPaintsFromTheFrameToThePicturesNewTopEdge() {
        val band = backdropBand(
            frameWidth = 1080f,
            frameHeight = 2340f,
            pictureTop = 866f,
            pictureBottom = 1474f,
            offset = 500f,
            direction = PREVIOUS,
        )

        assertNotNull(band)
        assertEquals("top", 0f, band!!.top, 0f)
        assertEquals("bottom", 1366f, band.bottom, 0f)
    }

    /** A swipe towards an end of the list names no neighbour, but the picture has still let go of a side. */
    @Test
    fun anUnnamedDragStillPaintsTheSideTheFingerCameFrom() {
        val upwards = backdropBand(
            frameWidth = 1080f,
            frameHeight = 2340f,
            pictureTop = 0f,
            pictureBottom = 2340f,
            offset = -300f,
            direction = null,
        )
        val downwards = backdropBand(
            frameWidth = 1080f,
            frameHeight = 2340f,
            pictureTop = 0f,
            pictureBottom = 2340f,
            offset = 300f,
            direction = null,
        )

        assertEquals("top", 2040f, upwards!!.top, 0f)
        assertEquals("bottom", 2340f, upwards.bottom, 0f)
        assertEquals("top", 0f, downwards!!.top, 0f)
        assertEquals("bottom", 300f, downwards.bottom, 0f)
    }

    /** A full height picture that has not moved has uncovered nothing, so there is no band to paint. */
    @Test
    fun nothingIsPaintedWhereThePictureHasNotLeft() {
        assertNull(
            backdropBand(
                frameWidth = 1080f,
                frameHeight = 2340f,
                pictureTop = 0f,
                pictureBottom = 2340f,
                offset = 0f,
                direction = NEXT,
            ),
        )
    }

    /** Past the edge of the frame the band stops at that edge, rather than growing over the picture. */
    @Test
    fun aPictureDraggedOffTheFrameLeavesTheFrameItIsIn() {
        val band = backdropBand(
            frameWidth = 1080f,
            frameHeight = 2340f,
            pictureTop = 866f,
            pictureBottom = 1474f,
            offset = -2340f,
            direction = NEXT,
        )

        assertEquals("top", 0f, band!!.top, 0f)
        assertEquals("bottom", 2340f, band.bottom, 0f)
    }

    /** However far the finger goes, the band stays behind the picture and never catches up with it. */
    @Test
    fun theBandNeverReachesThePictureThatIsTravelling() {
        listOf(-10f, -200f, -900f, -2340f).forEach { offset ->
            val band = backdropBand(
                frameWidth = 1080f,
                frameHeight = 2340f,
                pictureTop = 866f,
                pictureBottom = 1474f,
                offset = offset,
                direction = NEXT,
            )
            val pictureTop = (866f + offset).coerceAtLeast(0f)
            val pictureBottom = (1474f + offset).coerceAtMost(2340f)
            if (band != null && pictureBottom > pictureTop) {
                assertTrue(
                    "a drag of $offset painted from ${band.top} over a picture at $pictureTop..$pictureBottom",
                    band.top >= pictureBottom,
                )
            }
        }
    }

    private fun setContent(
        direction: ItemSwipeDirection?,
        title: String?,
    ) {
        composeRule.setContent {
            NextPlayerTheme(darkTheme = true, dynamicColor = false) {
                Box(modifier = Modifier.fillMaxSize().testTag(TEST_SPACE_TAG)) {
                    ItemDragBackdrop(
                        state = draggedState(direction, title),
                        pictureTop = 0f,
                        pictureBottom = 0f,
                    )
                }
            }
        }
    }

    /** The picture filling three fifths of the frame from a third of the way down, as it is on a phone. */
    private fun capturePanel(direction: ItemSwipeDirection?): android.graphics.Bitmap {
        composeRule.setContent {
            val density = LocalDensity.current
            NextPlayerTheme(darkTheme = true, dynamicColor = false) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize().testTag(TEST_SPACE_TAG)) {
                    val frameHeight = with(density) { maxHeight.toPx() }
                    ItemDragBackdrop(
                        state = draggedState(direction, title = null),
                        pictureTop = frameHeight * 0.3f,
                        pictureBottom = frameHeight * 0.7f,
                    )
                }
            }
        }
        return composeRule.onNodeWithTag(ITEM_DRAG_BACKDROP_TEST_TAG).captureToImage().asAndroidBitmap()
    }

    private fun draggedState(
        direction: ItemSwipeDirection?,
        title: String?,
    ): ItemDragState = ItemDragState(CoroutineScope(Dispatchers.Unconfined)).apply {
        onDragStart()
        // One pixel off the edge: the band is where the drag has got to, and these tests fix the finger there.
        onDrag(
            dragAmount = if (direction == PREVIOUS) 1f else -1f,
            screenHeight = 10_000f,
            direction = direction,
            title = title,
        )
    }

    private fun isPanelTone(pixel: Int): Boolean {
        val tone = Color(0xFF3F4948)
        return android.graphics.Color.red(pixel) in (tone.red * 255).toInt() - 8..(tone.red * 255).toInt() + 8 &&
            android.graphics.Color.green(pixel) in (tone.green * 255).toInt() - 8..(tone.green * 255).toInt() + 8 &&
            android.graphics.Color.blue(pixel) in (tone.blue * 255).toInt() - 8..(tone.blue * 255).toInt() + 8
    }

    private fun lightness(color: Color): Float =
        0.299f * color.red + 0.587f * color.green + 0.114f * color.blue

    private companion object {
        const val TEST_SPACE_TAG = "item_drag_backdrop_test_space"

        /** Clear of the centred word, name and icon at any width the tests are run at. */
        const val EDGE_SAMPLE_X = 8
    }
}
