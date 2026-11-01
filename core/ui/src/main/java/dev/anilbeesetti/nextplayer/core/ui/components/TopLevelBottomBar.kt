package dev.anilbeesetti.nextplayer.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf

val LocalTopLevelBottomBarVisibleSetter = compositionLocalOf<(Boolean) -> Unit> { {} }

@Composable
fun BindTopLevelBottomBarVisible(visible: Boolean) {
    val setter = LocalTopLevelBottomBarVisibleSetter.current

    DisposableEffect(visible, setter) {
        setter(visible)
        onDispose { setter(true) }
    }
}
