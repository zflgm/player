package dev.anilbeesetti.nextplayer.navigation

import android.content.Context
import androidx.core.net.toUri
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import dev.anilbeesetti.nextplayer.feature.more.navigation.historyEntry
import dev.anilbeesetti.nextplayer.feature.more.navigation.navigateToHistory
import dev.anilbeesetti.nextplayer.feature.more.navigation.navigateToTrash
import dev.anilbeesetti.nextplayer.feature.more.navigation.trashEntry
import dev.anilbeesetti.nextplayer.feature.videopicker.navigation.navigateToVault
import dev.anilbeesetti.nextplayer.feature.videopicker.navigation.vaultEntry
import dev.anilbeesetti.nextplayer.settings.Setting
import dev.anilbeesetti.nextplayer.settings.navigation.aboutPreferencesEntry
import dev.anilbeesetti.nextplayer.settings.navigation.appearancePreferencesEntry
import dev.anilbeesetti.nextplayer.settings.navigation.audioPreferencesEntry
import dev.anilbeesetti.nextplayer.settings.navigation.folderPreferencesEntry
import dev.anilbeesetti.nextplayer.settings.navigation.generalPreferencesEntry
import dev.anilbeesetti.nextplayer.settings.navigation.gesturePreferencesEntry
import dev.anilbeesetti.nextplayer.settings.navigation.librariesEntry
import dev.anilbeesetti.nextplayer.settings.navigation.mediaLibraryPreferencesEntry
import dev.anilbeesetti.nextplayer.settings.navigation.navigateToAboutPreferences
import dev.anilbeesetti.nextplayer.settings.navigation.navigateToAppearancePreferences
import dev.anilbeesetti.nextplayer.settings.navigation.navigateToAudioPreferences
import dev.anilbeesetti.nextplayer.settings.navigation.navigateToFolderPreferencesScreen
import dev.anilbeesetti.nextplayer.settings.navigation.navigateToGeneralPreferences
import dev.anilbeesetti.nextplayer.settings.navigation.navigateToGesturePreferences
import dev.anilbeesetti.nextplayer.settings.navigation.navigateToLibraries
import dev.anilbeesetti.nextplayer.settings.navigation.navigateToMediaLibraryPreferencesScreen
import dev.anilbeesetti.nextplayer.settings.navigation.navigateToPlayerPreferences
import dev.anilbeesetti.nextplayer.settings.navigation.navigateToSubtitlePreferences
import dev.anilbeesetti.nextplayer.settings.navigation.navigateToThumbnailPreferencesScreen
import dev.anilbeesetti.nextplayer.settings.navigation.playerPreferencesEntry
import dev.anilbeesetti.nextplayer.settings.navigation.settingsEntry
import dev.anilbeesetti.nextplayer.settings.navigation.subtitlePreferencesEntry
import dev.anilbeesetti.nextplayer.settings.navigation.thumbnailPreferencesEntry

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
