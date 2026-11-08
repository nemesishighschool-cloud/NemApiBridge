package com.ialocalbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ResponseFragmentStitcherTest {
    @Test fun stitchesOneUnambiguousOverlap() {
        assertEquals(listOf("a", "b", "c", "d"), ResponseFragmentStitcher.prepend(listOf("a", "b", "c"), listOf("b", "c", "d")))
    }
    @Test fun rejectsNoOverlap() {
        assertThrows(AutomationException::class.java) { ResponseFragmentStitcher.prepend(listOf("a"), listOf("b")) }
    }
    @Test fun rejectsAmbiguousRepeatedOverlap() {
        assertThrows(AutomationException::class.java) { ResponseFragmentStitcher.prepend(listOf("a", "a"), listOf("a", "a")) }
    }
    @Test fun restoresAnElementVisibleInOnlyOneFragment() {
        assertEquals(listOf("1", "a", "2", "b", "3", "c", "4"),
            ResponseFragmentStitcher.prepend(listOf("1", "a", "2", "b", "c"), listOf("2", "b", "3", "c", "4")))
    }
    @Test fun rejectsConflictingMissingElements() {
        assertThrows(AutomationException::class.java) {
            ResponseFragmentStitcher.prepend(listOf("a", "x", "b"), listOf("a", "y", "b", "c"))
        }
    }

    @Test fun identicalSingleTextRequiresSameNodeProof() {
        assertThrows(AutomationException::class.java) { ResponseFragmentStitcher.prepend(listOf("long"), listOf("long")) }
        assertEquals(listOf("long"), ResponseFragmentStitcher.prepend(listOf("long"), listOf("long"), sameSingleNode = true))
    }

}
