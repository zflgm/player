package com.zflgm.player.core.data.mappers

import com.zflgm.player.core.media.services.MediaFolder
import com.zflgm.player.core.model.Folder

internal fun MediaFolder.toFolder() = Folder(
    name = name,
    path = path,
    dateModified = dateModified,
    totalSize = totalSize,
    totalDuration = totalDuration,
    videosCount = videosCount,
    foldersCount = foldersCount,
)
