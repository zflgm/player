package com.zflgm.player.feature.playlist.screens.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zflgm.player.core.ui.R
import com.zflgm.player.core.ui.designsystem.NextIcons

/**
 * In-place picker for adding content to a playlist: local media (folders expand to their videos)
 * and network folders (drilled into per connection, added recursively).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaylistAddSheet(
    state: PlaylistDetailUiState,
    onAction: (PlaylistDetailUiAction) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = { onAction(PlaylistDetailUiAction.OnDismissAddSheet) },
        // The sheet is its own window and therefore owns the back gesture - a handler in the
        // screen's composition never sees it. Turning off the built-in back dismissal makes the
        // handler inside the content the single decision point.
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
    ) {
        // While browsing a share, back climbs one level; at the top level it closes the sheet.
        BackHandler {
            if (state.addBrowse != null) {
                onAction(PlaylistDetailUiAction.OnAddBrowseUp)
            } else {
                onAction(PlaylistDetailUiAction.OnDismissAddSheet)
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp),
        ) {
            Text(
                text = stringResource(R.string.add_to_playlist),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            TabRow(selectedTabIndex = state.addTab.ordinal) {
                Tab(
                    selected = state.addTab == AddSheetTab.LOCAL,
                    onClick = { onAction(PlaylistDetailUiAction.OnAddTabChange(AddSheetTab.LOCAL)) },
                    text = { Text(stringResource(R.string.add_sheet_local_tab)) },
                )
                Tab(
                    selected = state.addTab == AddSheetTab.NETWORK,
                    onClick = { onAction(PlaylistDetailUiAction.OnAddTabChange(AddSheetTab.NETWORK)) },
                    text = { Text(stringResource(R.string.add_sheet_network_tab)) },
                )
            }
            when (state.addTab) {
                AddSheetTab.LOCAL -> LocalAddContent(state, onAction)
                AddSheetTab.NETWORK -> NetworkAddContent(state, onAction)
            }
        }
    }
}

@Composable
private fun LocalAddContent(
    state: PlaylistDetailUiState,
    onAction: (PlaylistDetailUiAction) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 240.dp, max = 460.dp),
        ) {
            if (state.addFolders.isNotEmpty()) {
                item(key = "folders-header") {
                    SectionHeader(stringResource(R.string.folders))
                }
                items(state.addFolders, key = { "folder:${it.path}" }) { folder ->
                    val allSelected = folder.videoUris.isNotEmpty() &&
                        folder.videoUris.all { it in state.addSelection }
                    AddRow(
                        selected = allSelected,
                        onClick = { onAction(PlaylistDetailUiAction.OnToggleAddFolder(folder)) },
                        leading = {
                            Icon(imageVector = NextIcons.Folder, contentDescription = null)
                        },
                        title = folder.name,
                        subtitle = folder.videoUris.size.toString(),
                    )
                }
                item(key = "videos-header") {
                    SectionHeader(stringResource(R.string.videos))
                }
            }
            items(state.addLocalVideos, key = { "video:${it.uriString}" }) { video ->
                AddRow(
                    selected = video.uriString in state.addSelection,
                    onClick = { onAction(PlaylistDetailUiAction.OnToggleAddVideo(video.uriString)) },
                    leading = null,
                    title = video.displayName,
                    subtitle = video.formattedDuration.ifBlank { null },
                )
            }
        }
        HorizontalDivider()
        Button(
            onClick = { onAction(PlaylistDetailUiAction.OnConfirmAddLocal) },
            enabled = state.addSelection.isNotEmpty() && !state.updateActionState.isRunning,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(stringResource(R.string.add_selected_to_playlist, state.addSelection.size))
        }
    }
}

@Composable
private fun NetworkAddContent(
    state: PlaylistDetailUiState,
    onAction: (PlaylistDetailUiAction) -> Unit,
) {
    val browse = state.addBrowse
    if (browse == null) {
        if (state.addConnections.isEmpty()) {
            Text(
                text = stringResource(R.string.no_connections_title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.heightIn(min = 240.dp, max = 460.dp)) {
                items(state.addConnections, key = { "conn:${it.id}" }) { connection ->
                    AddRow(
                        selected = false,
                        onClick = { onAction(PlaylistDetailUiAction.OnOpenAddConnection(connection)) },
                        leading = {
                            Icon(imageVector = NextIcons.Storage, contentDescription = null)
                        },
                        title = connection.name,
                        subtitle = connection.host,
                    )
                }
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            IconButton(onClick = { onAction(PlaylistDetailUiAction.OnAddBrowseUp) }) {
                Icon(
                    imageVector = NextIcons.ArrowBack,
                    contentDescription = stringResource(R.string.navigate_up),
                )
            }
            Text(
                text = browse.pathStack.lastOrNull()
                    ?.trimEnd('/')
                    ?.substringAfterLast('/')
                    ?.takeIf(String::isNotBlank)
                    ?: browse.connectionName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }
        Button(
            onClick = { onAction(PlaylistDetailUiAction.OnAddCurrentFolder) },
            enabled = !browse.isLoading && !state.isAddingItems && !state.updateActionState.isRunning,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Text(stringResource(R.string.add_this_folder_recursive))
        }
        when {
            browse.isLoading || state.isAddingItems -> {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(24.dp)
                        .size(32.dp),
                )
            }

            browse.error != null -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = browse.error.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    val connection = state.addConnections.firstOrNull { it.id == browse.connectionId }
                    if (connection != null) {
                        TextButton(
                            onClick = { onAction(PlaylistDetailUiAction.OnOpenAddConnection(connection)) },
                        ) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
            }

            else -> {
                LazyColumn(modifier = Modifier.heightIn(min = 160.dp, max = 380.dp)) {
                    items(browse.folders, key = { "dir:${it.path}" }) { folder ->
                        AddRow(
                            selected = false,
                            onClick = { onAction(PlaylistDetailUiAction.OnAddBrowseInto(folder)) },
                            leading = {
                                Icon(imageVector = NextIcons.Folder, contentDescription = null)
                            },
                            title = folder.name,
                            subtitle = null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun AddRow(
    selected: Boolean,
    onClick: () -> Unit,
    leading: (@Composable () -> Unit)?,
    title: String,
    subtitle: String?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Checkbox(checked = selected, onCheckedChange = { onClick() })
        if (leading != null) {
            leading()
            Spacer(Modifier.size(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}
