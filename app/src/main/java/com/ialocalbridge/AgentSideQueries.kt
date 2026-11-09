package com.ialocalbridge

import com.ialocalbridge.utils.ToolParser
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Narrow, opt-in handling of known agent housekeeping without touching the provider chat.
 * Never invents a safety decision or pretends to answer an arbitrary structured task. */
object AgentSideQueries {
    data class Answer(val text: String = "", val calls: ToolParser.ToolCallResult? = null, val error: String? = null)
    private val safety = Regex("block|deny|denied|reject|unsafe|danger|risk|malicious|harm|forbid|approv|allow|permit|authoriz", RegexOption.IGNORE_CASE)
    private val memoryMarkers = listOf("You are now acting as the managed memory extraction subagent", "You are performing a managed memory dream", "You are consolidating durable, cross-project User Memory", "You are reviewing this conversation to extract reusable skills")

    fun classify(messages: JSONArray, tools: JSONArray?, choice: Any?, metadata: JSONObject?): Answer? {
        val system = mutableListOf<String>()
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            if (m.optString("role").lowercase() !in setOf("system", "developer")) break
            system.add(m.optString("content"))
        }
        val last = (messages.length() - 1 downTo 0).mapNotNull { messages.optJSONObject(it) }
            .firstOrNull { it.optString("role").equals("user", true) }?.optString("content").orEmpty()
        val texts = listOf(system.joinToString("\n"), last).map { it.trimStart() }
        if (choice != "required" && choice != "any" && choice !is JSONObject) {
            if (texts.any { it.startsWith("[SUGGESTION MODE:", true) }) return Answer()
            if (texts.any { text -> memoryMarkers.any { text.startsWith(it, true) } })
                return Answer(text = "No durable information to save; no file changes.")
            val pid = metadata?.optString("promptId").orEmpty()
            if (pid == "prompt_suggestion" || pid == "speculation") return Answer()
        }
        if (choice == "none" || tools == null || tools.length() != 1) return null
        val item = tools.optJSONObject(0) ?: return null
        val fn = item.optJSONObject("function") ?: item
        val name = fn.optString("name")
        if (name != "respond_in_schema") return null
        if (choice is JSONObject) {
            val target = choice.optJSONObject("function")?.optString("name") ?: choice.optString("name")
            if (target.isNotBlank() && target != name) return null
        }
        val schema = fn.optJSONObject("parameters") ?: return Answer(error = "Le schéma annexe est absent ; aucune réponse locale n’a été inventée.")
        if (containsSafety(schema)) return Answer(error = "Ce classifieur de sécurité exige une approbation manuelle ; aucune décision automatique n’a été fabriquée.")
        // Only the known memory-recall schema may receive an empty selection.
        // A general respond_in_schema request may be the actual task.
        val props = schema.optJSONObject("properties") ?: return null
        val required = schema.optJSONArray("required") ?: return null
        if (props.length() != 1 || !props.has("selected_memories") ||
            required.length() != 1 || required.optString(0) != "selected_memories" ||
            props.optJSONObject("selected_memories")?.optString("type") != "array") return null
        val child = props.getJSONObject("selected_memories")
        val annotations = setOf("title", "description", "default", "examples", "deprecated", "readOnly", "writeOnly", "$" + "schema", "$" + "id", "$" + "comment")
        val rootAllowed = annotations + setOf("type", "properties", "required", "additionalProperties", "minProperties", "maxProperties")
        val childAllowed = annotations + setOf("type", "items", "minItems", "maxItems", "uniqueItems")
        fun invalidBound(obj: JSONObject, key: String, predicate: (Double) -> Boolean): Boolean {
            if (!obj.has(key)) return false
            val number = obj.opt(key) as? Number ?: return true
            return !predicate(number.toDouble())
        }
        val supported = schema.optString("type") == "object" &&
            schema.keys().asSequence().all { it in rootAllowed } && child.keys().asSequence().all { it in childAllowed } &&
            !invalidBound(schema, "minProperties") { it <= 1.0 && it >= 0.0 } &&
            !invalidBound(schema, "maxProperties") { it >= 1.0 } &&
            !invalidBound(child, "minItems") { it == 0.0 } &&
            !invalidBound(child, "maxItems") { it >= 0.0 }
        if (!supported) return Answer(error = "Schéma mémoire annexe non pris en charge ; aucune réponse locale n’a été inventée.")
        val value = JSONObject().put("selected_memories", JSONArray())
        val call = ToolParser.ParsedToolCall("call_" + UUID.randomUUID().toString().replace("-", ""), name, value.toString())
        return Answer(calls = ToolParser.ToolCallResult(listOf(call), ""))
    }

    private fun containsSafety(schema: JSONObject, depth: Int = 0): Boolean {
        if (depth > 12) return true
        val properties = schema.optJSONObject("properties")
        if (properties != null) {
            val keys = properties.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (safety.containsMatchIn(key)) return true
                properties.optJSONObject(key)?.let { if (containsSafety(it, depth + 1)) return true }
            }
        }
        for (name in listOf("items", "not", "if", "then", "else")) schema.optJSONObject(name)?.let { if (containsSafety(it, depth + 1)) return true }
        for (name in listOf("anyOf", "oneOf", "allOf")) schema.optJSONArray(name)?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { if (containsSafety(it, depth + 1)) return true }
        }
        return false
    }

}
