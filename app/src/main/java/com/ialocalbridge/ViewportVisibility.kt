package com.ialocalbridge

/** Pure geometry check for non-scrolling conversations. */
data class ViewportBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

object ViewportVisibility {
    // A horizontal toolbar can be marked scrollable too. The threshold is relative
    // to current screen bounds, not a stored position or a calibration.
    fun isPlausibleConversationScroll(bounds: ViewportBounds, screenWidth: Int, screenHeight: Int): Boolean {
        if (screenWidth <= 0 || screenHeight <= 0 || bounds.right <= bounds.left || bounds.bottom <= bounds.top) return false
        return (bounds.bottom - bounds.top).toLong() * 100 >= screenHeight.toLong() * 15 &&
            (bounds.right - bounds.left).toLong() * 100 >= screenWidth.toLong() * 30
    }

    fun strictlyInside(message: ViewportBounds, viewport: ViewportBounds, screenWidth: Int, screenHeight: Int): Boolean {
        if (screenWidth <= 0 || screenHeight <= 0 || message.right <= message.left || message.bottom <= message.top) return false
        if (viewport.right <= viewport.left || viewport.bottom <= viewport.top) return false
        if (message.left < 0 || message.top < 0 || message.right > screenWidth || message.bottom > screenHeight) return false
        if (message.left < viewport.left || message.right > viewport.right) return false
        return message.top > viewport.top && message.bottom < viewport.bottom
    }
}
