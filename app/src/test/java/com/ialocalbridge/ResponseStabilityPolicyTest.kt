package com.ialocalbridge

import org.junit.Assert.assertEquals
import org.junit.Test

class ResponseStabilityPolicyTest {
    @Test fun activeGenerationAlwaysResetsTheCount() {
        val state = ResponseStabilityPolicy.State("old answer", 3)
        assertEquals(ResponseStabilityPolicy.State(), ResponseStabilityPolicy.observe(state, "old answer", true, true))
    }
    @Test fun candidateMustRemainNewAndUnchangedAfterInactivity() {
        val initial = ResponseStabilityPolicy.State()
        val first = ResponseStabilityPolicy.observe(initial, "answer", true, false)
        val second = ResponseStabilityPolicy.observe(first, "answer", true, false)
        assertEquals(0, first.equalPolls)
        assertEquals(1, second.equalPolls)
        assertEquals(ResponseStabilityPolicy.State("changed", 0), ResponseStabilityPolicy.observe(second, "changed", true, false))
        assertEquals(initial, ResponseStabilityPolicy.observe(second, "answer", false, false))
        assertEquals(initial, ResponseStabilityPolicy.observe(second, "", true, false))
        assertEquals(initial, ResponseStabilityPolicy.observe(second, "answer", true, true))
    }
}
