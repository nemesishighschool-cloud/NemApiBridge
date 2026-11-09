package com.ialocalbridge

/** A chooser label alone is insufficient when another activity uses the same
 * name or more than one eligible row is visible. Fail closed in both cases. */
internal object ResolverLabelPolicy {
    fun uniqueTrustedIndex(trusted: Set<String>, other: Set<String>, visible: List<String>): Int? {
        val safe = trusted.filterTo(mutableSetOf()) { it.isNotBlank() && it !in other }
        val indices = visible.indices.filter { visible[it] in safe }
        return indices.singleOrNull()
    }
}
