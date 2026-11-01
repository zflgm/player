package com.zflgm.player.navigation

import android.content.Context
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.zflgm.player.feature.network.navigation.addConnectionEntry
import com.zflgm.player.feature.network.navigation.navigateToAddConnection
import com.zflgm.player.feature.network.navigation.navigateToNetworkBrowse
import com.zflgm.player.feature.network.navigation.networkBrowseEntry
import com.zflgm.player.feature.network.navigation.networkEntry
import com.zflgm.player.settings.navigation.navigateToSettings

fun EntryProviderScope<NavKey>.networkNavGraph(
    context: Context,
    backStack: NavBackStack<NavKey>,
) {
    networkEntry(
        onAddConnection = { backStack.navigateToAddConnection() },
        onEditConnection = { id -> backStack.navigateToAddConnection(id) },
        onOpenConnection = { id -> backStack.navigateToNetworkBrowse(id) },
        onSettingsClick = backStack::navigateToSettings,
        onOpenStream = { uri -> context.startPlayback(uri) },
        onConnectToDiscoveredHost = { host -> backStack.navigateToAddConnection(prefillHost = host) },
    )

    addConnectionEntry(
        onNavigateUp = { backStack.removeLastIfNotRoot() },
    )

    networkBrowseEntry(
        onNavigateUp = { backStack.removeLastIfNotRoot() },
        onPlayVideos = { uris, startUri -> context.startPlayback(uris, startUri) },
        onNavigateToFolder = { id, path -> backStack.navigateToNetworkBrowse(id, path) },
    )
}
