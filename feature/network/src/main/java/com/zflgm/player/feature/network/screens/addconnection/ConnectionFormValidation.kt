package com.zflgm.player.feature.network.screens.addconnection

import com.zflgm.player.core.model.NetworkAuthentication
import com.zflgm.player.core.model.NetworkProtocol

internal fun canSaveConnection(
    name: String,
    host: String,
    protocol: NetworkProtocol,
    username: String,
    authentication: NetworkAuthentication,
    hasPrivateKey: Boolean,
    isTesting: Boolean,
): Boolean {
    if (name.isBlank() || host.isBlank() || isTesting) return false
    if (protocol != NetworkProtocol.SFTP) return true
    if (username.isBlank()) return false
    return authentication != NetworkAuthentication.SSH_KEY || hasPrivateKey
}
