package com.zflgm.player.core.media.network

import com.zflgm.player.core.media.network.clients.FtpClient
import com.zflgm.player.core.media.network.clients.SftpClient
import com.zflgm.player.core.media.network.clients.SmbClient
import com.zflgm.player.core.media.network.clients.WebDavClient
import com.zflgm.player.core.media.network.keys.SshKeyStore
import com.zflgm.player.core.model.NetworkConnection
import com.zflgm.player.core.model.NetworkProtocol
import org.koin.core.annotation.Single

@Single
class DefaultNetworkClientFactory(
    private val sshKeyStore: SshKeyStore,
) : NetworkClientFactory {
    override fun create(connection: NetworkConnection): NetworkClient = when (connection.protocol) {
        NetworkProtocol.SMB -> SmbClient(connection)
        NetworkProtocol.FTP -> FtpClient(connection)
        NetworkProtocol.SFTP -> SftpClient(connection, sshKeyStore)
        NetworkProtocol.WEBDAV -> WebDavClient(connection)
    }
}
