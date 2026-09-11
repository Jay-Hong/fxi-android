package com.jay.fxi.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnrecordedObligationsTest {

    @Test
    fun anOlderReleaseDoesNotClearANewerMark() {
        val memory = UnrecordedObligations()
        memory.markUid("a", 1)
        val read = checkNotNull(memory.uidMark("a"))
        memory.markUid("a", 2)

        memory.releaseUid("a", read)

        assertEquals(2L, memory.uidMark("a"))
    }

    @Test
    fun aReleaseAtTheMarksOwnRevisionClearsIt() {
        val memory = UnrecordedObligations()
        memory.markUid("a", 1)

        memory.releaseUid("a", 1)

        assertNull(memory.uidMark("a"))
    }

    @Test
    fun anOlderForgetDoesNotClearANewerToken() {
        val memory = UnrecordedObligations()
        memory.remember("a", "t1", 1)
        val read = memory.tokenMark("a", "t1")
        memory.remember("a", "t1", 2)

        memory.forget("a", "t1", read)

        assertEquals(2L, memory.tokenMark("a", "t1"))
    }

    @Test
    fun aForgetWithoutARevisionKeepsTheToken() {
        val memory = UnrecordedObligations()
        memory.remember("a", "t1", 1)

        memory.forget("a", "t1", null)

        assertEquals(1L, memory.tokenMark("a", "t1"))
    }

    @Test
    fun aLateMarkKeepsTheNewerRevision() {
        val memory = UnrecordedObligations()
        memory.markUid("a", 2)

        memory.markUid("a", 1)

        assertEquals(2L, memory.uidMark("a"))
    }

    @Test
    fun aLateTokenRecordKeepsTheNewerRevisionThroughAnOlderForget() {
        val memory = UnrecordedObligations()
        memory.remember("a", "t1", 1)
        val read = memory.tokenMark("a", "t1")
        memory.remember("a", "t1", 2)
        memory.remember("a", "t1", 1) // an older sign-out's record, arriving late

        memory.forget("a", "t1", read)

        assertEquals(2L, memory.tokenMark("a", "t1"))
    }

    @Test
    fun tokensAreKeptPerUid() {
        val memory = UnrecordedObligations()
        memory.remember("a", "t1", 1)
        memory.remember("b", "t1", 1)
        memory.remember("a", "t2", 1)

        assertEquals(setOf("a" to "t1", "a" to "t2"), memory.tokensFor("a").toSet())
    }
}
