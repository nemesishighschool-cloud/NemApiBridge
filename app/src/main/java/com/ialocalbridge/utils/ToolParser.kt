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
    private val jsonFence = Regex("(?m)^[ \\t]*(`{3,}|~{3,})[ \\t]*json[ \\t]*\\r?\\n([\\s\\S]*?)\\r?\\n[ \\t]*\\1[ \\t]*(?=\\r?$)", RegexOption.IGNORE_CASE)
    private val qwenFunc = Regex("<function=([^>\\s]+)\\s*>([\\s\\S]*?)</function\\s*>", RegexOption.IGNORE_CASE)
    private val qwenParam = Regex("<parameter=([^>\\s]+)\\s*>([\\s\\S]*?)</parameter\\s*>", RegexOption.IGNORE_CASE)
    private val invoke = Regex("<invoke\\s+name=[\"']([^\"']+)[\"'][^>]*>([\\s\\S]*?)</invoke\\s*>", RegexOption.IGNORE_CASE)
    private val xmlParam = Regex("<parameter\\s+name=[\"']([^\"']+)[\"']([^>]*)>([\\s\\S]*?)</parameter\\s*>", RegexOption.IGNORE_CASE)
    private val functionCall = Regex("<function[_-]?call>([\\s\\S]*?)</function[_-]?call>", RegexOption.IGNORE_CASE)
    private val v3Block = Regex("<\\s*[|｜]tool[▁_ ]calls[▁_ ]begin[|｜]\\s*>([\\s\\S]*?)<\\s*[|｜]tool[▁_ ]calls[▁_ ]end[|｜]\\s*>", RegexOption.IGNORE_CASE)
    private val v3Call = Regex("<\\s*[|｜]tool[▁_ ]call[▁_ ]begin[|｜]\\s*>\\s*(?:function)?\\s*<\\s*[|｜]tool[▁_ ]sep[|｜]\\s*>\\s*([A-Za-z0-9_.:-]+)\\s*([\\s\\S]*?)<\\s*[|｜]tool[▁_ ]call[▁_ ]end[|｜]\\s*>", RegexOption.IGNORE_CASE)
    private val stringFalse = Regex("\\bstring\\s*=\\s*[\"']false[\"']", RegexOption.IGNORE_CASE)

    /** Unknown names and malformed JSON remain visible instead of being executed or silently altered. */
    fun parseToolCalls(text: String, allowedNames: Set<String>? = null, generic: Boolean = false): ToolCallResult? {
        val candidates = mutableListOf<Span>()
        val outerToolTags = ToolTagScanner.scan(text).tags
        val ordinaryFences = Regex("(?m)^[ \\t]*(`{3,}|~{3,})[^\\r\\n]*\\r?\\n[\\s\\S]*?\\r?\\n[ \\t]*\\1[ \\t]*(?=\\r?$)")
            .findAll(text).filter { !it.value.substringBefore('\n').trim().matches(Regex("(?:`{3,}|~{3,})[ \\t]*(?:tool|tool_call)[ \\t]*", RegexOption.IGNORE_CASE)) }.map { it.range }.toList()
        val toolFences = toolFence.findAll(text).toList()
        fun add(m: MatchResult, calls: List<ParsedToolCall>, acceptJsonFence: Boolean = false) {
            if (!acceptJsonFence && ordinaryFences.any { m.range.first in it }) return
            if (outerToolTags.any { it.start < m.range.first && it.end >= m.range.last + 1 }) return
            if (toolFences.any { it.range.first < m.range.first && it.range.last >= m.range.last }) return
            if (calls.isNotEmpty() && calls.all { allowedNames == null || it.name in allowedNames }) {
                candidates.add(Span(m.range.first, m.range.last + 1, calls))
            }
        }
        fun tagCalls(body: String): List<ParsedToolCall> {
            val nested = qwenFunc.findAll(body).toList()
            if (nested.isNotEmpty() && qwenFunc.replace(body, "").trim().isEmpty()) {
                val parsed = nested.mapNotNull(::parseQwen)
                return if (parsed.size == nested.size) parsed else emptyList()
            }
            return jsonCalls(body)
        }
        // Never execute a valid subset if another explicit tool tag is invalid.
        for (tag in outerToolTags) {
            if (ordinaryFences.any { tag.start in it }) continue
            val calls = tagCalls(tag.body)
            if (calls.isEmpty() || calls.any { allowedNames != null && it.name !in allowedNames }) return null
        }
        for (m in toolFences) {
            val body = m.groupValues[2]
            val scan = ToolTagScanner.scan(body)
            val blocks = scan.tags
            val residual = StringBuilder().apply {
                var cursor = 0
                blocks.forEach { append(body, cursor, it.start); cursor = it.end }
                append(body, cursor, body.length)
            }.toString().trim()
            if (scan.incompleteStart == null && blocks.isNotEmpty() && residual.isEmpty()) {
                val grouped = blocks.map { tagCalls(it.body) }
                if (grouped.all { it.isNotEmpty() }) add(m, grouped.flatten())
            } else if (scan.incompleteStart == null && blocks.isEmpty()) {
                val qwen = qwenFunc.findAll(body).toList()
                val parsed = qwen.mapNotNull(::parseQwen)
                if (qwen.isNotEmpty() && parsed.size == qwen.size && qwenFunc.replace(body, "").trim().isEmpty()) add(m, parsed)
                else add(m, jsonCalls(body))
            }
        }
        // JSON fences are ambiguous: accept only when the request declared the name.
        if (allowedNames != null) for (m in jsonFence.findAll(text)) add(m, jsonCalls(m.groupValues[2]), true)
        else if (generic) for (m in jsonFence.findAll(text)) add(m, genericCalls(m.groupValues[2]), true)
        for (m in qwenFunc.findAll(text)) parseQwen(m)?.let { add(m, listOf(it)) }
        for (m in invoke.findAll(text)) parseInvoke(m)?.let { add(m, listOf(it)) }
        for (m in functionCall.findAll(text)) add(m, jsonCalls(m.groupValues[1]))
        for (m in v3Block.findAll(text)) {
            val parts = v3Call.findAll(m.value).toList()
            val calls = parts.mapNotNull(::parseV3)
            if (parts.isNotEmpty() && parts.size == calls.size) add(m, calls)
        }
        for (m in v3Call.findAll(text)) parseV3(m)?.let { add(m, listOf(it)) }
        for (tag in outerToolTags) {
            if (toolFences.any { tag.start in it.range }) continue
            val calls = tagCalls(tag.body)
            if (ordinaryFences.none { tag.start in it } && calls.isNotEmpty() && calls.all { allowedNames == null || it.name in allowedNames })
                candidates.add(Span(tag.start, tag.end, calls))
        }
        for (m in legacy.findAll(text)) add(m, jsonCalls(m.groupValues[1]))
        for (m in dsmlBlock.findAll(text)) {
            val invocations = dsmlInvoke.findAll(m.groupValues[1]).toList()
            val calls = invocations.mapNotNull(::parseDsml)
            if (invocations.isNotEmpty() && calls.size == invocations.size && dsmlInvoke.replace(m.groupValues[1], "").trim().isEmpty()) add(m, calls)
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

    private fun makeCall(name: String, args: JSONObject) = ParsedToolCall(
        "call_" + UUID.randomUUID().toString().replace("-", ""), name, args.toString())

    private fun parseQwen(m: MatchResult): ParsedToolCall? = try {
        val body = m.groupValues[2]
        require(qwenParam.replace(body, "").trim().isEmpty())
        val args = JSONObject()
        for (param in qwenParam.findAll(body)) {
            require(!args.has(param.groupValues[1]))
            args.put(param.groupValues[1], xmlValue(param.groupValues[2]))
        }
        makeCall(m.groupValues[1], args)
    } catch (_: Exception) { null }

    private fun parseInvoke(m: MatchResult): ParsedToolCall? = try {
        val body = m.groupValues[2]
        require(xmlParam.replace(body, "").trim().isEmpty())
        val args = JSONObject()
        for (param in xmlParam.findAll(body)) {
            require(!args.has(param.groupValues[1]))
            val value = if (stringFalse.containsMatchIn(param.groupValues[2])) xmlValue(param.groupValues[3]) else param.groupValues[3]
            args.put(param.groupValues[1], value)
        }
        makeCall(m.groupValues[1], args)
    } catch (_: Exception) { null }

    private fun xmlValue(raw: String): Any {
        val trimmed = raw.trim()
        if (trimmed == "true") return true
        if (trimmed == "false") return false
        if (trimmed == "null") return JSONObject.NULL
        if (trimmed.matches(Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")) || trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                val tokener = org.json.JSONTokener(trimmed)
                val value = tokener.nextValue()
                if (tokener.nextClean() == '\u0000') return value
            } catch (_: Exception) { }
        }
        return raw
    }

    private fun parseV3(m: MatchResult): ParsedToolCall? = try {
        makeCall(m.groupValues[1], JSONObject(m.groupValues[2].trim()))
    } catch (_: Exception) { null }

    private fun jsonCalls(raw: String): List<ParsedToolCall> {
      return try {
        val tokener = org.json.JSONTokener(raw.trim())
        val value = tokener.nextValue()
        require(tokener.nextClean() == '\u0000')
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

    private fun genericCalls(text: String): List<ParsedToolCall> = try {
        val value = org.json.JSONTokener(text.trim()).nextValue()
        val items = when (value) {
            is JSONObject -> listOf(value)
            is JSONArray -> (0 until value.length()).map { value.getJSONObject(it) }
            else -> throw IllegalArgumentException("Invalid JSON")
        }
        items.map { item ->
            val markedName = listOf("tool", "action", "tool_name").firstNotNullOfOrNull { key -> (item.opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() } }
            val name = markedName ?: (item.opt("name") as? String)?.trim().orEmpty()
            require(Regex("^[A-Za-z_][A-Za-z0-9_.-]{0,48}$").matches(name))
            val argsKey = listOf("parameters", "arguments", "params", "args", "input").firstOrNull(item::has)
            require(argsKey != null || markedName != null)
            val args = if (argsKey == null) JSONObject() else item.optJSONObject(argsKey) ?: throw IllegalArgumentException("Arguments must be an object")
            makeCall(name, args)
        }
    } catch (_: Exception) { emptyList() }

    private fun parseDsml(m: MatchResult): ParsedToolCall? = try {
        val args = JSONObject()
        val body = m.groupValues[2]
        require(dsmlParameter.replace(body, "").trim().isEmpty())
        for (p in dsmlParameter.findAll(body)) {
            require(!args.has(p.groupValues[1]))
            val value = p.groupValues[3]
            if (stringFalse.containsMatchIn(p.groupValues[2])) {
                val tokener = org.json.JSONTokener(value.trim())
                val parsed = tokener.nextValue()
                require(tokener.nextClean() == '\u0000')
                args.put(p.groupValues[1], parsed)
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
