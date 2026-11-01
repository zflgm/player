package dev.anilbeesetti.nextplayer.core.model

enum class PlaylistType {
    LOCAL,
    M3U_URL,
    M3U_FILE,

    /** Snapshot of a network folder: paths only, refreshed by re-scanning the source. */
    NETWORK,
}

data class PlaylistSummary(
    val id: Long,
    val name: String,
    val type: PlaylistType,
    val itemCount: Int,
    val lastRefreshedAt: Long?,
)

/** Item counts applied by re-scanning a network snapshot. */
data class PlaylistSnapshotDiff(
    val added: Int,
    val removed: Int,
)

data class PlaylistRecord(
    val id: Long,
    val name: String,
    val type: PlaylistType,
    val source: String?,
    val items: List<PlaylistItemRecord>,
    val lastRefreshedAt: Long?,
)

data class PlaylistItemRecord(
    val position: Int,
    val uri: String,
    val title: String? = null,
    val tvgLogo: String? = null,
    val duration: Int = M3UPlaylistItem.UNKNOWN_DURATION,
    val groupTitle: String? = null,
    val lastPlayedAt: Long? = null,
)

data class PlaylistItem(
    val position: Int,
    val uri: String,
    val title: String?,
    val tvgLogo: String?,
    val duration: Int,
    val groupTitle: String?,
    val video: Video?,
    val lastPlayedAt: Long? = null,
) {
    val displayTitle: String
        get() = title?.takeIf(String::isNotBlank)
            ?: video?.displayName
            ?: uri.nameSegment().ifBlank { uri }

    val supportingText: String
        get() = video?.parentPath?.takeIf(String::isNotBlank) ?: uri.decodePercentEscapes()
}

/**
 * The name a URI carries by itself, for the rows that have no title of their own: a media queue built from
 * network snapshot rows only has the encoded path to show. MediaStore content URIs end in a numeric row id,
 * which is not a name, so this returns null rather than a number.
 */
fun playlistUriDisplayName(uri: String): String? =
    uri.nameSegment().takeIf { it.isNotBlank() && !it.all(Char::isDigit) }

/** The last path segment, percent-decoded and without its extension. */
private fun String.nameSegment(): String =
    substringBefore('?').substringAfterLast('/').decodePercentEscapes().substringBeforeLast('.')

/**
 * Restores the text behind `%XX` escapes. Network playlist rows store `Uri`-encoded paths and carry no
 * title, so without this the user reads `%E6%97%A5` where the file is named in Japanese or Chinese.
 * A `+` stays a `+` (form encoding would turn it into a space), and a run that is not valid UTF-8 is
 * left exactly as stored instead of showing replacement glyphs.
 */
internal fun String.decodePercentEscapes(): String {
    if (indexOf('%') < 0) return this
    val bytes = ArrayList<Byte>(length)
    var index = 0
    while (index < length) {
        val char = this[index]
        if (char == '%' && index + 2 < length) {
            val high = this[index + 1].digitToIntOrNull(16)
            val low = this[index + 2].digitToIntOrNull(16)
            if (high != null && low != null) {
                bytes.add(((high shl 4) or low).toByte())
                index += 3
                continue
            }
        }
        bytes.addAll(char.toString().toByteArray(Charsets.UTF_8).asList())
        index++
    }
    return String(bytes.toByteArray(), Charsets.UTF_8)
        .takeUnless { it.contains('\uFFFD') }
        ?: this
}

data class Playlist(
    val id: Long,
    val name: String,
    val type: PlaylistType,
    val source: String?,
    val items: List<PlaylistItem>,
    val lastRefreshedAt: Long?,
) {
    val lastPlayedItem: PlaylistItem?
        get() = items
            .maxByOrNull { it.lastPlayedAt ?: Long.MIN_VALUE }
            ?.takeIf { it.lastPlayedAt != null }

    /**
     * Whether this list owns its `position` column. Linked M3U playlists are excluded: refreshing one
     * deletes and re-inserts every row, so a stored order would not survive it.
     */
    val isOrderEditable: Boolean
        get() = type == PlaylistType.LOCAL || type == PlaylistType.NETWORK
}
