package com.ialocalbridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RequestParameterAuditTest {
    @Test fun onlyReportsPresentNonNullSettings() {
        val body = JSONObject("""{"model":"DeepSeek","max_tokens":100,"temperature":0,"top_p":null,"text":{"verbosity":"low"}}""")
        assertEquals(listOf("max_tokens", "temperature", "text.verbosity"), RequestParameterAudit.ignoredFields(body))
        assertTrue(RequestParameterAudit.ignoredFields(JSONObject("""{"model":"DeepSeek"}""")).isEmpty())
    }
    @Test fun responsesDoNotClaimUnknownSampling() {
        val result = ResponsesCodec.output("id", "DeepSeek", "ok", emptyList(), 2, 3)
        assertTrue(result.isNull("temperature")); assertTrue(result.isNull("top_p")); assertTrue(result.isNull("max_output_tokens"))
    }
}
