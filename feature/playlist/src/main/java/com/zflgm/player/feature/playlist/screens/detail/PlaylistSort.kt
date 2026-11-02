package com.zflgm.player.feature.playlist.screens.detail

import androidx.annotation.StringRes
import com.zflgm.player.core.model.PlaylistItem
import com.zflgm.player.core.model.PlaylistItemSortMode
import com.zflgm.player.core.model.compareNaturalTitles
import com.zflgm.player.core.ui.R
import kotlin.random.Random

/**
 * An ordering the user applies once and keeps: the result is written to `playlist_item.position`, so
 * the play order and the resume item both follow it after the screen is left.
 */
enum class PlaylistSort(@StringRes val labelRes: Int) {
    NAME_ASCENDING(R.string.sort_name_ascending),
    NAME_DESCENDING(R.string.sort_name_descending),
    DURATION_ASCENDING(R.string.sort_duration_ascending),
    DURATION_DESCENDING(R.string.sort_duration_descending),
    SHUFFLE(R.string.sort_shuffle),
    INSERTION_ORDER(R.string.sort_restore_added_order),
}

/**
 * The uris in [sort] order, for `replaceOrder`. [PlaylistSort.INSERTION_ORDER] is not computed here:
 * this list already comes back ordered by `position`, so only the database still knows how the rows
 * were added, and the repository restores that one.
 */
internal fun playlistUrisForSort(
    items: List<PlaylistItem>,
    sort: PlaylistSort,
    random: Random = Random.Default,
): List<String> {
    val ordered = when (sort) {
        PlaylistSort.NAME_ASCENDING -> items.sortedWith(nameComparator(descending = false))
        PlaylistSort.NAME_DESCENDING -> items.sortedWith(nameComparator(descending = true))
        PlaylistSort.DURATION_ASCENDING -> items.sortedWith(durationComparator(descending = false))
        PlaylistSort.DURATION_DESCENDING -> items.sortedWith(durationComparator(descending = true))
        PlaylistSort.SHUFFLE -> items.shuffled(random)
        PlaylistSort.INSERTION_ORDER -> error("Insertion order is restored by the repository")
    }
    return ordered.map { it.uri }
}

/** Whether the duration rows are worth offering; a network snapshot knows no durations until metadata lands. */
internal fun List<PlaylistItem>.hasSortableDuration(): Boolean = any { it.durationMs != null }

/** Whether this row belongs in the menu of a list, given what that list knows about durations. */
internal fun PlaylistSort.showsFor(hasDuration: Boolean): Boolean =
    hasDuration || (this != PlaylistSort.DURATION_ASCENDING && this != PlaylistSort.DURATION_DESCENDING)

private fun nameComparator(descending: Boolean): Comparator<PlaylistItem> = Comparator { first, second ->
    if (descending) {
        compareNaturalTitles(second.displayTitle, first.displayTitle)
    } else {
        compareNaturalTitles(first.displayTitle, second.displayTitle)
    }
}

private fun durationComparator(descending: Boolean): Comparator<PlaylistItem> = Comparator { first, second ->
    val firstDuration = first.durationMs
    val secondDuration = second.durationMs
    when {
        // Items with no known duration stay last in both directions: they carry no information, and
        // flipping them to the top would hide every real length behind a column of blanks.
        firstDuration == null && secondDuration == null -> 0
        firstDuration == null -> 1
        secondDuration == null -> -1
        descending -> secondDuration.compareTo(firstDuration)
        else -> firstDuration.compareTo(secondDuration)
    }
}

/** Milliseconds when the app knows them: MediaStore for local videos, EXTINF seconds for M3U rows. */
private val PlaylistItem.durationMs: Long?
    get() = video?.duration?.takeIf { it > 0 }
        ?: duration.takeIf { it > 0 }?.times(1000L)

/** The remembered mode this one-shot sort stands for. */
internal fun PlaylistSort.toItemSortMode(): PlaylistItemSortMode = when (this) {
    PlaylistSort.NAME_ASCENDING -> PlaylistItemSortMode.NAME_ASCENDING
    PlaylistSort.NAME_DESCENDING -> PlaylistItemSortMode.NAME_DESCENDING
    PlaylistSort.DURATION_ASCENDING -> PlaylistItemSortMode.DURATION_ASCENDING
    PlaylistSort.DURATION_DESCENDING -> PlaylistItemSortMode.DURATION_DESCENDING
    PlaylistSort.SHUFFLE -> PlaylistItemSortMode.SHUFFLE
    PlaylistSort.INSERTION_ORDER -> PlaylistItemSortMode.INSERTION_ORDER
}

/** The sort that reproduces [this] mode, or null when only a hand-made order can. */
internal fun PlaylistItemSortMode.toPlaylistSortOrNull(): PlaylistSort? = when (this) {
    PlaylistItemSortMode.CUSTOM -> null
    PlaylistItemSortMode.NAME_ASCENDING -> PlaylistSort.NAME_ASCENDING
    PlaylistItemSortMode.NAME_DESCENDING -> PlaylistSort.NAME_DESCENDING
    PlaylistItemSortMode.DURATION_ASCENDING -> PlaylistSort.DURATION_ASCENDING
    PlaylistItemSortMode.DURATION_DESCENDING -> PlaylistSort.DURATION_DESCENDING
    PlaylistItemSortMode.SHUFFLE -> PlaylistSort.SHUFFLE
    PlaylistItemSortMode.INSERTION_ORDER -> PlaylistSort.INSERTION_ORDER
}
