package dev.anilbeesetti.nextplayer.core.media.network

import dev.anilbeesetti.nextplayer.core.model.NetworkFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.koin.core.annotation.Single

/**
 * Walks a network folder and emits its video files.
 *
 * Folder paths are never assembled here: every entry comes from [NetworkClient.listFiles], which
 * already returns paths in the form its own protocol expects, so the walk works for all protocols.
 */
interface NetworkDirectoryScanner {
    /**
     * Emits video files under [rootPath], shallowest folder first and alphabetical within a folder, so
     * a long scan fills the list in the order the user expects. Unreadable sub-folders are skipped:
     * one denied directory must not abort a scan of thousands.
     */
    fun scan(client: NetworkClient, rootPath: String, recursive: Boolean): Flow<NetworkFile>
}

@Single
class DefaultNetworkDirectoryScanner : NetworkDirectoryScanner {

    override fun scan(client: NetworkClient, rootPath: String, recursive: Boolean): Flow<NetworkFile> = flow {
        val queue = ArrayDeque<String>()
        val visited = mutableSetOf(rootPath)
        queue.addLast(rootPath)
        while (queue.isNotEmpty()) {
            val files = client.listFiles(queue.removeFirst()).getOrNull() ?: continue
            for (file in files.sortedBy { it.name.lowercase() }) {
                if (file.isDirectory) {
                    if (recursive && visited.add(file.path)) queue.addLast(file.path)
                } else if (isNetworkVideoFile(file.name)) {
                    emit(file)
                }
            }
        }
    }
}
