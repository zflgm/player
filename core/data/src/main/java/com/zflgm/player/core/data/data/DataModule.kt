package com.zflgm.player.core.data

import com.zflgm.player.core.data.repository.NetworkConnectionRepository
import com.zflgm.player.core.database.DatabaseModule
import com.zflgm.player.core.datastore.di.DataStoreModule
import com.zflgm.player.core.media.MediaModule
import com.zflgm.player.core.media.network.NetworkConnectionResolver
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

@Module(includes = [DatabaseModule::class, DataStoreModule::class, MediaModule::class])
@ComponentScan
class DataModule {
    /** Lets `core:media` resolve a playback uri's connection id without depending on `core:data`. */
    @Single
    fun providesNetworkConnectionResolver(
        repository: NetworkConnectionRepository,
    ): NetworkConnectionResolver = NetworkConnectionResolver { id -> repository.getConnection(id) }
}
