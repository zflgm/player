package com.zflgm.player.core.model

/**
 * How the list of playlists is ordered.
 *
 * [CUSTOM] means the order the user arranged by hand. The name modes are view-level sorts that
 * leave the stored arrangement untouched, so switching back to [CUSTOM] returns to the order the
 * user built — the pattern Spotify ("Custom order") and other libraries follow.
 */
enum class PlaylistSortMode {
    NAME_ASCENDING,
    NAME_DESCENDING,
    CUSTOM,
}
