package dev.anilbeesetti.nextplayer.feature.playlist.screens.detail

import android.content.Context
import android.net.Uri
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.anilbeesetti.nextplayer.core.common.extensions.isTelevision
import dev.anilbeesetti.nextplayer.core.model.Playlist
import dev.anilbeesetti.nextplayer.core.model.PlaylistItem
import dev.anilbeesetti.nextplayer.core.model.PlaylistType
import dev.anilbeesetti.nextplayer.core.model.Video
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.base.ActionState
import dev.anilbeesetti.nextplayer.core.ui.base.DataState
import dev.anilbeesetti.nextplayer.core.ui.theme.NextPlayerTheme
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class PlaylistDetailScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun tvFabUpReturnsToTheVideoInsteadOfSearch() {
        assumeTrue(ApplicationProvider.getApplicationContext<Context>().isTelevision)
        setContent(playlist(item("content://one", "One.mp4", "/Movies", 0)), isTv = true)
        composeRule.onNodeWithContentDescription(stringRes(R.string.play)).requestFocus()
        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithText("One").assertIsFocused()
    }

    @Test
    fun tvFabUpRestoresThePreviouslyFocusedVideo() {
        assumeTrue(ApplicationProvider.getApplicationContext<Context>().isTelevision)
        setContent(
            playlist(
                item("content://one", "One.mp4", "/Movies", 0),
                item("content://two", "Two.mp4", "/Movies", 1),
            ),
            isTv = true,
        )
        composeRule.onNodeWithText("Two").requestFocus()
        composeRule.onNodeWithContentDescription(stringRes(R.string.play)).requestFocus()
        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithText("Two").assertIsFocused()
    }

    @Test
    fun playFabEmitsOrderedQueueStartingFromFirstVideo() {
        val actions = mutableListOf<PlaylistDetailUiAction>()
        val playlist = playlist(
            item("content://two", "Renamed Two.mp4", "/Moved", 0),
            item("content://one", "One.mp4", "/Movies", 1),
        )

        setContent(playlist, onAction = actions::add)

        composeRule.onNodeWithText("Renamed Two").assertIsDisplayed()
        composeRule.onNodeWithText("/Moved").assertIsDisplayed()
        composeRule.onNodeWithText(stringRes(R.string.play_all), useUnmergedTree = true).assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription(stringRes(R.string.play_all)).assertCountEquals(0)
        composeRule.onNodeWithContentDescription(stringRes(R.string.play)).performClick()

        assertEquals(
            PlaylistDetailUiAction.OnPlay(Uri.parse("content://two")),
            actions.single(),
        )
    }

    @Test
    fun playFabStartsFromTheLastPlayedVideo() {
        val actions = mutableListOf<PlaylistDetailUiAction>()
        val playlist = playlist(
            item("content://one", "One.mp4", "/Movies", 0),
            item("content://two", "Two.mp4", "/Movies", 1, lastPlayedAt = 200),
        )

        setContent(playlist, onAction = actions::add)
        composeRule.onNodeWithText(stringRes(R.string.continue_playback), useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(stringRes(R.string.play)).performClick()

        assertEquals(
            PlaylistDetailUiAction.OnPlay(Uri.parse("content://two")),
            actions.single(),
        )
    }

    @Test
    fun existingPlaylistRemainsVisibleWhileUpdating() {
        setContent(
            playlist = playlist(item("content://one", "One.mp4", "/Movies", 0)),
            updateActionState = ActionState.Running,
        )

        composeRule.onNodeWithText("One").assertIsDisplayed()
        composeRule.onNodeWithText("/Movies").assertIsDisplayed()
    }

    @Test
    fun networkSnapshotRowShowsReadableNamesInsteadOfPercentEscapes() {
        val encodedUri = "smb://192.0.2.1/Media/%E6%97%A5%E6%9C%AC%E8%AA%9E.mkv?cid=3"
        setContent(
            playlist = playlist(networkItem(encodedUri, 0), type = PlaylistType.NETWORK),
        )

        composeRule.onNodeWithText("日本語").assertIsDisplayed()
        composeRule.onNodeWithText("smb://192.0.2.1/Media/日本語.mkv?cid=3").assertIsDisplayed()
        composeRule.onAllNodesWithText("%", substring = true).assertCountEquals(0)
    }

    @Test
    fun removeMenuRequestsConfirmation() {
        val actions = mutableListOf<PlaylistDetailUiAction>()
        val item = item("content://one", "One.mp4", "/Movies", 0)
        setContent(
            playlist = playlist(item),
            onAction = actions::add,
        )

        composeRule.onNodeWithContentDescription(stringRes(R.string.playlist_actions)).performClick()
        composeRule.onNodeWithText(stringRes(R.string.remove)).performClick()

        assertEquals(
            listOf(PlaylistDetailUiAction.ShowRemoveDialogFor(item)),
            actions,
        )
    }

    @Test
    fun removeDialogEmitsRemoveActionAfterConfirmation() {
        val actions = mutableListOf<PlaylistDetailUiAction>()
        val item = item("content://one", "One.mp4", "/Movies", 0)
        setContent(
            playlist = playlist(item),
            showRemoveDialogFor = item,
            onAction = actions::add,
        )

        composeRule.onNodeWithText(stringRes(R.string.remove_video_confirmation, "One"))
            .assertIsDisplayed()
        composeRule.onNodeWithText(stringRes(R.string.remove)).performClick()

        assertEquals(
            listOf(PlaylistDetailUiAction.RemoveVideo("content://one")),
            actions,
        )
    }

    @Test
    fun inPlaceSearchFiltersMetadataAndEmitsPlaybackAction() {
        val actions = mutableListOf<PlaylistDetailUiAction>()
        val playlist = playlist(
            item("content://one", "One.mp4", "/Movies", 0),
            item("content://two", "Two.mp4", "/Downloads", 1),
        )
        setContent(
            playlist = playlist,
            isSearching = true,
            searchQuery = "downloads",
            onAction = actions::add,
        )

        composeRule.onAllNodesWithContentDescription(stringRes(R.string.play)).assertCountEquals(0)
        composeRule.onAllNodesWithText("One").assertCountEquals(0)
        composeRule.onNodeWithText("Two").assertIsDisplayed()
        composeRule.onNodeWithText("Two").performClick()

        assertEquals(
            PlaylistDetailUiAction.OnPlay(Uri.parse("content://two")),
            actions.single(),
        )
    }

    @Test
    fun closeSearchEmitsCloseAction() {
        val actions = mutableListOf<PlaylistDetailUiAction>()
        setContent(
            playlist = playlist(item("content://one", "One.mp4", "/Movies", 0)),
            isSearching = true,
            onAction = actions::add,
        )

        composeRule.onNodeWithContentDescription(stringRes(R.string.close_search)).performClick()

        assertEquals(
            listOf(PlaylistDetailUiAction.OnCloseSearchClick),
            actions,
        )
    }

    @Test
    fun touchReorderModeUsesWholeRowsWithoutPlayingVideos() {
        val actions = mutableListOf<PlaylistDetailUiAction>()
        val playlist = playlist(
            item("content://one", "One.mp4", "/Movies", 0),
            item("content://two", "Two.mp4", "/Movies", 1),
        )

        setContent(
            playlist = playlist,
            isTv = false,
            isReordering = true,
            onAction = actions::add,
        )

        composeRule.onAllNodesWithContentDescription(stringRes(R.string.play)).assertCountEquals(0)
        composeRule.onNodeWithContentDescription(stringRes(R.string.finish_reordering)).assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription(stringRes(R.string.reorder_playlist_item)).assertCountEquals(2)
        composeRule.onAllNodesWithContentDescription(stringRes(R.string.playlist_actions)).assertCountEquals(0)
        composeRule.onNodeWithText("One").performClick()
        assertEquals(emptyList<PlaylistDetailUiAction>(), actions)

        composeRule.onNodeWithContentDescription(stringRes(R.string.finish_reordering)).performClick()
        assertEquals(
            listOf(PlaylistDetailUiAction.OnFinishReorderingClick),
            actions,
        )
    }

    @Test
    fun reorderButtonEmitsReorderAction() {
        val actions = mutableListOf<PlaylistDetailUiAction>()
        setContent(
            playlist = playlist(
                item("content://one", "One.mp4", "/Movies", 0),
                item("content://two", "Two.mp4", "/Movies", 1),
            ),
            onAction = actions::add,
        )

        composeRule.onNodeWithContentDescription(stringRes(R.string.reorder_playlist)).performClick()

        assertEquals(
            listOf(PlaylistDetailUiAction.OnReorderClick),
            actions,
        )
    }

    @Test
    fun tvRowsPlayWithoutReorderControls() {
        val actions = mutableListOf<PlaylistDetailUiAction>()
        val playlist = playlist(
            item("content://one", "One.mp4", "/Movies", 0),
            item("content://two", "Two.mp4", "/Movies", 1),
        )
        setContent(
            playlist = playlist,
            isTv = true,
            onAction = actions::add,
        )

        composeRule.onAllNodesWithContentDescription(stringRes(R.string.reorder_playlist)).assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription(stringRes(R.string.play)).assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription("Move up").assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription("Move down").assertCountEquals(0)

        composeRule.onNodeWithText("One").performClick()
        assertEquals(
            PlaylistDetailUiAction.OnPlay(Uri.parse("content://one")),
            actions.single(),
        )
    }

    @Test
    fun sortMenuEmitsTheChosenSort() {
        val actions = mutableListOf<PlaylistDetailUiAction>()
        setContent(
            playlist = playlist(
                item("content://one", "One.mp4", "/Movies", 0),
                item("content://two", "Two.mp4", "/Movies", 1),
            ),
            onAction = actions::add,
        )

        composeRule.onNodeWithContentDescription(stringRes(R.string.sort_playlist)).performClick()
        composeRule.onNodeWithText(stringRes(R.string.sort_duration_ascending)).performClick()

        assertEquals(
            listOf(PlaylistDetailUiAction.SortBy(PlaylistSort.DURATION_ASCENDING)),
            actions,
        )
    }

    @Test
    fun sortMenuHidesTheDurationRowsWhenNoVideoKnowsItsLength() {
        setContent(
            playlist = playlist(
                item("content://one", "One.mp4", "/Movies", 0, videoDuration = 0),
                item("content://two", "Two.mp4", "/Movies", 1, videoDuration = 0),
            ),
        )

        composeRule.onNodeWithContentDescription(stringRes(R.string.sort_playlist)).performClick()

        composeRule.onAllNodesWithText(stringRes(R.string.sort_duration_ascending)).assertCountEquals(0)
        composeRule.onAllNodesWithText(stringRes(R.string.sort_duration_descending)).assertCountEquals(0)
        composeRule.onNodeWithText(stringRes(R.string.sort_name_ascending)).assertIsDisplayed()
        composeRule.onNodeWithText(stringRes(R.string.sort_restore_added_order)).assertIsDisplayed()
    }

    @Test
    fun sortButtonIsAbsentFromAListTooShortToOrder() {
        setContent(playlist = playlist(item("content://one", "One.mp4", "/Movies", 0)))

        composeRule.onAllNodesWithContentDescription(stringRes(R.string.sort_playlist)).assertCountEquals(0)
    }

    /** The device locale decides the label text, so assert against the resource, not a literal. */
    private fun stringRes(resId: Int, vararg args: Any): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(resId, *args)

    private fun setContent(
        playlist: Playlist,
        isTv: Boolean = false,
        updateActionState: ActionState = ActionState.Idle,
        isSearching: Boolean = false,
        searchQuery: String = "",
        isReordering: Boolean = false,
        showRemoveDialogFor: PlaylistItem? = null,
        onAction: (PlaylistDetailUiAction) -> Unit = {},
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Requires ${if (isTv) "TV" else "phone"} device", context.isTelevision == isTv)
        composeRule.setContent {
            NextPlayerTheme {
                PlaylistDetailScreenContent(
                    state = PlaylistDetailUiState(
                        playlistDataState = DataState.Success(playlist),
                        updateActionState = updateActionState,
                        isSearching = isSearching,
                        searchQuery = searchQuery,
                        isReordering = isReordering,
                        showRemoveDialogFor = showRemoveDialogFor,
                    ),
                    onAction = onAction,
                )
            }
        }
    }
}

private fun playlist(vararg items: PlaylistItem, type: PlaylistType = PlaylistType.LOCAL) = Playlist(
    id = 7,
    name = "Movies",
    type = type,
    source = null,
    items = items.toList(),
    lastRefreshedAt = null,
)

/** A network snapshot row: no media-store record and no stored title, only the encoded URI. */
private fun networkItem(uri: String, position: Int) = PlaylistItem(
    position = position,
    uri = uri,
    title = null,
    tvgLogo = null,
    duration = -1,
    groupTitle = null,
    video = null,
)

private fun item(
    uri: String,
    name: String,
    parentPath: String,
    position: Int,
    lastPlayedAt: Long? = null,
    videoDuration: Long = 1_000,
) = PlaylistItem(
    position = position,
    uri = uri,
    title = null,
    tvgLogo = null,
    duration = -1,
    groupTitle = null,
    lastPlayedAt = lastPlayedAt,
    video = Video(
        id = position.toLong(),
        path = "$parentPath/$name",
        parentPath = parentPath,
        duration = videoDuration,
        uriString = uri,
        nameWithExtension = name,
        width = 1920,
        height = 1080,
        size = 1_000,
    ),
)
