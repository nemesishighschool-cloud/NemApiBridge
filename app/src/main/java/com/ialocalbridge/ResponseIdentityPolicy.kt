package com.ialocalbridge

object ResponseIdentityPolicy {
    fun isNew(answer: String, previous: String, startedWithoutScroll: Boolean, verifiedIdentity: Boolean): Boolean {
        if (answer.isBlank()) return false
        if (startedWithoutScroll) return verifiedIdentity
        val resemblesPrevious = previous.isNotBlank() && previous.contains(answer)
        return !resemblesPrevious || verifiedIdentity
    }
}
