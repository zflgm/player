package dev.anilbeesetti.nextplayer.feature.network.screens.list

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anilbeesetti.nextplayer.core.model.NetworkConnection
import dev.anilbeesetti.nextplayer.core.model.NetworkProtocol
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.components.LocalNavigationBottomPadding
import dev.anilbeesetti.nextplayer.core.ui.components.NextDialog
import dev.anilbeesetti.nextplayer.core.ui.components.NextOutlinedTextField
import dev.anilbeesetti.nextplayer.core.ui.components.NextSegmentedListItem
import dev.anilbeesetti.nextplayer.core.ui.components.NextTopAppBar
import dev.anilbeesetti.nextplayer.core.ui.components.tvFocusRing
import dev.anilbeesetti.nextplayer.core.ui.components.tvListFocus
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons
import dev.anilbeesetti.nextplayer.core.ui.extensions.copy
import dev.anilbeesetti.nextplayer.core.ui.theme.NextPlayerTheme

@Composable
fun NetworkScreen(
    viewModel: NetworkViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    NetworkScreenContent(
        state = state,
        onAction = viewModel::onAction,
    )
}

@Composable
internal fun NetworkScreenContent(
    state: NetworkUiState,
    onAction: (NetworkAction) -> Unit,
) {
    var connectionToDelete by remember { mutableStateOf<NetworkConnection?>(null) }
    var streamUrl by rememberSaveable { mutableStateOf("") }
    val trimmedStreamUrl = streamUrl.trim()

    val showEmptyState = state.connections.isEmpty() && !state.isLoading
    val navigationBottomPadding = LocalNavigationBottomPadding.current

    Scaffold(
        topBar = {
            NextTopAppBar(
                title = stringResource(R.string.network),
                fontWeight = FontWeight.Bold,
                actions = {
                    IconButton(onClick = { onAction(NetworkAction.StartScan) }, modifier = Modifier.tvFocusRing()) {
                        Icon(
                            imageVector = NextIcons.Radar,
                            contentDescription = stringResource(R.string.scan_for_devices),
                        )
                    }
                    IconButton(onClick = { onAction(NetworkAction.AddConnection) }, modifier = Modifier.tvFocusRing()) {
                        Icon(
                            imageVector = NextIcons.Add,
                            contentDescription = stringResource(R.string.add_connection),
                        )
                    }
                    IconButton(onClick = { onAction(NetworkAction.OpenSettings) }, modifier = Modifier.tvFocusRing()) {
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
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .tvListFocus(),
                contentPadding = PaddingValues(8.dp).copy(
                    bottom = scaffoldPadding.calculateBottomPadding() + navigationBottomPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item {
                    NetworkStreamCard(
                        url = streamUrl,
                        onUrlChange = { streamUrl = it },
                        onOpenStream = { onAction(NetworkAction.OpenStream(trimmedStreamUrl.toUri())) },
                        enabled = trimmedStreamUrl.isNotEmpty(),
                    )
                }
                if (showEmptyState) {
                    item {
                        NetworkEmptyState()
                    }
                } else {
                    itemsIndexed(
                        items = state.connections,
                        key = { _, connection -> connection.id },
                    ) { index, connection ->
                        ConnectionItem(
                            connection = connection,
                            isFirstItem = index == 0,
                            isLastItem = index == state.connections.lastIndex,
                            onClick = { onAction(NetworkAction.OpenConnection(connection.id)) },
                            onEdit = { onAction(NetworkAction.EditConnection(connection.id)) },
                            onDelete = { connectionToDelete = connection },
                        )
                    }
                }
            }
        }
    }

    connectionToDelete?.let { connection ->
        NextDialog(
            onDismissRequest = { connectionToDelete = null },
            title = { Text(stringResource(R.string.delete_connection)) },
            content = { Text(stringResource(R.string.delete_connection_confirmation, connection.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onAction(NetworkAction.DeleteConnection(connection.id))
                        connectionToDelete = null
                    },
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { connectionToDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (state.scanPhase != ScanPhase.Idle) {
        ScanDevicesDialog(
            state = state,
            onAction = onAction,
        )
    }

    if (state.connectFlow != HostConnectFlow.None) {
        ConnectHostDialog(
            state = state,
            onAction = onAction,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ScanDevicesDialog(
    state: NetworkUiState,
    onAction: (NetworkAction) -> Unit,
) {
    val isRunning = state.scanPhase == ScanPhase.Running
    NextDialog(
        onDismissRequest = { onAction(NetworkAction.StopScan) },
        title = { Text(stringResource(R.string.discovered_devices)) },
        content = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (isRunning && state.discoveredHosts.isEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LinearProgressIndicator()
                        Text(stringResource(R.string.scanning_devices))
                    }
                }
                state.discoveredHosts.forEach { host ->
                    val isAdded = state.connections.any { it.host == host.address }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onAction(NetworkAction.PickDiscoveredHost(host.address)) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = host.name ?: host.address,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = if (host.name != null) host.address else "SMB · ${host.address}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (isAdded) {
                                Text(
                                    text = stringResource(R.string.device_already_added),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
                if (!isRunning && state.discoveredHosts.isEmpty()) {
                    Text(
                        text = stringResource(R.string.no_devices_found),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAction(NetworkAction.StopScan) }) {
                Text(stringResource(if (isRunning) R.string.stop else R.string.cancel))
            }
        },
        dismissButton = {
            if (!isRunning && state.discoveredHosts.isNotEmpty()) {
                TextButton(onClick = { onAction(NetworkAction.StartScan) }) {
                    Text(stringResource(R.string.scan_again))
                }
            }
        },
    )
}

@Composable
private fun ConnectHostDialog(
    state: NetworkUiState,
    onAction: (NetworkAction) -> Unit,
) {
    val flow = state.connectFlow
    val host = when (flow) {
        is HostConnectFlow.Credentials -> flow.host
        is HostConnectFlow.Enumerating -> flow.host
        HostConnectFlow.None -> return
    }
    var username by rememberSaveable(host) { mutableStateOf("") }
    var password by rememberSaveable(host) { mutableStateOf("") }

    NextDialog(
        onDismissRequest = { onAction(NetworkAction.DismissConnectFlow) },
        title = { Text(stringResource(R.string.connect_to_host, host)) },
        content = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (flow) {
                    is HostConnectFlow.Credentials -> {
                        NextOutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.username)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        )
                        NextOutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.password)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        )
                        if (flow.failed) {
                            Text(
                                text = stringResource(R.string.enumeration_failed),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (flow.noShares) {
                            Text(
                                text = stringResource(R.string.no_shares_found),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (flow.failed || flow.noShares) {
                            TextButton(onClick = { onAction(NetworkAction.EnterDetailsManually) }) {
                                Text(stringResource(R.string.enter_details_manually))
                            }
                        }
                    }
                    is HostConnectFlow.Enumerating -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LinearProgressIndicator()
                            Text(stringResource(R.string.loading_shares))
                        }
                    }
                    HostConnectFlow.None -> Unit
                }
            }
        },
        confirmButton = {
            if (flow is HostConnectFlow.Credentials) {
                TextButton(
                    onClick = { onAction(NetworkAction.SubmitCredentials(host, username.trim(), password)) },
                ) {
                    Text(stringResource(R.string.connect))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { onAction(NetworkAction.DismissConnectFlow) }) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun NetworkStreamCard(
    modifier: Modifier = Modifier,
    url: String,
    onUrlChange: (String) -> Unit,
    onOpenStream: () -> Unit,
    enabled: Boolean,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.network_stream),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.enter_a_network_url),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        NextOutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.example_url)) },
            singleLine = true,
        )
        Button(
            onClick = onOpenStream,
            enabled = enabled,
            modifier = Modifier.align(Alignment.End),
        ) {
            Text(stringResource(R.string.open_network_stream))
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ConnectionItem(
    connection: NetworkConnection,
    isFirstItem: Boolean,
    isLastItem: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by rememberSaveable { mutableStateOf(false) }

    NextSegmentedListItem(
        contentPadding = PaddingValues(8.dp),
        isFirstItem = isFirstItem,
        isLastItem = isLastItem,
        onClick = onClick,
        leadingContent = {
            Box(
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .size(48.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = connection.protocol.icon(),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
        },
        content = {
            Text(
                text = connection.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text = "${connection.protocol.name} · ${connection.host}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(NextIcons.ExtraSettings, contentDescription = null)
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.edit)) },
                        leadingIcon = { Icon(NextIcons.Edit, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onEdit()
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
private fun NetworkEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = NextIcons.Network,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.size(16.dp))
        Text(
            text = stringResource(R.string.no_connections_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.no_connections_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

internal fun NetworkProtocol.icon(): ImageVector = when (this) {
    NetworkProtocol.SMB -> NextIcons.Storage
    NetworkProtocol.FTP -> NextIcons.Dns
    NetworkProtocol.SFTP -> NextIcons.Dns
    NetworkProtocol.WEBDAV -> NextIcons.Cloud
}

@PreviewLightDark
@Composable
private fun NetworkScreenPreview() {
    NextPlayerTheme {
        NetworkScreenContent(
            state = NetworkUiState(connections = listOf(NetworkConnection.sample), isLoading = false),
            onAction = {},
        )
    }
}
