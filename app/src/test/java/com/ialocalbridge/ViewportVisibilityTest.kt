package com.ialocalbridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportVisibilityTest {
    private val viewport = ViewportBounds(0, 100, 1080, 1800)
    @Test fun excludesSmallScrollableToolbar() {
        assertFalse(ViewportVisibility.isPlausibleConversationScroll(ViewportBounds(32, 1393, 409, 1477), 720, 1600))
        assertTrue(ViewportVisibility.isPlausibleConversationScroll(ViewportBounds(0, 100, 720, 1250), 720, 1600))
        assertFalse(ViewportVisibility.isPlausibleConversationScroll(ViewportBounds(0, 0, 0, 100), 720, 1600))
    }
    @Test fun fullyVisibleMessageIsAccepted() {
        assertTrue(ViewportVisibility.strictlyInside(ViewportBounds(0, 400, 1080, 900), viewport, 1080, 2340))
    }
    @Test fun clippingOrTouchingVerticalBoundariesIsRejected() {
        assertFalse(ViewportVisibility.strictlyInside(ViewportBounds(0, 100, 1080, 900), viewport, 1080, 2340))
        assertFalse(ViewportVisibility.strictlyInside(ViewportBounds(0, 400, 1080, 1800), viewport, 1080, 2340))
        assertFalse(ViewportVisibility.strictlyInside(ViewportBounds(0, 50, 1080, 900), viewport, 1080, 2340))
        assertFalse(ViewportVisibility.strictlyInside(ViewportBounds(0, 400, 1080, 1900), viewport, 1080, 2340))
    }
    @Test fun invalidScreenOrBoundsAreRejected() {
        assertFalse(ViewportVisibility.strictlyInside(ViewportBounds(1, 400, 1, 900), viewport, 1080, 2340))
        assertFalse(ViewportVisibility.strictlyInside(ViewportBounds(-1, 400, 100, 900), viewport, 1080, 2340))
        assertFalse(ViewportVisibility.strictlyInside(ViewportBounds(0, 400, 1081, 900), viewport, 1080, 2340))
        assertFalse(ViewportVisibility.strictlyInside(ViewportBounds(0, 400, 1080, 900), viewport, 0, 0))
    }
}
