package com.ialocalbridge

/** Counts identical observations only after the target reports inactivity. */
internal object ResponseStabilityPolicy {
    data class State(val text: String = "", val equalPolls: Int = 0)

    fun observe(previous: State, candidate: String, isNew: Boolean, generating: Boolean): State {
        if (generating || !isNew || candidate.isBlank()) return State()
        return if (candidate == previous.text) State(candidate, previous.equalPolls + 1) else State(candidate, 0)
    }
}
