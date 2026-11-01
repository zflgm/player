package dev.anilbeesetti.nextplayer.core.model

import kotlin.comparisons.reversed as kotlinReversed

/**
 * The digit-aware string order every title sort in the app uses, so "Episode 2" comes before
 * "Episode 10" in the library grid and in a playlist the same way.
 */
fun compareNaturalTitles(first: String, second: String): Int =
    naturalStringComparator.compare(first.lowercase(), second.lowercase())

private val naturalStringComparator = Comparator<String> { str1, str2 ->
    var str1Marker = 0
    var str2Marker = 0
    val str1Length = str1.length
    val str2Length = str2.length

    while (str1Marker < str1Length && str2Marker < str2Length) {
        val thisChunk = getChunk(str1, str1Length, str1Marker)
        str1Marker += thisChunk.length

        val thatChunk = getChunk(str2, str2Length, str2Marker)
        str2Marker += thatChunk.length

        // If both chunks contain numeric characters, sort them numerically.
        val result: Int
        if (thisChunk[0].isAsciiDigit() && thatChunk[0].isAsciiDigit()) {
            // Simple chunk comparison by length.
            val thisChunkLength = thisChunk.length
            val lengthDiff = thisChunkLength - thatChunk.length
            // If equal, the first different number counts.
            if (lengthDiff == 0) {
                for (i in 0 until thisChunkLength) {
                    val charDiff = thisChunk[i] - thatChunk[i]
                    if (charDiff != 0) {
                        return@Comparator charDiff
                    }
                }
                result = 0
            } else {
                result = lengthDiff
            }
        } else {
            result = thisChunk.compareTo(thatChunk)
        }

        if (result != 0) {
            return@Comparator result
        }
    }

    return@Comparator str1Length - str2Length
}

private fun getChunk(string: String, length: Int, marker: Int): String {
    var current = marker
    val chunk = StringBuilder()
    var c = string[current]
    chunk.append(c)
    current++
    if (c.isAsciiDigit()) {
        while (current < length) {
            c = string[current]
            if (!c.isAsciiDigit()) {
                break
            }
            chunk.append(c)
            current++
        }
    } else {
        while (current < length) {
            c = string[current]
            if (c.isAsciiDigit()) {
                break
            }
            chunk.append(c)
            current++
        }
    }
    return chunk.toString()
}

data class Sort(
    val by: By,
    val order: Order,
) {
    enum class By {
        TITLE,
        LENGTH,
        PATH,
        SIZE,
        DATE,
    }

    enum class Order {
        ASCENDING,
        DESCENDING,
    }

    fun videoComparator(): Comparator<Video> {
        val videoTitleComparator: Comparator<Video> = Comparator { video1, video2 ->
            return@Comparator compareNaturalTitles(video1.displayName, video2.displayName)
        }

        val videoPathComparator: Comparator<Video> = Comparator { video1, video2 ->
            return@Comparator compareNaturalTitles(video1.path, video2.path)
        }

        val comparator = when (by) {
            By.TITLE -> videoTitleComparator
            By.LENGTH -> compareBy<Video> { it.duration }.then(videoTitleComparator)
            By.PATH -> videoPathComparator
            By.SIZE -> compareBy<Video> { it.size }.then(videoTitleComparator)
            By.DATE -> compareBy<Video> { it.dateModified }.then(videoTitleComparator)
        }

        return when (order) {
            Order.ASCENDING -> comparator
            Order.DESCENDING -> comparator.reversedCompat()
        }
    }

    fun folderComparator(): Comparator<Folder> {
        val folderNameComparator: Comparator<Folder> = Comparator { folder1, folder2 ->
            return@Comparator compareNaturalTitles(folder1.name, folder2.name)
        }

        val folderPathComparator: Comparator<Folder> = Comparator { folder1, folder2 ->
            return@Comparator compareNaturalTitles(folder1.path, folder2.path)
        }

        val comparator = when (by) {
            By.TITLE -> folderNameComparator
            By.LENGTH -> compareBy<Folder> { it.totalDuration }.then(folderNameComparator)
            By.PATH -> folderPathComparator
            By.SIZE -> compareBy<Folder> { it.totalSize }.then(folderNameComparator)
            By.DATE -> compareBy<Folder> { it.dateModified }.then(folderNameComparator)
        }

        return when (order) {
            Order.ASCENDING -> comparator
            Order.DESCENDING -> comparator.reversedCompat()
        }
    }
}

// The length-based numeric ordering is contract-safe only for the contiguous ASCII digit range.
private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

fun <T> Comparator<T>.reversedCompat(): Comparator<T> = kotlinReversed()
