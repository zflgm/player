package com.zflgm.player.feature.videopicker.screens

import com.zflgm.player.core.model.Folder

sealed interface MediaState {
    data object Loading : MediaState
    data class Success(val data: Folder?) : MediaState
}
