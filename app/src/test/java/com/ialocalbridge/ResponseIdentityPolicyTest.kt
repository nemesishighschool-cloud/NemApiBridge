package com.ialocalbridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseIdentityPolicyTest {
    @Test fun noAnswerOrUnverifiedNonScrollingAnswerIsNeverAccepted() {
        assertFalse(ResponseIdentityPolicy.isNew("", "", true, true))
        assertFalse(ResponseIdentityPolicy.isNew("New reply", "", true, false))
        assertTrue(ResponseIdentityPolicy.isNew("New reply", "", true, true))
    }
    @Test fun repeatedOrShrunkenPreviousAnswerRequiresIdentity() {
        assertFalse(ResponseIdentityPolicy.isNew("Existing", "Existing", false, false))
        assertFalse(ResponseIdentityPolicy.isNew("sting", "Existing", false, false))
        assertTrue(ResponseIdentityPolicy.isNew("Existing", "Existing", false, true))
    }
    @Test fun genuinelyDifferentAnswerMayBeAcceptedOnScrollingScreens() {
        assertTrue(ResponseIdentityPolicy.isNew("Different", "Existing", false, false))
        assertTrue(ResponseIdentityPolicy.isNew("First", "", false, false))
        assertFalse(ResponseIdentityPolicy.isNew("   ", "", false, true))
    }
}
