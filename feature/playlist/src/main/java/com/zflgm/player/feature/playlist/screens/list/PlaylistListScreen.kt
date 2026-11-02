package com.zflgm.player.feature.playlist.screens.list

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import com.zflgm.player.core.model.PlaylistSummary
import com.zflgm.player.core.model.PlaylistType
import com.zflgm.player.core.ui.R
import com.zflgm.player.core.ui.base.DataState
import com.zflgm.player.core.ui.components.LocalNavigationBottomPadding
import com.zflgm.player.core.ui.components.NextDialog
import com.zflgm.player.core.ui.components.NextOutlinedTextField
import com.zflgm.player.core.ui.components.NextSegmentedListItem
import com.zflgm.player.core.ui.components.NextTopAppBar
import com.zflgm.player.core.ui.components.tvFocusRing
import com.zflgm.player.core.ui.components.tvListFocus
import com.zflgm.player.core.ui.designsystem.NextIcons
import com.zflgm.player.core.ui.extensions.copy
import com.zflgm.player.core.common.extensions.isTelevision
import sh.calvin.reorderable.DragGestureDetector
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun PlaylistListScreen(
    viewModel: PlaylistListViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) { viewModel.onAction(PlaylistUiAction.Synchronize) }

    PlaylistListScreenContent(
        state = state,
        onAction = viewModel::onAction,
    )
}

