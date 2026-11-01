package com.zflgm.player.core.datastore.datasource

import androidx.datastore.core.DataStore
import com.zflgm.player.core.common.Logger
import com.zflgm.player.core.common.di.DiQualifiers
import com.zflgm.player.core.model.SearchHistory
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Named

@Factory(binds = [])
class SearchHistoryDataSource(
    @Named(DiQualifiers.SEARCH_HISTORY) private val searchHistoryDataStore: DataStore<SearchHistory>,
) {

    companion object {
        private const val TAG = "SearchHistoryDataSource"
    }

    val searchHistory: Flow<SearchHistory> = searchHistoryDataStore.data

    suspend fun update(
        transform: suspend (SearchHistory) -> SearchHistory,
    ) {
        try {
            searchHistoryDataStore.updateData(transform)
        } catch (ioException: IOException) {
            // Only swallow disk IO failures. CancellationException and other programming errors
            // must propagate so structured concurrency keeps working.
            Logger.logError(TAG, "Failed to update search history: $ioException")
        }
    }
}
