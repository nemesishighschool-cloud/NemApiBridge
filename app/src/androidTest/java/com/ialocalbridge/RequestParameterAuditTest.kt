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
    @Test fun rejectsUnsupportedChoiceCountStopAndProbabilities() {
        for (body in listOf("""{"n":2}""", """{"stop":["END"]}""", """{"stop":"END"}""", """{"logprobs":true}""", """{"top_logprobs":1}""")) {
            try { RequestParameterAudit.validate(JSONObject(body)); fail("accepted $body") }
            catch (e: AutomationException) { assertEquals("unsupported_parameter", e.errorCode) }
        }
        for (body in listOf("""{"n":0}""", """{"n":"two"}""", """{"stop":7}""", """{"logprobs":"true"}""")) {
            try { RequestParameterAudit.validate(JSONObject(body)); fail("accepted $body") }
            catch (e: AutomationException) { assertEquals("invalid_request", e.errorCode) }
        }
        RequestParameterAudit.validate(JSONObject("""{"n":1,"stop":[],"logprobs":false,"top_logprobs":0}"""))
    }
    @Test fun responsesDoNotClaimUnknownSampling() {
        val result = ResponsesCodec.output("id", "DeepSeek", "ok", emptyList(), 2, 3)
        assertTrue(result.isNull("temperature")); assertTrue(result.isNull("top_p")); assertTrue(result.isNull("max_output_tokens"))
    }
}
