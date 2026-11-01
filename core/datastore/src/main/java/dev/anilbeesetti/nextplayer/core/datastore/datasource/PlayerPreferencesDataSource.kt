package dev.anilbeesetti.nextplayer.core.datastore.datasource

import androidx.datastore.core.DataStore
import dev.anilbeesetti.nextplayer.core.common.Logger
import dev.anilbeesetti.nextplayer.core.common.di.DiQualifiers
import dev.anilbeesetti.nextplayer.core.model.PlayerPreferences
import java.io.IOException
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Named

@Factory(binds = [])
class PlayerPreferencesDataSource(
    @Named(DiQualifiers.PLAYER_PREFERENCES) private val preferencesDataStore: DataStore<PlayerPreferences>,
) : PreferencesDataSource<PlayerPreferences> {

    companion object {
        private const val TAG = "PlayerPreferencesDataSource"
    }

    override val preferences = preferencesDataStore.data

    override suspend fun update(transform: suspend (PlayerPreferences) -> PlayerPreferences) {
        try {
            preferencesDataStore.updateData(transform)
        } catch (ioException: IOException) {
            // Only swallow disk IO failures. CancellationException and other programming errors
            // must propagate so structured concurrency keeps working.
            Logger.logError(TAG, "Failed to update player preferences: $ioException")
        }
    }
}
