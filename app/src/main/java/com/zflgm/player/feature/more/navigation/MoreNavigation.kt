package com.zflgm.player.feature.more.navigation

import androidx.compose.runtime.SideEffect
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.zflgm.player.feature.more.screens.history.HistoryScreen
import com.zflgm.player.feature.more.screens.history.HistoryViewModel
import com.zflgm.player.feature.more.screens.trash.TrashScreen
import com.zflgm.player.feature.more.screens.trash.TrashViewModel
import kotlinx.serialization.Serializable
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Serializable
object HistoryRoute : NavKey

@Serializable
object TrashRoute : NavKey

fun EntryProviderScope<NavKey>.historyEntry(
    onNavigateUp: () -> Unit,
    onPlayVideo: (String) -> Unit,
) {
    entry<HistoryRoute> {
        val output = HistoryViewModel.Output(
            navigateUp = onNavigateUp,
            playVideo = onPlayVideo,
        )
        val viewModel = koinViewModel<HistoryViewModel>(
            parameters = { parametersOf(output) },
        )
        SideEffect { viewModel.output = output }
        HistoryScreen(viewModel = viewModel)
    }
}

fun EntryProviderScope<NavKey>.trashEntry(
    onNavigateUp: () -> Unit,
    onPlayVideo: (String) -> Unit,
) {
    entry<TrashRoute> {
        val output = TrashViewModel.Output(
            navigateUp = onNavigateUp,
            playVideo = onPlayVideo,
        )
        val viewModel = koinViewModel<TrashViewModel>(
            parameters = { parametersOf(output) },
        )
        SideEffect { viewModel.output = output }
        TrashScreen(viewModel = viewModel)
    }
}

fun NavBackStack<NavKey>.navigateToHistory() {
    add(HistoryRoute)
}

fun NavBackStack<NavKey>.navigateToTrash() {
    add(TrashRoute)
}
