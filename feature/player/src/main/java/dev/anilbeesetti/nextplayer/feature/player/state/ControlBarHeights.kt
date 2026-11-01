package dev.anilbeesetti.nextplayer.feature.player.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Measured heights of the two portrait control bars, in pixels, written by the layout and read by the
 * tap handler. The pause region is the video strip between the bars, so it cannot be a fixed fraction
 * of the screen: it moves with the title bar height, the seekbar rows and the system bar insets.
 */
@Stable
class ControlBarHeights {
    var top by mutableIntStateOf(0)
        internal set

    var bottom by mutableIntStateOf(0)
        internal set
}

@Composable
fun rememberControlBarHeights(): ControlBarHeights = remember { ControlBarHeights() }
