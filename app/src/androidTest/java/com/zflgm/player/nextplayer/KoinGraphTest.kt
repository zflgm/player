package com.zflgm.player

import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zflgm.player.core.common.di.DiQualifiers
import com.zflgm.player.core.data.repository.PreferencesRepository
import com.zflgm.player.core.media.network.datasource.NextDataSourceFactory
import com.zflgm.player.core.media.services.MediaOperationsService
import com.zflgm.player.core.media.services.MediaService
import com.zflgm.player.core.media.sync.MediaSynchronizer
import com.zflgm.player.core.model.ApplicationPreferences
import com.zflgm.player.core.model.PlayerPreferences
import com.zflgm.player.core.model.SearchHistory
import com.zflgm.player.feature.more.screens.history.HistoryViewModel
import com.zflgm.player.feature.more.screens.trash.TrashViewModel
import com.zflgm.player.feature.network.screens.addconnection.AddConnectionViewModel
import com.zflgm.player.feature.network.screens.browse.NetworkBrowseViewModel
import com.zflgm.player.feature.network.screens.list.NetworkViewModel
import com.zflgm.player.feature.player.PlayerViewModel
import com.zflgm.player.feature.playlist.screens.detail.PlaylistDetailViewModel
import com.zflgm.player.feature.playlist.screens.list.PlaylistListViewModel
import com.zflgm.player.feature.videopicker.screens.mediapicker.MediaPickerViewModel
import com.zflgm.player.feature.videopicker.screens.search.SearchViewModel
import com.zflgm.player.feature.videopicker.screens.vault.VaultViewModel
import com.zflgm.player.settings.screens.about.AboutPreferencesViewModel
import com.zflgm.player.settings.screens.about.LibrariesViewModel
import com.zflgm.player.settings.screens.appearance.AppearancePreferencesViewModel
import com.zflgm.player.settings.screens.audio.AudioPreferencesViewModel
import com.zflgm.player.settings.screens.general.GeneralPreferencesViewModel
import com.zflgm.player.settings.screens.gesture.GesturePreferencesViewModel
import com.zflgm.player.settings.screens.medialibrary.FolderPreferencesViewModel
import com.zflgm.player.settings.screens.medialibrary.MediaLibraryPreferencesViewModel
import com.zflgm.player.settings.screens.player.PlayerPreferencesViewModel
import com.zflgm.player.settings.screens.subtitle.SubtitlePreferencesViewModel
import com.zflgm.player.settings.screens.thumbnail.ThumbnailPreferencesViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.context.GlobalContext
import org.koin.core.parameter.parametersOf
import org.koin.core.qualifier.named
import org.koin.viewmodel.resolveViewModel

@RunWith(AndroidJUnit4::class)
class KoinGraphTest {
    private val koin get() = GlobalContext.get()

    @Test
    fun sharedServicesAndAllThreePreferenceStoresResolve() {
        runBlocking {
            assertSame(koin.get<PreferencesRepository>(), koin.get<PreferencesRepository>())
            assertSame(koin.get<MediaOperationsService>(), koin.get<MediaOperationsService>())
            assertSame(koin.get<MediaService>(), koin.get<MediaService>())
            assertSame(koin.get<MediaSynchronizer>(), koin.get<MediaSynchronizer>())
            assertSame(koin.get<NextDataSourceFactory>(), koin.get<NextDataSourceFactory>())
            withTimeout(10_000) {
                // Read the typed values to catch collisions between erased DataStore keys.
                koin.get<DataStore<ApplicationPreferences>>(named(DiQualifiers.APP_PREFERENCES)).data.first().mediaViewMode
                koin.get<DataStore<PlayerPreferences>>(named(DiQualifiers.PLAYER_PREFERENCES)).data.first().resume
                koin.get<DataStore<SearchHistory>>(named(DiQualifiers.SEARCH_HISTORY)).data.first().queries.size
            }
        }
    }

    @Test
    fun viewModelsResolveWithRuntimeParametersAndRespectTheirStore() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val store = ViewModelStore()
            val secondStore = ViewModelStore()
            try {
                viewModel<MainViewModel>(store)
                viewModel<PlayerViewModel>(store, PlayerViewModel.Output({}, {}, {}, {}, {}, {}, {}))
                viewModel<HistoryViewModel>(store, HistoryViewModel.Output({}, {}))
                viewModel<TrashViewModel>(store, TrashViewModel.Output({}, {}))
                viewModel<NetworkViewModel>(store, NetworkViewModel.Output({}, {}, {}, {}, {}, {}))
                viewModel<AddConnectionViewModel>(store, AddConnectionViewModel.Input(null), AddConnectionViewModel.Output({}))
                viewModel<NetworkBrowseViewModel>(store, NetworkBrowseViewModel.Input(-1, "/"), NetworkBrowseViewModel.Output({}, { _, _ -> }, { _, _ -> }))
                viewModel<PlaylistListViewModel>(store, PlaylistListViewModel.Output({}, {}))
                viewModel<PlaylistDetailViewModel>(store, PlaylistDetailViewModel.Input(-1), PlaylistDetailViewModel.Output({}, { _, _ -> }))
                viewModel<SearchViewModel>(store, SearchViewModel.Output({}, {}, {}))
                viewModel<VaultViewModel>(store, VaultViewModel.Output({}, {}, {}))
                viewModel<AboutPreferencesViewModel>(store, AboutPreferencesViewModel.Output({}, {}))
                viewModel<LibrariesViewModel>(store, LibrariesViewModel.Output({}))
                viewModel<AppearancePreferencesViewModel>(store, AppearancePreferencesViewModel.Output({}))
                viewModel<AudioPreferencesViewModel>(store, AudioPreferencesViewModel.Output({}))
                viewModel<GeneralPreferencesViewModel>(store, GeneralPreferencesViewModel.Output({}))
                viewModel<GesturePreferencesViewModel>(store, GesturePreferencesViewModel.Output({}))
                viewModel<FolderPreferencesViewModel>(store, FolderPreferencesViewModel.Output({}))
                viewModel<MediaLibraryPreferencesViewModel>(store, MediaLibraryPreferencesViewModel.Output({}, {}, {}))
                viewModel<PlayerPreferencesViewModel>(store, PlayerPreferencesViewModel.Output({}))
                viewModel<SubtitlePreferencesViewModel>(store, SubtitlePreferencesViewModel.Output({}))
                viewModel<ThumbnailPreferencesViewModel>(store, ThumbnailPreferencesViewModel.Output({}))

                val output = MediaPickerViewModel.Output({}, {}, {}, {}, {}, {}, {})
                val first = viewModel<MediaPickerViewModel>(store, MediaPickerViewModel.Input("/first"), output)
                assertEquals("/first", first.folderPath)
                assertSame(first, viewModel<MediaPickerViewModel>(store, MediaPickerViewModel.Input("/ignored"), output))
                val second = viewModel<MediaPickerViewModel>(secondStore, MediaPickerViewModel.Input("/second"), output)
                assertNotSame(first, second)
                assertEquals("/second", second.folderPath)
            } finally {
                store.clear()
                secondStore.clear()
            }
        }
    }

    @OptIn(KoinInternalApi::class)
    private inline fun <reified T : ViewModel> viewModel(store: ViewModelStore, vararg parameters: Any): T = resolveViewModel(
        vmClass = T::class,
        viewModelStore = store,
        extras = CreationExtras.Empty,
        scope = koin.scopeRegistry.rootScope,
        parameters = { parametersOf(*parameters) },
    )
}