@Composable
internal fun PlaylistListScreenContent(
    state: PlaylistListUiState,
    onAction: (PlaylistUiAction) -> Unit = {},
) {
    val openM3UFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { onAction(PlaylistUiAction.CreateM3UFile(it)) }
    }

    val navigationBottomPadding = LocalNavigationBottomPadding.current

    Scaffold(
        topBar = {
            NextTopAppBar(
                title = stringResource(R.string.playlists),
                fontWeight = FontWeight.Bold,
                actions = {
                    if ((state.playlistsDataState as? DataState.Success)?.value?.size?.let { it > 1 } == true) {
                        var sortMenuExpanded by rememberSaveable { mutableStateOf(false) }
                        Box {
                            IconButton(
                                onClick = { sortMenuExpanded = true },
                                modifier = Modifier.tvFocusRing(),
                            ) {
                                Icon(
                                    imageVector = NextIcons.Sort,
                                    contentDescription = stringResource(R.string.sort_playlist),
                                )
                            }
                            DropdownMenu(
                                expanded = sortMenuExpanded,
                                onDismissRequest = { sortMenuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.sort_name_ascending)) },
                                    onClick = {
                                        sortMenuExpanded = false
                                        onAction(PlaylistUiAction.SortPlaylists(PlaylistListSort.NAME_ASCENDING))
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.sort_name_descending)) },
                                    onClick = {
                                        sortMenuExpanded = false
                                        onAction(PlaylistUiAction.SortPlaylists(PlaylistListSort.NAME_DESCENDING))
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.sort_shuffle)) },
                                    onClick = {
                                        sortMenuExpanded = false
                                        onAction(PlaylistUiAction.SortPlaylists(PlaylistListSort.SHUFFLE))
                                    },
                                )
                            }
                        }
                    }
                    IconButton(
                        onClick = { onAction(PlaylistUiAction.ShowCreationChooser) },
                        modifier = Modifier.tvFocusRing(),
                    ) {
                        Icon(
                            imageVector = NextIcons.Add,
                            contentDescription = stringResource(R.string.create),
                        )
                    }
                    IconButton(
                        onClick = { onAction(PlaylistUiAction.OnSettingsClick) },
                        modifier = Modifier.tvFocusRing(),
                    ) {
                        Icon(
                            imageVector = NextIcons.Settings,
                            contentDescription = stringResource(R.string.settings),
                        )
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) { scaffoldPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding.copy(bottom = 0.dp))
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(MaterialTheme.colorScheme.background),
        ) {
            when (state.playlistsDataState) {
                is DataState.Loading -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }

                is DataState.Error -> {
                    PlaylistListEmptyState(Modifier.fillMaxSize())
                }

                is DataState.Success -> {
                    val playlistList = state.playlistsDataState.value

                    if (playlistList.isEmpty()) {
                        PlaylistListEmptyState(Modifier.fillMaxSize())
                    } else {
                        val isTv = LocalContext.current.isTelevision
                        val hapticFeedback = LocalHapticFeedback.current
                        var displayedPlaylists by remember { mutableStateOf(playlistList) }
                        var isDragging by remember { mutableStateOf(false) }
                        LaunchedEffect(playlistList) {
                            if (!isDragging) displayedPlaylists = playlistList
                        }
                        val listState = rememberLazyListState()
                        val reorderState = rememberReorderableLazyListState(listState) { from, to ->
                            displayedPlaylists = displayedPlaylists.toMutableList().apply {
                                add(to.index, removeAt(from.index))
                            }
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                        }

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .tvListFocus(),
                            state = listState,
                            contentPadding = PaddingValues(8.dp).copy(
                                bottom = scaffoldPadding.calculateBottomPadding() + navigationBottomPadding,
                            ),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            itemsIndexed(
                                items = displayedPlaylists,
                                key = { _, playlist -> playlist.id },
                            ) { index, playlist ->
                                ReorderableItem(
                                    state = reorderState,
                                    key = playlist.id,
                                ) {
                                    PlaylistRow(
                                        playlist = playlist,
                                        isFirstItem = index == 0,
                                        isLastItem = index == displayedPlaylists.lastIndex,
                                        onClick = { onAction(PlaylistUiAction.OnPlaylistClick(playlist)) },
                                        onRename = { onAction(PlaylistUiAction.ShowRenameDialogFor(playlist)) },
                                        onDelete = { onAction(PlaylistUiAction.ShowDeleteDialogFor(playlist)) },
                                        modifier = Modifier.draggableHandle(
                                            enabled = !isTv,
                                            dragGestureDetector = DragGestureDetector.LongPress,
                                            onDragStarted = {
                                                isDragging = true
                                                hapticFeedback.performHapticFeedback(
                                                    HapticFeedbackType.GestureThresholdActivate,
                                                )
                                            },
                                            onDragStopped = {
                                                isDragging = false
                                                onAction(
                                                    PlaylistUiAction.ReplacePlaylistOrder(
                                                        displayedPlaylists.map { it.id },
                                                    ),
                                                )
                                                hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                            },
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    when (state.creationDialog) {
        PlaylistCreationDialog.NONE -> Unit
        PlaylistCreationDialog.CHOOSER -> CreationChooserDialog(
            onDismissRequest = { onAction(PlaylistUiAction.DismissCreation) },
            onCreateLocal = { onAction(PlaylistUiAction.ChooseLocalPlaylist) },
            onCreateUrl = { onAction(PlaylistUiAction.ChooseM3UUrl) },
            onCreateFile = {
                onAction(PlaylistUiAction.DismissCreation)
                openM3UFileLauncher.launch(M3U_MIME_TYPES)
            },
        )

        PlaylistCreationDialog.LOCAL_NAME -> PlaylistNameDialog(
            title = stringResource(R.string.create_local_playlist),
            confirmLabel = stringResource(R.string.create),
            isSaving = state.saveActionState.isRunning,
            error = state.saveActionState.errorMessage,
            onDismissRequest = { onAction(PlaylistUiAction.DismissCreation) },
            onConfirm = { onAction(PlaylistUiAction.CreateLocal(it)) },
        )

        PlaylistCreationDialog.M3U_URL -> M3UUrlDialog(
            isSaving = state.saveActionState.isRunning,
            error = state.saveActionState.errorMessage,
            onDismissRequest = { onAction(PlaylistUiAction.DismissCreation) },
            onConfirm = { onAction(PlaylistUiAction.CreateM3UUrl(it)) },
        )
    }

    state.showRenameDialogFor?.let { playlist ->
        PlaylistNameDialog(
            title = stringResource(R.string.rename_playlist),
            confirmLabel = stringResource(R.string.save),
            initialName = playlist.name,
            isSaving = state.saveActionState.isRunning,
            error = state.saveActionState.errorMessage,
            onDismissRequest = { onAction(PlaylistUiAction.DismissRenameDialog) },
            onConfirm = { onAction(PlaylistUiAction.Rename(playlist.id, it)) },
        )
    }

    state.showDeleteDialogFor?.let { playlist ->
        NextDialog(
            title = { Text(text = stringResource(R.string.delete_playlist)) },
            content = {
                Text(
                    text = stringResource(R.string.delete_playlist_confirmation, playlist.name),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { onAction(PlaylistUiAction.Delete(playlist.id)) },
                    modifier = Modifier.tvFocusRing(),
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            onDismissRequest = { onAction(PlaylistUiAction.DismissDeleteDialog) },
            dismissButton = {
                TextButton(
                    onClick = { onAction(PlaylistUiAction.DismissDeleteDialog) },
                    modifier = Modifier.tvFocusRing(),
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun CreationChooserDialog(
    onDismissRequest: () -> Unit,
    onCreateLocal: () -> Unit,
    onCreateUrl: () -> Unit,
    onCreateFile: () -> Unit,
) {
    NextDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.choose_playlist_type)) },
        content = {
            Column {
                CreationChoice(
                    label = stringResource(R.string.create_local_playlist),
                    icon = NextIcons.Add,
                    onClick = onCreateLocal,
                )
                CreationChoice(
                    label = stringResource(R.string.add_m3u_url_playlist),
                    icon = NextIcons.Link,
                    onClick = onCreateUrl,
                )
                CreationChoice(
                    label = stringResource(R.string.add_m3u_file_playlist),
                    icon = NextIcons.FileOpen,
                    onClick = onCreateFile,
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismissRequest, modifier = Modifier.tvFocusRing()) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun CreationChoice(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun M3UUrlDialog(
    isSaving: Boolean,
    error: String?,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var url by rememberSaveable { mutableStateOf("") }
    val trimmedUrl = url.trim()
    val isValid = remember(trimmedUrl) { trimmedUrl.isHttpUrl() }

    NextDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.add_m3u_url_playlist)) },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NextOutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.playlist_url)) },
                    enabled = !isSaving,
                    singleLine = true,
                    isError = error != null || (trimmedUrl.isNotEmpty() && !isValid),
                    supportingText = {
                        when {
                            error != null -> Text(error)
                            trimmedUrl.isEmpty() -> Text(stringResource(R.string.url_required))
                            !isValid -> Text(stringResource(R.string.invalid_url))
                        }
                    },
                )
                if (isSaving) {
                    Text(
                        text = stringResource(R.string.saving_playlist),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (isValid) onConfirm(trimmedUrl) },
                enabled = !isSaving && isValid,
                modifier = Modifier.tvFocusRing(),
            ) {
                Text(stringResource(R.string.create))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                enabled = !isSaving,
                modifier = Modifier.tvFocusRing(),
            ) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun PlaylistNameDialog(
    title: String,
    confirmLabel: String,
    isSaving: Boolean,
    error: String?,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
    initialName: String = "",
) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    val trimmedName = name.trim()

    NextDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(title) },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NextOutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.playlist_name)) },
                    enabled = !isSaving,
                    singleLine = true,
                    isError = error != null && trimmedName.isEmpty(),
                    supportingText = {
                        when {
                            error != null -> Text(error)
                            trimmedName.isEmpty() ->
                                Text(stringResource(R.string.name_required))
                        }
                    },
                )
                if (isSaving) {
                    Text(
                        text = stringResource(R.string.saving_playlist),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (trimmedName.isNotEmpty()) onConfirm(trimmedName)
                },
                enabled = !isSaving && trimmedName.isNotEmpty(),
                modifier = Modifier.tvFocusRing(),
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                enabled = !isSaving,
                modifier = Modifier.tvFocusRing(),
            ) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlaylistRow(
    playlist: PlaylistSummary,
    isFirstItem: Boolean,
    isLastItem: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    val videoCount = pluralStringResource(
        R.plurals.playlist_video_count,
        playlist.itemCount,
        playlist.itemCount,
    )

    NextSegmentedListItem(
        modifier = modifier,
        isFirstItem = isFirstItem,
        isLastItem = isLastItem,
        onClick = onClick,
        contentPadding = PaddingValues(12.dp),
        leadingContent = {
            Icon(
                imageVector = NextIcons.Playlist,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        },
        content = {
            Text(
                text = playlist.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text = "${playlist.type.label()} · $videoCount",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.tvFocusRing(),
                ) {
                    Icon(
                        imageVector = NextIcons.MoreVert,
                        contentDescription = stringResource(R.string.playlist_actions),
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.rename_playlist)) },
                        leadingIcon = { Icon(NextIcons.Edit, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) },
                        leadingIcon = { Icon(NextIcons.Delete, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        },
    )
}

@Composable
private fun PlaylistType.label(): String = stringResource(
    when (this) {
        PlaylistType.LOCAL -> R.string.local_playlist
        PlaylistType.M3U_URL -> R.string.m3u_url_playlist
        PlaylistType.M3U_FILE -> R.string.m3u_file_playlist
        PlaylistType.NETWORK -> R.string.network_playlist
    },
)

private fun String.isHttpUrl(): Boolean = runCatching {
    val uri = Uri.parse(this)
    (
        uri.scheme.equals("http", ignoreCase = true) ||
            uri.scheme.equals("https", ignoreCase = true)
        ) && !uri.host.isNullOrBlank()
}.getOrDefault(false)

private val M3U_MIME_TYPES = arrayOf(
    "application/vnd.apple.mpegurl",
    "application/x-mpegURL",
    "audio/mpegurl",
    "audio/x-mpegurl",
    "text/plain",
    "application/octet-stream",
)

@Composable
private fun PlaylistListEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = NextIcons.Playlist,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.size(16.dp))
        Text(
            text = stringResource(R.string.no_playlists_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.no_playlists_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
