package com.zflgm.player.navigation

import android.content.Context
import androidx.core.net.toUri
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.zflgm.player.feature.more.navigation.historyEntry
import com.zflgm.player.feature.more.navigation.navigateToHistory
import com.zflgm.player.feature.more.navigation.navigateToTrash
import com.zflgm.player.feature.more.navigation.trashEntry
import com.zflgm.player.feature.videopicker.navigation.navigateToVault
import com.zflgm.player.feature.videopicker.navigation.vaultEntry
import com.zflgm.player.settings.Setting
import com.zflgm.player.settings.navigation.aboutPreferencesEntry
import com.zflgm.player.settings.navigation.appearancePreferencesEntry
import com.zflgm.player.settings.navigation.audioPreferencesEntry
import com.zflgm.player.settings.navigation.folderPreferencesEntry
import com.zflgm.player.settings.navigation.generalPreferencesEntry
import com.zflgm.player.settings.navigation.gesturePreferencesEntry
import com.zflgm.player.settings.navigation.librariesEntry
import com.zflgm.player.settings.navigation.mediaLibraryPreferencesEntry
import com.zflgm.player.settings.navigation.navigateToAboutPreferences
import com.zflgm.player.settings.navigation.navigateToAppearancePreferences
import com.zflgm.player.settings.navigation.navigateToAudioPreferences
import com.zflgm.player.settings.navigation.navigateToFolderPreferencesScreen
import com.zflgm.player.settings.navigation.navigateToGeneralPreferences
import com.zflgm.player.settings.navigation.navigateToGesturePreferences
import com.zflgm.player.settings.navigation.navigateToLibraries
import com.zflgm.player.settings.navigation.navigateToMediaLibraryPreferencesScreen
import com.zflgm.player.settings.navigation.navigateToPlayerPreferences
import com.zflgm.player.settings.navigation.navigateToSubtitlePreferences
import com.zflgm.player.settings.navigation.navigateToThumbnailPreferencesScreen
import com.zflgm.player.settings.navigation.playerPreferencesEntry
import com.zflgm.player.settings.navigation.settingsEntry
import com.zflgm.player.settings.navigation.subtitlePreferencesEntry
import com.zflgm.player.settings.navigation.thumbnailPreferencesEntry

/**
 * [currentStack] is resolved when a callback fires instead of being captured at registration time:
 * the settings entry can be created before the user ever switches to its tab, and the gear opens
 * settings on whichever tab stack is currently selected.
 */
fun EntryProviderScope<NavKey>.settingsNavGraph(
    context: Context,
    currentStack: () -> NavBackStack<NavKey>,
) {
    settingsEntry(
        onNavigateUp = { currentStack().removeLastIfNotRoot() },
        canNavigateUp = { currentStack().size > 1 },
        onItemClick = { setting ->
            val backStack = currentStack()
            when (setting) {
                Setting.APPEARANCE -> backStack.navigateToAppearancePreferences()
                Setting.MEDIA_LIBRARY -> backStack.navigateToMediaLibraryPreferencesScreen()
                Setting.PLAYER -> backStack.navigateToPlayerPreferences()
                Setting.GESTURES -> backStack.navigateToGesturePreferences()
                Setting.AUDIO -> backStack.navigateToAudioPreferences()
                Setting.SUBTITLE -> backStack.navigateToSubtitlePreferences()
                Setting.GENERAL -> backStack.navigateToGeneralPreferences()
                Setting.VAULT -> backStack.navigateToVault()
                Setting.TRASH -> backStack.navigateToTrash()
                Setting.HISTORY -> backStack.navigateToHistory()
                Setting.ABOUT -> backStack.navigateToAboutPreferences()
            }
        },
    )
    appearancePreferencesEntry(
        onNavigateUp = { currentStack().removeLastIfNotRoot() },
    )
    mediaLibraryPreferencesEntry(
        onNavigateUp = { currentStack().removeLastIfNotRoot() },
        onFolderSettingClick = { currentStack().navigateToFolderPreferencesScreen() },
        onThumbnailSettingClick = { currentStack().navigateToThumbnailPreferencesScreen() },
    )
    thumbnailPreferencesEntry(
        onNavigateUp = { currentStack().removeLastIfNotRoot() },
    )
    folderPreferencesEntry(
        onNavigateUp = { currentStack().removeLastIfNotRoot() },
    )
    playerPreferencesEntry(
        onNavigateUp = { currentStack().removeLastIfNotRoot() },
    )
    gesturePreferencesEntry(
        onNavigateUp = { currentStack().removeLastIfNotRoot() },
    )
    audioPreferencesEntry(
        onNavigateUp = { currentStack().removeLastIfNotRoot() },
    )
    subtitlePreferencesEntry(
        onNavigateUp = { currentStack().removeLastIfNotRoot() },
    )
    generalPreferencesEntry(
        onNavigateUp = { currentStack().removeLastIfNotRoot() },
    )
    aboutPreferencesEntry(
        onLibrariesClick = { currentStack().navigateToLibraries() },
        onNavigateUp = { currentStack().removeLastIfNotRoot() },
    )
    librariesEntry(
        onNavigateUp = { currentStack().removeLastIfNotRoot() },
    )
    historyEntry(
        onNavigateUp = { currentStack().removeLastIfNotRoot() },
        onPlayVideo = { context.startPlayback(it.toUri()) },
    )
    trashEntry(
        onNavigateUp = { currentStack().removeLastIfNotRoot() },
        onPlayVideo = { context.startPlayback(it.toUri()) },
    )
    vaultEntry(
        onNavigateUp = { currentStack().removeLastIfNotRoot() },
        // Vault files are served through FileProvider, so read access must be granted at
        // playback time for both PlayerActivity and the (separate) PlayerService component.
        onPlayVideo = { uri -> context.startPlayback(uri, grantReadPermission = true) },
        onPlayVideos = { uris -> context.startPlayback(uris, grantReadPermission = true) },
    )
}
