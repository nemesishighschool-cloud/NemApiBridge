package com.ialocalbridge

import org.json.JSONArray
import org.json.JSONObject
import com.ialocalbridge.utils.ToolTagScanner

/** Conservative continuation of incomplete tool output. Never repair invalid JSON silently. */
object ToolContinuation {
    private val toolFence = Regex("(?m)^[ \\t]*(`{3,}|~{3,})[ \\t]*(?:tool|tool_call)[ \\t]*\\r?\\n", RegexOption.IGNORE_CASE)
    private val structurePairs = listOf(
        "<function=[^>]+>" to "</function\\s*>",
        "<parameter=[^>]+>" to "</parameter\\s*>",
        "<invoke\\s+name=[^>]+>" to "</invoke\\s*>",
        "<parameter\\s+name=[^>]+>" to "</parameter\\s*>",
        "<\\s*[|｜]{1,2}\\s*DSML\\s*[|｜]{1,2}\\s*(?:tool_calls|function_calls|calls)\\s*>" to "<\\s*/\\s*[|｜]{1,2}\\s*DSML\\s*[|｜]{1,2}\\s*(?:tool_calls|function_calls|calls)\\s*>",
        "<\\s*[|｜]{1,2}\\s*DSML\\s*[|｜]{1,2}\\s*invoke\\b[^>]*>" to "<\\s*/\\s*[|｜]{1,2}\\s*DSML\\s*[|｜]{1,2}\\s*invoke\\s*>",
        "<\\s*[|｜]{1,2}\\s*DSML\\s*[|｜]{1,2}\\s*parameter\\b[^>]*>" to "<\\s*/\\s*[|｜]{1,2}\\s*DSML\\s*[|｜]{1,2}\\s*parameter\\s*>",
        "<\\s*[|｜]tool[▁_ ]calls[▁_ ]begin[|｜]\\s*>" to "<\\s*[|｜]tool[▁_ ]calls[▁_ ]end[|｜]\\s*>",
        "<\\s*[|｜]tool[▁_ ]call[▁_ ]begin[|｜]\\s*>" to "<\\s*[|｜]tool[▁_ ]call[▁_ ]end[|｜]\\s*>"
    ).map { Regex(it.first, RegexOption.IGNORE_CASE) to Regex(it.second, RegexOption.IGNORE_CASE) }

    fun isIncomplete(text: String): Boolean {
        val direct = text.trim()
        if ((direct.startsWith("{") || direct.startsWith("[")) && validJson(direct)) return false
        val masked = StringBuilder(text)
        fun mask(start: Int, end: Int) { for (i in start until end) masked.setCharAt(i, ' ') }
        for (fence in toolFence.findAll(text)) {
            val bodyStart = fence.range.last + 1
            val body = text.substring(bodyStart)
            val delimiter = fence.groupValues[1]
            val end = Regex("(?m)^[ \\t]*" + Regex.escape(delimiter) + "[ \\t]*(?:\\r?$)").find(body)
            val inner = (if (end == null) body else body.substring(0, end.range.first)).trim()
            if (inner.startsWith("{") || inner.startsWith("[")) {
                if (end == null || !validJson(inner)) return true
                mask(fence.range.first, bodyStart + end.range.last + 1)
            }
        }
        val tags = ToolTagScanner.scan(masked.toString())
        if (tags.incompleteStart != null) return true
        for (tag in tags.tags) {
            val inner = tag.body.trim()
            if (inner.startsWith("{") || inner.startsWith("[")) {
                if (!validJson(inner)) return true
                mask(tag.start, tag.end)
            }
        }
        val structuralText = masked.toString()
        for ((open, close) in structurePairs) {
            var cursor = 0
            while (true) {
                val first = open.find(structuralText, cursor) ?: break
                val end = close.find(structuralText, first.range.last + 1) ?: return true
                val nested = open.find(structuralText, first.range.last + 1)
                if (nested != null && nested.range.first < end.range.first) return true
                cursor = end.range.last + 1
            }
        }
        return false
    }
    private fun validJson(s: String): Boolean = try {
        val tokener = org.json.JSONTokener(s)
        val value = tokener.nextValue()
        (value is JSONObject || value is JSONArray) && tokener.nextClean() == '\u0000' 
    } catch (_: Exception) { false }

    fun prompt(partial: String): String {
        val tail = partial.takeLast(80)
        return "[CONTINUE] The previous tool call was cut off and not executed. Your response MUST begin by copying the exact prefix below, character for character, then immediately continue and complete the tool call. Do not include explanations or labels in your response.\nEXACT PREFIX:\n$tail\nEND OF PREFIX\nRepeat that exact prefix first, then finish the tool call."
    }

    /** Accept only a sufficiently long, unique, byte-for-byte overlap. If it is
     * absent or ambiguous return null rather than risking corrupted file content. */
    fun stitch(partial: String, continuation: String): String? {
        val body = unwrap(continuation)
        val tail = partial.takeLast(4096)
        val max = minOf(tail.length, body.length)
        var candidates = 0
        for (length in max downTo 24) {
            val overlap = tail.takeLast(length)
            if (!body.startsWith(overlap)) continue
            candidates++
            if (candidates > 128) return null
            if (partial.indexOf(overlap) == partial.lastIndexOf(overlap)) {
                val result = partial + body.substring(length)
                if (result.length <= 500_000) return result
                return null
            }
        }
        return null
    }
    private fun unwrap(value: String): String {
        val match = Regex("^(`{3,}|~{3,})(?:tool|tool_call)?[ \\t]*\\r?\\n([\\s\\S]*?)\\r?\\n\\1[ \\t]*$").matchEntire(value)
        return match?.groupValues?.get(2) ?: value
    }
}
