package com.ialocalbridge

import org.json.JSONObject

/** Settings sent by clients that the Android UI cannot enforce. */
object RequestParameterAudit {
    private val ignored = listOf(
        "max_tokens", "max_completion_tokens", "max_output_tokens", "temperature", "top_p",
        "frequency_penalty", "presence_penalty", "seed", "logit_bias", "reasoning_effort", "reasoning", "verbosity",
        "service_tier", "truncation", "max_tool_calls", "include"
    )
    /** Reject requests whose response shape or stopping behavior cannot be honored. */
    fun validate(body: JSONObject) {
        if (body.has("n") && !body.isNull("n")) {
            val n = body.opt("n")
            if (n !is Int || n < 1) throw AutomationException("invalid_request", "n doit être un entier positif.")
            if (n != 1) throw AutomationException("unsupported_parameter", "Plusieurs choix de réponse ne sont pas pris en charge.")
        }
        if (body.has("stop") && !body.isNull("stop")) {
            val stop = body.opt("stop")
            if (stop !is String && stop !is org.json.JSONArray) throw AutomationException("invalid_request", "stop doit être une chaîne ou un tableau.")
            if (stop is String || (stop is org.json.JSONArray && stop.length() > 0))
                throw AutomationException("unsupported_parameter", "Les séquences stop ne sont pas appliquées par le fournisseur Android.")
        }
        if (body.has("logprobs") && !body.isNull("logprobs")) {
            val logprobs = body.opt("logprobs")
            if (logprobs !is Boolean) throw AutomationException("invalid_request", "logprobs doit être un booléen.")
            if (logprobs) throw AutomationException("unsupported_parameter", "Les probabilités de tokens ne sont pas disponibles.")
        }
        if (body.has("top_logprobs") && !body.isNull("top_logprobs")) {
            val top = body.opt("top_logprobs")
            if (top !is Int || top < 0) throw AutomationException("invalid_request", "top_logprobs doit être un entier positif ou nul.")
            if (top > 0) throw AutomationException("unsupported_parameter", "Les probabilités de tokens ne sont pas disponibles.")
        }
    }

    fun ignoredFields(body: JSONObject): List<String> {
        val fields = ignored.filter { body.has(it) && !body.isNull(it) }.toMutableList()
        val text = body.optJSONObject("text")
        if (text != null && text.has("verbosity") && !text.isNull("verbosity")) fields.add("text.verbosity")
        return fields
    }
}
