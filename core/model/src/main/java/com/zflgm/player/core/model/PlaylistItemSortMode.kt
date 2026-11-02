package com.zflgm.player.core.model

/**
 * How a playlist's own rows are ordered.
 *
 * [CUSTOM] is the arrangement the user produced by dragging. The automatic modes are re-applied
 * when rows are added, so a list marked "name A-Z" does not grow a stray episode at the bottom
 * after an import. [SHUFFLE] is deliberately not automatic: randomness cannot be maintained, so
 * new rows simply arrive last while the label keeps saying what the user last chose.
 */
enum class PlaylistItemSortMode {
    NAME_ASCENDING,
    NAME_DESCENDING,
    DURATION_ASCENDING,
    DURATION_DESCENDING,
    SHUFFLE,
    INSERTION_ORDER,
    CUSTOM,
    ;

    /** Whether this ordering can be recomputed from the rows themselves. */
    val isAutomatic: Boolean
        get() = this != SHUFFLE && this != CUSTOM
}
