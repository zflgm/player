package dev.anilbeesetti.nextplayer.core.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

@Composable
fun NextDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)? = null,
    dialogProperties: DialogProperties = NextDialogDefaults.dialogProperties,
) {
    val configuration = LocalConfiguration.current
    // M3 AlertDialog's own enter transition is ~60ms and not configurable, so drive a visible
    // fade+scale on the dialog surface itself via its modifier (applied inside the dialog window).
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { revealed = true }
    val enterProgress by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = tween(NextDialogDefaults.enterDurationMillis, easing = FastOutSlowInEasing),
        label = "nextDialogEnter",
    )

    AlertDialog(
        title = title,
        text = { Column { content() } },
        modifier = modifier
            .widthIn(max = configuration.screenWidthDp.dp - NextDialogDefaults.dialogMargin * 2)
            .graphicsLayer {
                alpha = enterProgress
                val scale = NextDialogDefaults.enterInitialScale +
                    (1f - NextDialogDefaults.enterInitialScale) * enterProgress
                scaleX = scale
                scaleY = scale
            },
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        properties = dialogProperties,
    )
}

@Composable
fun NextDialogWithDoneAndCancelButtons(
    title: String,
    onDoneClick: () -> Unit,
    onDismissClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    NextDialog(
        title = { Text(text = title) },
        confirmButton = { DoneButton(onClick = onDoneClick) },
        dismissButton = { CancelButton(onClick = onDismissClick) },
        onDismissRequest = onDismissClick,
        content = content,
    )
}

object NextDialogDefaults {
    val dialogProperties: DialogProperties = DialogProperties(
        usePlatformDefaultWidth = false,
        dismissOnBackPress = true,
        dismissOnClickOutside = true,
        decorFitsSystemWindows = true,
    )
    val dialogMargin: Dp = 16.dp
    const val enterDurationMillis = 180
    const val enterInitialScale = 0.92f
}
