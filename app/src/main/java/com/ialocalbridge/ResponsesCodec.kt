package com.ialocalbridge

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Translation between the Responses API and the text/tool chat pipeline. */
object ResponsesCodec {
    private fun id(prefix: String) = prefix + "_" + UUID.randomUUID().toString().replace("-", "").take(24)

    private fun text(value: Any?): String = when (value) {
        null, JSONObject.NULL -> ""
        is String -> value
        is JSONArray -> (0 until value.length()).map { i ->
            when (val p = value.get(i)) {
                is String -> p
                is JSONObject -> when (p.optString("type", "input_text")) {
                    "input_text", "output_text", "text", "summary_text" -> p.optString("text")
                    else -> throw AutomationException("unsupported_content", "Ce type de contenu Responses n’est pas pris en charge sur Android.")
                }
                else -> throw AutomationException("unsupported_content", "Contenu Responses invalide.")
            }
        }.filter { it.isNotEmpty() }.joinToString("\n")
        else -> throw AutomationException("unsupported_content", "Contenu Responses invalide.")
    }

    fun toChat(body: JSONObject): JSONObject {
        if (body.has("previous_response_id") && !body.isNull("previous_response_id"))
            throw AutomationException("unsupported_parameter", "previous_response_id n’est pas encore pris en charge ; renvoie l’historique dans input.")
        val messages = JSONArray()
        val instructions = text(body.opt("instructions"))
        if (instructions.isNotBlank()) messages.put(JSONObject().put("role", "system").put("content", instructions))
        val input = body.opt("input")
        val items = when (input) {
            is String -> JSONArray().put(JSONObject().put("role", "user").put("content", input))
            is JSONObject -> JSONArray().put(input)
            is JSONArray -> input
            else -> throw AutomationException("invalid_request", "Le champ input est requis.")
        }
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: throw AutomationException("invalid_request", "Chaque élément input doit être un objet.")
            when (item.optString("type", "message")) {
                "function_call" -> {
                    val callId = item.optString("call_id").ifBlank { item.optString("id").ifBlank { id("call") } }
                    val rawArgs = item.opt("arguments")
                    val args = if (rawArgs is String) rawArgs else (rawArgs ?: JSONObject()).toString()
                    val call = JSONObject().put("id", callId).put("type", "function")
                        .put("function", JSONObject().put("name", item.optString("name")).put("arguments", args))
                    messages.put(JSONObject().put("role", "assistant").put("content", "").put("tool_calls", JSONArray().put(call)))
                }
                "function_call_output" -> messages.put(JSONObject().put("role", "tool")
                    .put("tool_call_id", item.optString("call_id")).put("content", text(item.opt("output"))))
                "message" -> {
                    val role = item.optString("role", "user").let { if (it == "developer") "system" else it }
                    val content = text(item.opt("content"))
                    if (content.isNotBlank()) messages.put(JSONObject().put("role", role).put("content", content))
                }
                else -> throw AutomationException("unsupported_content", "Type input Responses non pris en charge : ${item.optString("type")}")
            }
        }
        val chat = JSONObject().put("model", body.optString("model")).put("messages", messages).put("stream", body.optBoolean("stream", false))
        body.optJSONArray("tools")?.let { tools ->
            val mapped = JSONArray()
            for (i in 0 until tools.length()) {
                val tool = tools.optJSONObject(i) ?: throw AutomationException("invalid_request", "Définition d’outil invalide.")
                if (tool.optString("type", "function") != "function") throw AutomationException("unsupported_tool", "Seuls les outils function sont pris en charge.")
                if (tool.optJSONObject("function") != null) mapped.put(tool)
                else {
                    val fn = JSONObject().put("name", tool.optString("name"))
                        .put("description", tool.optString("description"))
                        .put("parameters", tool.optJSONObject("parameters") ?: JSONObject().put("type", "object"))
                    mapped.put(JSONObject().put("type", "function").put("function", fn))
                }
            }
            chat.put("tools", mapped)
        }
        if (body.has("tool_choice")) {
            val choice = body.opt("tool_choice")
            if (choice is JSONObject && choice.optString("type") == "function" && choice.optJSONObject("function") == null)
                chat.put("tool_choice", JSONObject().put("type", "function").put("function", JSONObject().put("name", choice.optString("name"))))
            else chat.put("tool_choice", choice)
        }
        return chat
    }

    fun output(responseId: String, model: String, text: String, calls: List<ToolParserCall>, promptTokens: Int, completionTokens: Int): JSONObject {
        val output = JSONArray()
        if (text.isNotEmpty()) output.put(JSONObject().put("type", "message").put("id", id("msg"))
            .put("status", "completed").put("role", "assistant")
            .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", text).put("annotations", JSONArray()))))
        for (call in calls) output.put(JSONObject().put("type", "function_call").put("id", id("fc"))
            .put("call_id", call.id).put("name", call.name).put("arguments", call.arguments).put("status", "completed"))
        return JSONObject().put("id", responseId).put("object", "response").put("created_at", System.currentTimeMillis()/1000)
            .put("status", "completed").put("model", model).put("output", output).put("parallel_tool_calls", true)
            .put("tool_choice", "auto").put("tools", JSONArray()).put("error", JSONObject.NULL)
            .put("incomplete_details", JSONObject.NULL).put("instructions", JSONObject.NULL).put("metadata", JSONObject())
            .put("temperature", 1.0).put("top_p", 1.0).put("max_output_tokens", JSONObject.NULL)
            .put("previous_response_id", JSONObject.NULL).put("reasoning", JSONObject().put("effort", JSONObject.NULL).put("summary", JSONObject.NULL))
            .put("store", false).put("truncation", "disabled")
            .put("usage", JSONObject().put("input_tokens", promptTokens).put("input_tokens_details", JSONObject().put("cached_tokens", 0))
                .put("output_tokens", completionTokens).put("output_tokens_details", JSONObject().put("reasoning_tokens", 0))
                .put("total_tokens", promptTokens + completionTokens))
    }
    data class ToolParserCall(val id: String, val name: String, val arguments: String)
    fun newResponseId(): String = id("resp")
}
