package com.zflgm.player.settings.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.zflgm.player.settings.Setting
import com.zflgm.player.settings.SettingsOutput
import com.zflgm.player.settings.SettingsScreen
import kotlinx.serialization.Serializable

@Serializable
object SettingsRoute : NavKey

fun NavBackStack<NavKey>.navigateToSettings() {
    add(SettingsRoute)
}

fun EntryProviderScope<NavKey>.settingsEntry(
    onNavigateUp: () -> Unit,
    onItemClick: (Setting) -> Unit,
    canNavigateUp: () -> Boolean = { true },
) {
    entry<SettingsRoute> {
        SettingsScreen(
            output = SettingsOutput(
                navigateUp = onNavigateUp,
                openSetting = onItemClick,
                canNavigateUp = canNavigateUp,
            ),
        )
    }
}
