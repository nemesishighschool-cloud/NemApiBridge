package com.ialocalbridge

/** Align ordered fragments without guessing the order of two independently missing elements. */
object ResponseFragmentStitcher {
    fun prepend(previous: List<String>, current: List<String>, sameSingleNode: Boolean = false): List<String> {
        fun incomplete(message: String): Nothing = throw AutomationException("response_incomplete", message)
        if (previous.isEmpty() || current.isEmpty()) incomplete("Fragment de réponse vide.")
        if (sameSingleNode && previous.size == 1 && current.size == 1 && previous.single() == current.single()) return current
        val previousCounts = previous.groupingBy { it }.eachCount()
        val currentCounts = current.groupingBy { it }.eachCount()
        val common = previousCounts.keys.intersect(currentCounts.keys)
        if (common.any { previousCounts[it] != 1 || currentCounts[it] != 1 }) {
            incomplete("Éléments répétés : alignement des fragments ambigu.")
        }
        val currentPositions = current.withIndex().associate { it.value to it.index }
        val pairs = previous.withIndex().mapNotNull { (i, value) ->
            currentPositions[value]?.let { j -> i to j }
        }
        if (pairs.size < 2 || pairs.first().second != 0 || pairs.last().first != previous.lastIndex) {
            incomplete("Chevauchement des fragments absent ou insuffisant.")
        }
        if (pairs.zipWithNext().any { (a, b) -> a.second >= b.second }) {
            incomplete("Ordre des fragments incohérent.")
        }
        val merged = previous.take(pairs.first().first).toMutableList()
        merged.add(previous[pairs.first().first])
        for ((left, right) in pairs.zipWithNext()) {
            val beforeGap = previous.subList(left.first + 1, right.first)
            val afterGap = current.subList(left.second + 1, right.second)
            if (beforeGap.isNotEmpty() && afterGap.isNotEmpty()) {
                incomplete("Ordre des éléments manquants ambigu.")
            }
            merged.addAll(beforeGap)
            merged.addAll(afterGap)
            merged.add(previous[right.first])
        }
        merged.addAll(current.drop(pairs.last().second + 1))
        return merged
    }
}
