package com.zflgm.player.core.data.repository

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.zflgm.player.core.common.Utils
import com.zflgm.player.core.data.mappers.toAudioStreamInfo
import com.zflgm.player.core.data.mappers.toFolder
import com.zflgm.player.core.data.mappers.toSubtitleStreamInfo
import com.zflgm.player.core.data.mappers.toVideo
import com.zflgm.player.core.data.mappers.toVideoState
import com.zflgm.player.core.data.mappers.toVideoStreamInfo
import com.zflgm.player.core.data.models.VideoState
import com.zflgm.player.core.database.converter.UriListConverter
import com.zflgm.player.core.database.dao.MediumStateDao
import com.zflgm.player.core.database.entities.MediumStateEntity
import com.zflgm.player.core.media.services.MediaService
import com.zflgm.player.core.model.Folder
import com.zflgm.player.core.model.MediaInfo
import com.zflgm.player.core.model.Video
import io.github.anilbeesetti.nextlib.mediainfo.MediaInfoBuilder
import java.util.Date
import kotlin.math.absoluteValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Factory

@Factory
class LocalMediaRepository(
    private val mediumStateDao: MediumStateDao,
    private val mediaService: MediaService,
    private val context: Context,
) : MediaRepository {

    override fun observeFolders(folderPath: String?): Flow<List<Folder>> {
        return mediaService.observeFolders(folderPath).map { mediaFolders ->
            mediaFolders.map { it.toFolder() }
        }
    }

    override fun observeVideos(folderPath: String?): Flow<List<Video>> {
        return combine(mediaService.observeVideos(folderPath), mediumStateDao.getAll()) { mediaVideos, mediumStates ->
            val statesMap = mediumStates.associateBy { it.uriString }
            mediaVideos.map { mediaVideo ->
                val mediaState = statesMap[mediaVideo.uri.toString()]
                mediaVideo.toVideo(mediaState)
            }
        }
    }

    override suspend fun fetchFolders(folderPath: String?): List<Folder> {
        return mediaService.fetchFolders(folderPath).map { it.toFolder() }
    }

    override suspend fun fetchVideos(folderPath: String?): List<Video> {
        val mediaVideos = mediaService.fetchVideos(folderPath)
        // One batch lookup instead of a query per video (N+1), and no unbounded coroutine fan-out.
        val statesByUri = mediumStateDao.getAllByUris(mediaVideos.map { it.uri.toString() })
            .associateBy { it.uriString }
        return mediaVideos.map { mediaVideo ->
            mediaVideo.toVideo(statesByUri[mediaVideo.uri.toString()])
        }
    }

    override fun observePlaybackHistory(): Flow<List<Video>> {
        return combine(mediaService.observeVideos(), mediumStateDao.getAll()) { mediaVideos, mediumStates ->
            val videosByUri = mediaVideos.associateBy { it.uri.toString() }
            mediumStates
                .filter { it.lastPlayedTime != null }
                .sortedByDescending { it.lastPlayedTime }
                .map { state ->
                    videosByUri[state.uriString]?.toVideo(state) ?: state.toHistoryVideo()
                }
        }
    }

    override fun observeTrashVideos(): Flow<List<Video>> {
        return mediaService.observeTrashVideos().map { videos -> videos.map { it.toVideo() } }
    }

    override suspend fun getVideoByUri(uri: String): Video? = coroutineScope {
        val mediaVideoDeferred = async { mediaService.findVideo(uri.toUri()) }
        val mediaStateDeferred = async { mediumStateDao.get(uri) }

        val mediaVideo = mediaVideoDeferred.await() ?: return@coroutineScope null
        val mediaState = mediaStateDeferred.await()

        return@coroutineScope mediaVideo.toVideo(mediaState)
    }

    override suspend fun getVideoState(uri: String): VideoState? {
        return mediumStateDao.get(uri)?.toVideoState()
    }

    override suspend fun getMediaInfo(uri: String): MediaInfo? = withContext(Dispatchers.IO) {
        val video = getVideoByUri(uri) ?: return@withContext null
        val mediaInfo = runCatching { MediaInfoBuilder().from(context = context, uri = uri.toUri()).build() }.getOrNull()
        val result = MediaInfo(
            video = video.copy(format = mediaInfo?.format),
            videoStream = mediaInfo?.videoStream?.toVideoStreamInfo(),
            audioStreams = mediaInfo?.audioStreams?.map { it.toAudioStreamInfo() } ?: emptyList(),
            subtitleStreams = mediaInfo?.subtitleStreams?.map { it.toSubtitleStreamInfo() } ?: emptyList(),
        )
        mediaInfo?.release()
        return@withContext result
    }

    override suspend fun clearPlaybackHistory() {
        mediumStateDao.clearPlaybackHistory()
    }

    override suspend fun updateMediumLastPlayedTime(uri: String, lastPlayedTime: Long, duration: Long?) {
        mediumStateDao.setLastPlayed(uri, lastPlayedTime, duration)
    }

    override suspend fun updateMediumPosition(uri: String, position: Long) {
        mediumStateDao.setPosition(uri, position)
    }

    override suspend fun updateMediumPlaybackSpeed(uri: String, playbackSpeed: Float) {
        mediumStateDao.setSpeed(uri, playbackSpeed)
    }

    override suspend fun updateMediumAudioTrack(uri: String, audioTrackIndex: Int) {
        mediumStateDao.setAudioTrack(uri, audioTrackIndex)
    }

    override suspend fun updateMediumSubtitleTrack(uri: String, subtitleTrackIndex: Int) {
        mediumStateDao.setSubtitleTrack(uri, subtitleTrackIndex)
    }

    override suspend fun updateMediumZoom(uri: String, zoom: Float) {
        mediumStateDao.setScale(uri, zoom)
    }

    override suspend fun addExternalAudioToMedium(uri: String, audioUri: Uri) {
        val current = mediumStateDao.get(uri)?.externalAudio ?: ""
        val audio = UriListConverter.fromStringToList(current)
        if (audioUri in audio) return
        // Only the list column is written, so concurrent position/track saves cannot be lost.
        mediumStateDao.setExternalAudio(uri, UriListConverter.fromListToString(audio + audioUri))
    }

    override suspend fun addExternalSubtitleToMedium(uri: String, subtitleUri: Uri) {
        val current = mediumStateDao.get(uri)?.externalSubs ?: ""
        val subs = UriListConverter.fromStringToList(current)
        if (subtitleUri in subs) return
        mediumStateDao.setExternalSubs(uri, UriListConverter.fromListToString(subs + subtitleUri))
    }

    override suspend fun updateSubtitleDelay(uri: String, delay: Long) {
        mediumStateDao.setSubtitleDelay(uri, delay)
    }

    override suspend fun updateSubtitleSpeed(uri: String, speed: Float) {
        mediumStateDao.setSubtitleSpeed(uri, speed)
    }
}

private fun MediumStateEntity.toHistoryVideo(): Video {
    val uri = uriString.toUri()
    val name = uri.lastPathSegment?.substringAfterLast('/')
        ?: uri.host
        ?: uriString
    return Video(
        id = uriString.hashCode().toLong().absoluteValue,
        path = uriString,
        uriString = uriString,
        nameWithExtension = name,
        duration = duration ?: 0,
        width = 0,
        height = 0,
        size = 0,
        formattedDuration = duration?.let(Utils::formatDurationMillis).orEmpty(),
        playbackPosition = playbackPosition,
        lastPlayedAt = lastPlayedTime?.let(::Date),
    )
}
