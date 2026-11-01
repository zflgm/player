package dev.anilbeesetti.nextplayer.feature.player.extensions

import android.view.TextureView
import android.view.View
import android.view.ViewGroup

/**
 * Applies [action] to every [TextureView] in the view tree, that is where video frames are drawn when the canvas is
 * rotated.
 */
internal fun View.forEachTextureView(action: (TextureView) -> Unit) {
    if (this is TextureView) {
        action(this)
    } else if (this is ViewGroup) {
        for (index in 0 until childCount) {
            getChildAt(index).forEachTextureView(action)
        }
    }
}
