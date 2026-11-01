package dev.anilbeesetti.nextplayer.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompareNaturalTitlesTest {

    @Test
    fun digitsCompareAsNumbersNotAsText() {
        assertTrue(compareNaturalTitles("Episode 2", "Episode 10") < 0)
        assertTrue(compareNaturalTitles("Chapter 9", "Chapter 100") < 0)
    }

    @Test
    fun lettersStillCompareAsText() {
        assertTrue(compareNaturalTitles("Alpha", "Beta") < 0)
        assertTrue(compareNaturalTitles("abc", "abcd") < 0)
    }

    @Test
    fun caseDoesNotMatter() {
        assertTrue(compareNaturalTitles("episode 2", "Episode 10") < 0)
        assertEquals(0, compareNaturalTitles("Movie", "MOVIE"))
    }
}
