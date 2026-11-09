package com.ialocalbridge

import org.json.JSONArray
import org.json.JSONObject
import com.ialocalbridge.utils.ToolTagScanner

/** Conservative continuation of incomplete tool output. Never repair invalid JSON silently. */
object ToolContinuation {
    private val openTool = Regex("<tool(?:_call)?>", RegexOption.IGNORE_CASE)
    private val closeTool = Regex("</tool(?:_call)?>", RegexOption.IGNORE_CASE)
    private val toolFence = Regex("(?m)^[ \\t]*(`{3,}|~{3,})[ \\t]*(?:tool|tool_call)[ \\t]*\\r?\\n", RegexOption.IGNORE_CASE)
    private val dsmlParameterOpen = Regex("<\\s*[|｜]{1,2}\\s*DSML\\s*[|｜]{1,2}\\s*parameter\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val dsmlParameterClose = Regex("<\\s*/\\s*[|｜]{1,2}\\s*DSML\\s*[|｜]{1,2}\\s*parameter\\s*>", RegexOption.IGNORE_CASE)

    fun isIncomplete(text: String): Boolean {
        val tags = ToolTagScanner.scan(text)
        if (tags.incompleteStart != null) return true
        for (tag in tags.tags) {
            val inner = tag.body.trim()
            if ((inner.startsWith("{") || inner.startsWith("[")) && !validJson(inner)) return true
        }
        val dsml = dsmlParameterOpen.findAll(text).lastOrNull()
        if (dsml != null && dsmlParameterClose.find(text, dsml.range.last + 1) == null) return true
        val fence = toolFence.findAll(text).lastOrNull() ?: return false
        val body = text.substring(fence.range.last + 1)
        val delimiter = fence.groupValues[1]
        val end = Regex("(?m)^[ \\t]*" + Regex.escape(delimiter) + "[ \\t]*(?:\\r?$)").find(body)
        val inner = (if (end == null) body else body.substring(0, end.range.first)).trim()
        if (inner.startsWith("{") || inner.startsWith("[")) return !validJson(inner)
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
