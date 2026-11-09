package com.ialocalbridge

import org.json.JSONObject

/** Settings sent by clients that the Android UI cannot enforce. */
object RequestParameterAudit {
    private val ignored = listOf(
        "max_tokens", "max_completion_tokens", "max_output_tokens", "temperature", "top_p",
        "frequency_penalty", "presence_penalty", "seed", "stop", "n", "logit_bias",
        "logprobs", "top_logprobs", "reasoning_effort", "reasoning", "verbosity",
        "service_tier", "truncation", "max_tool_calls", "include"
    )
    fun ignoredFields(body: JSONObject): List<String> {
        val fields = ignored.filter { body.has(it) && !body.isNull(it) }.toMutableList()
        val text = body.optJSONObject("text")
        if (text != null && text.has("verbosity") && !text.isNull("verbosity")) fields.add("text.verbosity")
        return fields
    }
}
