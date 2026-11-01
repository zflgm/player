package com.zflgm.player.core.datastore.datasource

import androidx.datastore.core.DataStore
import com.zflgm.player.core.common.Logger
import com.zflgm.player.core.common.di.DiQualifiers
import com.zflgm.player.core.model.ApplicationPreferences
import java.io.IOException
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Named

@Factory(binds = [])
class AppPreferencesDataSource(
    @Named(DiQualifiers.APP_PREFERENCES) private val appPreferences: DataStore<ApplicationPreferences>,
) : PreferencesDataSource<ApplicationPreferences> {

    companion object {
        private const val TAG = "AppPreferencesDataSource"
    }

    override val preferences = appPreferences.data

    override suspend fun update(
        transform: suspend (ApplicationPreferences) -> ApplicationPreferences,
    ) {
        try {
            appPreferences.updateData(transform)
        } catch (ioException: IOException) {
            // Only swallow disk IO failures. CancellationException and other programming errors
            // must propagate so structured concurrency keeps working.
            Logger.logError(TAG, "Failed to update app preferences: $ioException")
        }
    }
}
