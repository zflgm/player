package com.zflgm.player.core.media.network

import com.zflgm.player.core.model.NetworkFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.koin.core.annotation.Single

sealed interface ScanEvent {
    data class Video(val file: NetworkFile) : ScanEvent

    /**
     * A folder that could not be listed (denied, dropped mid-scan). Callers must treat its
     * contents as unknown — never as "empty" — or a partial scan will delete live data.
     */
    data class FolderUnreadable(val path: String) : ScanEvent
}

/**
 * Walks a network folder and emits its video files plus any folders it could not read.
 *
 * Folder paths are never assembled here: every entry comes from [NetworkClient.listFiles], which
 * already returns paths in the form its own protocol expects, so the walk works for all protocols.
 */
interface NetworkDirectoryScanner {
    /**
     * Emits video files under [rootPath], shallowest folder first and alphabetical within a folder, so
     * a long scan fills the list in the order the user expects. Unreadable sub-folders are reported
     * as [ScanEvent.FolderUnreadable] instead of aborting the walk — one denied directory must not
     * stop a scan of thousands — but they are never silently swallowed either.
     */
    fun scan(client: NetworkClient, rootPath: String, recursive: Boolean): Flow<ScanEvent>
}

@Single
class DefaultNetworkDirectoryScanner : NetworkDirectoryScanner {

    override fun scan(client: NetworkClient, rootPath: String, recursive: Boolean): Flow<ScanEvent> = flow {
        val queue = ArrayDeque<String>()
        val visited = mutableSetOf(rootPath)
        queue.addLast(rootPath)
        while (queue.isNotEmpty()) {
            val path = queue.removeFirst()
            val files = client.listFiles(path).getOrNull()
            if (files == null) {
                emit(ScanEvent.FolderUnreadable(path))
                continue
            }
            for (file in files.sortedBy { it.name.lowercase() }) {
                if (file.isDirectory) {
                    if (recursive && visited.add(file.path)) queue.addLast(file.path)
                } else if (isNetworkVideoFile(file.name)) {
                    emit(ScanEvent.Video(file))
                }
            }
        }
    }
}
