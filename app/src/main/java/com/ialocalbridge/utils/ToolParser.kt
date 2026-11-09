package com.ialocalbridge.utils

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Strict, lossless subset of the NemApi tool-call contract. */
object ToolParser {
    data class ParsedToolCall(val id: String, val name: String, val arguments: String)
    data class ToolCallResult(val toolCalls: List<ParsedToolCall>, val remainingText: String)
    private data class Span(val start: Int, val end: Int, val calls: List<ParsedToolCall>)

    private val toolBlock = Regex("<tool(?:_call)?>([\\s\\S]*?)</tool(?:_call)?>", RegexOption.IGNORE_CASE)
    private val toolFence = Regex("(?m)^[ \\t]*(`{3,}|~{3,})[ \\t]*(?:tool|tool_call)[ \\t]*\\r?\\n([\\s\\S]*?)\\r?\\n[ \\t]*\\1[ \\t]*(?=\\r?$)", RegexOption.IGNORE_CASE)
    private val legacy = Regex("<\\|tool_calls_begin\\|>([\\s\\S]*?)<\\|tool_calls_end\\|>", RegexOption.IGNORE_CASE)
    private val dsmlBlock = Regex("<\\s*[|｜]{1,2}\\s*DSML\\s*[|｜]{1,2}\\s*(?:tool_calls|function_calls|calls)\\s*>([\\s\\S]*?)<\\s*/\\s*[|｜]{1,2}\\s*DSML\\s*[|｜]{1,2}\\s*(?:tool_calls|function_calls|calls)\\s*>", RegexOption.IGNORE_CASE)
    private val dsmlInvoke = Regex("<\\s*[|｜]{1,2}\\s*DSML\\s*[|｜]{1,2}\\s*invoke\\s+name\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>([\\s\\S]*?)<\\s*/\\s*[|｜]{1,2}\\s*DSML\\s*[|｜]{1,2}\\s*invoke\\s*>", RegexOption.IGNORE_CASE)
    private val dsmlParameter = Regex("<\\s*[|｜]{1,2}\\s*DSML\\s*[|｜]{1,2}\\s*parameter\\s+name\\s*=\\s*[\"']([^\"']+)[\"']([^>]*)>([\\s\\S]*?)<\\s*/\\s*[|｜]{1,2}\\s*DSML\\s*[|｜]{1,2}\\s*parameter\\s*>", RegexOption.IGNORE_CASE)
    private val stringFalse = Regex("\\bstring\\s*=\\s*[\"']false[\"']", RegexOption.IGNORE_CASE)

    /** Unknown names and malformed JSON remain visible instead of being executed or silently altered. */
    fun parseToolCalls(text: String, allowedNames: Set<String>? = null): ToolCallResult? {
        val candidates = mutableListOf<Span>()
        val ordinaryFences = Regex("(?m)^[ \\t]*(`{3,}|~{3,})[^\\r\\n]*\\r?\\n[\\s\\S]*?\\r?\\n[ \\t]*\\1[ \\t]*(?=\\r?$)")
            .findAll(text).filter { !it.value.substringBefore('\n').trim().matches(Regex("(?:`{3,}|~{3,})[ \\t]*(?:tool|tool_call)[ \\t]*", RegexOption.IGNORE_CASE)) }.map { it.range }.toList()
        fun add(m: MatchResult, calls: List<ParsedToolCall>) {
            if (ordinaryFences.any { m.range.first in it }) return
            if (calls.isNotEmpty() && calls.all { allowedNames == null || it.name in allowedNames }) {
                candidates.add(Span(m.range.first, m.range.last + 1, calls))
            }
        }
        for (m in toolFence.findAll(text)) {
            val body = m.groupValues[2]
            val blocks = toolBlock.findAll(body).toList()
            // Do not remove unrelated content from a tool fence.
            val residual = toolBlock.replace(body, "").trim()
            if (blocks.isNotEmpty() && residual.isEmpty()) {
                val calls = blocks.flatMap { jsonCalls(it.groupValues[1]) }
                if (calls.size == blocks.size) add(m, calls)
            } else if (blocks.isEmpty()) {
                // Some models emit JSON directly inside a dedicated tool fence.
                add(m, jsonCalls(body))
            }
        }
        for (m in toolBlock.findAll(text)) add(m, jsonCalls(m.groupValues[1]))
        for (m in legacy.findAll(text)) add(m, jsonCalls(m.groupValues[1]))
        for (m in dsmlBlock.findAll(text)) {
            val invocations = dsmlInvoke.findAll(m.groupValues[1]).toList()
            val calls = invocations.mapNotNull(::parseDsml)
            if (invocations.isNotEmpty() && calls.size == invocations.size) add(m, calls)
        }
        val spans = candidates.sortedWith(compareBy<Span> { it.start }.thenByDescending { it.end })
        if (spans.isEmpty()) return null
        val result = StringBuilder()
        val calls = mutableListOf<ParsedToolCall>()
        var cursor = 0
        for (span in spans) {
            if (span.start < cursor) continue
            result.append(text, cursor, span.start)
            calls.addAll(span.calls)
            cursor = span.end
        }
        result.append(text, cursor, text.length)
        return ToolCallResult(calls, result.toString().trim())
    }

    private fun jsonCalls(raw: String): List<ParsedToolCall> {
      return try {
        val value = org.json.JSONTokener(raw.trim()).nextValue()
        val objects = when (value) {
            is JSONObject -> listOf(value)
            is JSONArray -> (0 until value.length()).map { value.getJSONObject(it) }
            else -> return emptyList()
        }
        objects.map { obj ->
            val fn = obj.optJSONObject("function") ?: obj
            val name = fn.optString("name").trim()
            require(name.isNotEmpty())
            val valueArgs = fn.opt("arguments") ?: JSONObject()
            val args = when (valueArgs) {
                is JSONObject -> valueArgs
                is String -> JSONObject(valueArgs)
                else -> throw IllegalArgumentException("arguments must be an object")
            }
            ParsedToolCall("call_" + UUID.randomUUID().toString().replace("-", ""), name, args.toString())
        }
      } catch (_: Exception) { emptyList() }
    }

    private fun parseDsml(m: MatchResult): ParsedToolCall? = try {
        val args = JSONObject()
        for (p in dsmlParameter.findAll(m.groupValues[2])) {
            val value = p.groupValues[3]
            if (stringFalse.containsMatchIn(p.groupValues[2])) {
                args.put(p.groupValues[1], org.json.JSONTokener(value.trim()).nextValue())
            } else args.put(p.groupValues[1], value)
        }
        ParsedToolCall("call_" + UUID.randomUUID().toString().replace("-", ""), m.groupValues[1], args.toString())
    } catch (_: Exception) { null }

    fun buildToolCallPrompt(tools: JSONArray): String {
        val names = mutableSetOf<String>()
        val lines = mutableListOf<String>()
        for (i in 0 until tools.length()) {
            val tool = tools.getJSONObject(i)
            require(!tool.has("type") || tool.optString("type") == "function")
            val fn = tool.optJSONObject("function") ?: tool
            val name = fn.getString("name").trim()
            require(name.isNotEmpty() && names.add(name))
            lines.add(fn.toString())
        }
        return """You have access to the following tools. To call a tool, emit one fenced block per call, exactly as follows:
```tool
<tool>{"name":"tool_name","arguments":{"argument":"value"}}</tool>
```
The name must be one of the tools below; arguments must be a valid JSON object. Preserve exact strings, including backslashes. If no tool is needed, answer normally. Do not invent a tool result.
Available tools:
${lines.joinToString("\n")}"""
    }
}
