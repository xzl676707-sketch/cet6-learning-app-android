package com.cet6.study.data

import org.junit.Assert.*
import org.junit.Test

class WordQueueTest {
    @Test fun openingMiddleContinuesAfterCurrent() {
        assertEquals(4, nextQueueItem(listOf(1, 2, 3, 4, 5), 3, listOf(1, 2, 4, 5)))
    }
    @Test fun retryWaitsUntilFollowingItems() {
        assertEquals(4, nextQueueItem(listOf(1, 2, 3, 4), 3, listOf(1, 2, 4, 3)))
    }
    @Test fun earlierItemsRemainAvailableAfterEnd() {
        assertEquals(1, nextQueueItem(listOf(1, 2, 3), 3, listOf(1, 2, 3)))
    }
    @Test fun removedItemsAreSkipped() {
        assertEquals(5, nextQueueItem(listOf(1, 2, 3, 4, 5), 3, listOf(1, 5)))
    }
    @Test fun emptyQueueFinishesAndSingleRetryRemains() {
        assertNull(nextQueueItem(listOf(1), 1, emptyList()))
        assertEquals(1, nextQueueItem(listOf(1), 1, listOf(1)))
    }
    @Test fun mixedWordAndNoteIdsKeepTheirOrder() {
        val word = "word" to 7L
        val note = "note" to 7L
        val nextWord = "word" to 8L
        assertEquals(note, nextQueueItem(listOf(word, note, nextWord), word, listOf(note, nextWord)))
        assertEquals(nextWord, nextQueueItem(listOf(word, note, nextWord), note, listOf(word, nextWord)))
    }
}
