package dev.anilbeesetti.nextplayer.feature.network.screens.browse

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anilbeesetti.nextplayer.core.common.Utils
import dev.anilbeesetti.nextplayer.core.model.NetworkFile
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.components.NextDialog
import dev.anilbeesetti.nextplayer.core.ui.components.NextOutlinedTextField
import dev.anilbeesetti.nextplayer.core.ui.components.NextSegmentedListItem
import dev.anilbeesetti.nextplayer.core.ui.components.NextTopAppBar
import dev.anilbeesetti.nextplayer.core.ui.components.tvFocusRing
import dev.anilbeesetti.nextplayer.core.ui.components.tvListFocus
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons
import dev.anilbeesetti.nextplayer.core.ui.extensions.copy
import java.util.Date

@Composable
fun NetworkBrowseScreen(
    viewModel: NetworkBrowseViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    NetworkBrowseScreenContent(
        state = state,
        onAction = viewModel::onAction,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NetworkBrowseScreenContent(
    state: NetworkBrowseUiState,
    onAction: (NetworkBrowseAction) -> Unit,
) {
    Scaffold(
        topBar = {
            NextTopAppBar(
                title = state.title,
                navigationIcon = {
                    FilledTonalIconButton(onClick = { onAction(NetworkBrowseAction.NavigateUp) }, modifier = Modifier.tvFocusRing()) {
                        Icon(
                            imageVector = NextIcons.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_up),
                        )
                    }
                },
                actions = {
                    if (!state.isLoading && state.error == null) {
                        if (state.files.any { !it.isDirectory }) {
                            FilledTonalIconButton(
                                onClick = { onAction(NetworkBrowseAction.PlayAll) },
                                modifier = Modifier.tvFocusRing(),
                            ) {
                                Icon(NextIcons.Play, contentDescription = stringResource(R.string.play_all))
                            }
                        }
                        if (state.files.isNotEmpty()) {
                            FilledTonalIconButton(
                                onClick = { onAction(NetworkBrowseAction.ShowSnapshotDialog) },
                                modifier = Modifier.tvFocusRing(),
                            ) {
                                Icon(
                                    NextIcons.PlaylistAdd,
                                    contentDescription = stringResource(R.string.create_playlist_from_folder),
                                )
                            }
                        }
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) { scaffoldPadding ->
        when {
            state.isLoading -> {
                Box(Modifier.fillMaxSize().padding(scaffoldPadding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            state.error != null -> {
                val error = state.error
                Column(
                    modifier = Modifier.fillMaxSize().padding(scaffoldPadding).padding(horizontal = 32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.failed_to_load_folder),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        text = if (error.hostKeyMismatch != null) {
                            stringResource(R.string.host_key_mismatch)
                        } else {
                            error.message ?: stringResource(R.string.connection_failed)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    error.hostKeyMismatch?.let { mismatch ->
                        Spacer(Modifier.size(4.dp))
                        SelectionContainer {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.ssh_host_key_trusted_fingerprint,
                                        mismatch.trustedFingerprint,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                                Text(
                                    text = stringResource(
                                        R.string.ssh_host_key_presented_fingerprint,
                                        mismatch.presentedFingerprint,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.size(16.dp))
                    Button(onClick = { onAction(NetworkBrowseAction.Retry) }) { Text(stringResource(R.string.retry)) }
                }
            }

            else -> {
                val containerModifier = Modifier
                    .fillMaxSize()
                    .padding(scaffoldPadding.copy(bottom = 0.dp))
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(MaterialTheme.colorScheme.background)

                Box(modifier = containerModifier) {
                    if (state.files.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = stringResource(R.string.empty_folder),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .tvListFocus(),
                            contentPadding = PaddingValues(
                                start = 8.dp,
                                end = 8.dp,
                                top = 8.dp,
                                bottom = scaffoldPadding.calculateBottomPadding() + 16.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            itemsIndexed(
                                items = state.files,
                                key = { _, file -> file.path },
                            ) { index, file ->
                                NetworkFileItem(
                                    file = file,
                                    isFirstItem = index == 0,
                                    isLastItem = index == state.files.lastIndex,
                                    isRecentlyPlayed = state.preferences.markLastPlayedMedia &&
                                        (state.recentlyPlayedPath == file.path ||
                                            (file.isDirectory && state.recentlyPlayedPath?.startsWith("${file.path.trimEnd('/')}/") == true)),
                                    playedPercentage = state.playbackHistory[file.path]?.playedPercentage
                                        ?.takeIf { state.preferences.showPlayedProgress },
                                    onClick = {
                                        if (file.isDirectory) onAction(NetworkBrowseAction.OpenFolder(file)) else onAction(NetworkBrowseAction.PlayVideo(file))
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    state.snapshot?.let { snapshot ->
        SnapshotPlaylistDialog(
            snapshot = snapshot,
            onAction = onAction,
        )
    }
}

@Composable
private fun SnapshotPlaylistDialog(
    snapshot: NetworkSnapshotState,
    onAction: (NetworkBrowseAction) -> Unit,
) {
    val ask = snapshot as? NetworkSnapshotState.Ask
    val seedName = ask?.suggestedName.orEmpty()
    var name by rememberSaveable(seedName) { mutableStateOf(seedName) }
    val isBuilding = snapshot is NetworkSnapshotState.Building
    val targetId = ask?.targetId

    NextDialog(
        onDismissRequest = { onAction(NetworkBrowseAction.DismissSnapshotDialog) },
        title = { Text(stringResource(R.string.create_playlist_from_folder)) },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isBuilding) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LinearProgressIndicator()
                        Text(
                            text = stringResource(
                                R.string.scanning_folder_videos,
                                (snapshot as NetworkSnapshotState.Building).discovered,
                            ),
                        )
                    }
                } else {
                    SnapshotTargetRow(
                        selected = targetId == null,
                        title = stringResource(R.string.create_new_playlist),
                        onClick = { onAction(NetworkBrowseAction.SelectSnapshotTarget(null)) },
                    )
                    if (!ask?.targets.isNullOrEmpty()) {
                        LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
                            items(ask?.targets.orEmpty(), key = NetworkPlaylistTarget::id) { target ->
                                SnapshotTargetRow(
                                    selected = targetId == target.id,
                                    title = target.name,
                                    supporting = pluralStringResource(
                                        R.plurals.playlist_video_count,
                                        target.itemCount,
                                        target.itemCount,
                                    ),
                                    onClick = { onAction(NetworkBrowseAction.SelectSnapshotTarget(target.id)) },
                                )
                            }
                        }
                    }
                    if (targetId == null) {
                        NextOutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.playlist_name)) },
                            singleLine = true,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.include_subfolders),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Switch(
                            checked = ask?.recursive == true,
                            onCheckedChange = { onAction(NetworkBrowseAction.ToggleSubfolders) },
                            enabled = ask?.subfoldersRequired != true,
                        )
                    }
                    if (ask?.subfoldersRequired == true) {
                        Text(
                            text = stringResource(R.string.subfolders_required_hint),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (ask?.failed == true) {
                        DialogMessage(stringResource(R.string.snapshot_scan_failed))
                    }
                    if (ask?.emptyResult == true) {
                        DialogMessage(stringResource(R.string.snapshot_scan_empty))
                    }
                }
            }
        },
        confirmButton = {
            if (isBuilding) {
                TextButton(onClick = { onAction(NetworkBrowseAction.DismissSnapshotDialog) }) {
                    Text(stringResource(R.string.cancel))
                }
            } else {
                TextButton(
                    onClick = {
                        onAction(
                            NetworkBrowseAction.CreateSnapshot(
                                name = name.trim(),
                                recursive = ask?.recursive == true,
                            ),
                        )
                    },
                    enabled = targetId != null || name.isNotBlank(),
                ) {
                    Text(stringResource(if (targetId == null) R.string.create else R.string.add))
                }
            }
        },
        dismissButton = {
            if (!isBuilding) {
                TextButton(onClick = { onAction(NetworkBrowseAction.DismissSnapshotDialog) }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        },
    )
}

@Composable
private fun SnapshotTargetRow(
    selected: Boolean,
    title: String,
    onClick: () -> Unit,
    supporting: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DialogMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NetworkFileItem(
    file: NetworkFile,
    isFirstItem: Boolean,
    isLastItem: Boolean,
    isRecentlyPlayed: Boolean,
    playedPercentage: Float?,
    onClick: () -> Unit,
) {
    NextSegmentedListItem(
        contentPadding = PaddingValues(8.dp),
        isFirstItem = isFirstItem,
        isLastItem = isLastItem,
        onClick = onClick,
        colors = ListItemDefaults.segmentedColors(
            contentColor = if (isRecentlyPlayed) MaterialTheme.colorScheme.primary else ListItemDefaults.segmentedColors().contentColor,
            supportingContentColor = if (isRecentlyPlayed) MaterialTheme.colorScheme.primary else ListItemDefaults.segmentedColors().supportingContentColor,
        ),
        leadingContent = {
            if (file.isDirectory) {
                Box(modifier = Modifier.padding(horizontal = 8.dp)) {
                    Icon(
                        imageVector = ImageVector.vectorResource(id = R.drawable.folder_thumb),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .width(72.dp)
                            .aspectRatio(20 / 17f),
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .width(86.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp))
                        .aspectRatio(16f / 10f),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = NextIcons.Video,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.surfaceColorAtElevation(100.dp),
                        modifier = Modifier.fillMaxSize(0.5f),
                    )
                    if (playedPercentage != null) {
                        LinearProgressIndicator(
                            progress = { playedPercentage.coerceIn(0f, 1f) },
                            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp),
                            gapSize = 0.dp,
                            drawStopIndicator = {},
                        )
                    }
                }
            }
        },
        content = {
            Text(
                text = file.name,
                maxLines = 2,
                style = MaterialTheme.typography.titleMedium,
                overflow = TextOverflow.Ellipsis,
            )
        },
        // Secondary line: size for videos, last-modified date for folders.
        supportingContent = when {
            !file.isDirectory && file.size > 0 -> {
                { SupportingText(Utils.formatFileSize(file.size)) }
            }
            file.isDirectory && file.modified != null -> {
                val modified = file.modified!!
                { SupportingText(formatModifiedDate(modified)) }
            }
            else -> null
        },
    )
}

@Composable
private fun SupportingText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun formatModifiedDate(millis: Long): String {
    val context = LocalContext.current
    return remember(millis) {
        DateFormat.getMediumDateFormat(context).format(Date(millis))
    }
}
