package dev.anilbeesetti.nextplayer.navigation

import androidx.annotation.StringRes
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.Scene
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.components.tvFocusRing
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons
import dev.anilbeesetti.nextplayer.feature.network.navigation.NetworkRoute
import dev.anilbeesetti.nextplayer.feature.playlist.navigation.PlaylistListRoute
import dev.anilbeesetti.nextplayer.feature.videopicker.navigation.MediaPickerRoute
import dev.anilbeesetti.nextplayer.settings.navigation.SettingsRoute

/**
 * Top-level destinations shown in the bottom bar / nav rail. The first entry is the start (exit)
 * destination — pressing back from any other tab returns here before leaving the app.
 */
enum class TopLevelDestination(
    val route: NavKey,
    val icon: ImageVector,
    @StringRes val labelRes: Int,
) {
    MEDIA(MediaPickerRoute(), NextIcons.Home, R.string.home),
    PLAYLISTS(PlaylistListRoute, NextIcons.Playlist, R.string.playlists),
    NETWORK(NetworkRoute, NextIcons.Network, R.string.network),
    SETTINGS(SettingsRoute, NextIcons.Settings, R.string.settings),
}

@Composable
fun rememberTopLevelNavState(): TopLevelNavState {
    val destinations = TopLevelDestination.entries
    // Each tab keeps its own back stack; rememberNavBackStack persists it across config change and
    // process death.
    val backStacks = destinations.associate { dest ->
        val backStack = rememberNavBackStack(dest.route)
        backStack.ensureRoot(dest.route)
        dest.route to backStack
    }
    val selectedIndex = rememberSaveable { mutableIntStateOf(0) }
    return remember(backStacks, selectedIndex) {
        TopLevelNavState(destinations, backStacks, selectedIndex)
    }
}

/**
 * Holds the per-tab back stacks and the currently selected tab, and flattens them into the single
 * list of entries that [androidx.navigation3.ui.NavDisplay] renders.
 *
 * The start tab's stack is always kept at the base, so a non-start tab is displayed *on top* of it;
 * this makes back navigation from a secondary tab fall through to the start tab.
 */
@Stable
class TopLevelNavState(
    val destinations: List<TopLevelDestination>,
    val backStacks: Map<NavKey, NavBackStack<NavKey>>,
    private val selectedIndexState: MutableIntState,
) {
    var selectedIndex by selectedIndexState
        private set

    private val startRoute: NavKey get() = destinations.first().route

    val topLevelRoute: NavKey get() = destinations[selectedIndex].route

    /** The back stack of the currently selected tab — navigation targets are added here. */
    val currentStack: NavBackStack<NavKey> get() = backStacks.getValue(topLevelRoute)

    /**
     * True while the root page of the selected tab is on top. The bottom bar/rail is shown only
     * then: pages pushed on a tab stack (including settings opened from another tab's gear) cover
     * the whole screen.
     */
    val isAtTopLevel: Boolean get() = currentStack.lastOrNull() == topLevelRoute

    private val stacksInUse: List<NavKey>
        get() = if (selectedIndex == 0) listOf(startRoute) else listOf(startRoute, topLevelRoute)

    /**
     * The [androidx.navigation3.runtime.NavEntry.contentKey]s of the top-level destinations. Used to
     * decide whether a rendered scene should show the nav bar/rail. `contentKey` defaults to the
     * route's `toString()`, matching how the entries are created.
     */
    val topLevelContentKeys: Set<Any> = destinations.flatMap { dest ->
        listOf(
            dest.route,
            dest.route.toString(),
            Pair("${dest.route}", "${dest.route::class}"),
        )
    }.toSet()

    fun switchTo(route: NavKey) {
        val index = destinations.indexOfFirst { it.route == route }
        if (index >= 0) selectedIndex = index
    }

    fun goBack() {
        val stack = currentStack
        when {
            stack.size > 1 -> stack.removeLastOrNull()
            selectedIndex != 0 -> selectedIndex = 0
        }
    }

    @Composable
    fun rememberEntries(
        entryProvider: (NavKey) -> NavEntry<NavKey>,
        entryDecorator: NavEntryDecorator<NavKey>,
    ): SnapshotStateList<NavEntry<NavKey>> {
        val decoratedByRoute = LinkedHashMap<NavKey, List<NavEntry<NavKey>>>()
        for (dest in destinations) {
            decoratedByRoute[dest.route] = rememberDecoratedNavEntries(
                backStack = backStacks.getValue(dest.route),
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                    entryDecorator,
                ),
                entryProvider = entryProvider,
            )
        }
        return stacksInUse.flatMap { decoratedByRoute.getValue(it) }.toMutableStateList()
    }
}

fun TopLevelNavState.isNavigationBetweenTopLevelDestinations(initialState: Scene<NavKey>, targetState: Scene<NavKey>): Boolean =
    topLevelContentKeys.run { contains(initialState.entries.lastOrNull()?.contentKey) && contains(targetState.entries.lastOrNull()?.contentKey) }

internal fun TopLevelNavState.navigationTransition(initialState: Scene<NavKey>, targetState: Scene<NavKey>): ContentTransform {
    if (isNavigationBetweenTopLevelDestinations(initialState, targetState)) {
        return fadeIn(tween(200, easing = LinearEasing)) togetherWith fadeOut(tween(200, easing = LinearEasing))
    }

    // Quick Back can select the pop spec while the animated scenes still describe the push.
    // Keep the direction tied to those scenes so the existing slide can reverse smoothly.
    val isPop = initialState.previousEntries.any { it.contentKey == targetState.entries.lastOrNull()?.contentKey }
    return slideInHorizontally(
        initialOffsetX = { if (isPop) -(it * 0.3f).toInt() else it },
        animationSpec = tween(durationMillis = 200, easing = LinearEasing),
    ) togetherWith slideOutHorizontally(
        targetOffsetX = { if (isPop) it else -(it * 0.3f).toInt() },
        animationSpec = tween(durationMillis = 200, easing = LinearEasing),
    )
}

@Composable
fun NextNavigationBar(
    state: TopLevelNavState,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .navigationBarsPadding()
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
            .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.destinations.forEach { destination ->
            TopLevelNavItem(
                destination = destination,
                isSelected = destination.route == state.topLevelRoute,
                onClick = { state.switchTo(destination.route) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("top_level_tab_${destination.name}"),
            )
        }
    }
}

@Composable
fun NextNavigationRail(state: TopLevelNavState, modifier: Modifier = Modifier) {
    NavigationRail(
        modifier = modifier.fillMaxHeight(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) {
            state.destinations.forEach { destination ->
                TopLevelNavItem(
                    destination = destination,
                    isSelected = destination.route == state.topLevelRoute,
                    onClick = { state.switchTo(destination.route) },
                )
            }
        }
    }
}

@Composable
private fun TopLevelNavItem(
    destination: TopLevelDestination,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = modifier
            .tvFocusRing(shape = RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .selectable(
                selected = isSelected,
                onClick = onClick,
                role = Role.Tab,
                indication = null,
                interactionSource = null,
            )
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(
            imageVector = destination.icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = stringResource(destination.labelRes),
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            maxLines = 1,
        )
    }
}
