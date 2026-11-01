package com.zflgm.player.core.media.network

import com.zflgm.player.core.model.NetworkConnection

fun interface NetworkClientFactory {
    fun create(connection: NetworkConnection): NetworkClient
}
