package com.ialocalbridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResponsesCodecIntegrationTest {
    @Test fun mapsTextAndFlatFunctionTools() {
        val body = JSONObject("""{"model":"DeepSeek","instructions":"sys","input":[{"role":"developer","content":[{"type":"input_text","text":"dev"}]},{"role":"user","content":[{"type":"input_text","text":"hello"}]}],"tools":[{"type":"function","name":"read_file","parameters":{"type":"object"}}],"tool_choice":{"type":"function","name":"read_file"},"stream":true}""")
        val chat = ResponsesCodec.toChat(body)
        val messages = chat.getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("system", messages.getJSONObject(1).getString("role"))
        assertEquals("hello", messages.getJSONObject(2).getString("content"))
        assertEquals("read_file", chat.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name"))
        assertEquals("read_file", chat.getJSONObject("tool_choice").getJSONObject("function").getString("name"))
        assertTrue(chat.getBoolean("stream"))
    }
    @Test fun preservesFunctionIdsAndOutput() {
        val body = JSONObject("""{"input":[{"role":"user","content":"go"},{"type":"function_call","call_id":"c1","name":"ls","arguments":"{\"p\":1}"},{"type":"function_call_output","call_id":"c1","output":"a.txt"}]}""")
        val messages = ResponsesCodec.toChat(body).getJSONArray("messages")
        assertEquals("c1", messages.getJSONObject(1).getJSONArray("tool_calls").getJSONObject(0).getString("id"))
        assertEquals("c1", messages.getJSONObject(2).getString("tool_call_id"))
        val result = ResponsesCodec.output("resp_1", "DeepSeek", "ok", listOf(ResponsesCodec.ToolParserCall("c1","ls","{\"p\":1}")), 2, 3)
        assertEquals("completed", result.getString("status"))
        assertEquals("ok", result.getJSONArray("output").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text"))
        assertEquals("c1", result.getJSONArray("output").getJSONObject(1).getString("call_id"))
        assertEquals(5, result.getJSONObject("usage").getInt("total_tokens"))
    }
    @Test fun rejectsUnsupportedStateAndImageFileIdsInsteadOfLosingData() {
        try { ResponsesCodec.toChat(JSONObject("""{"input":"hi","previous_response_id":"resp_old"}""")); fail("previous_response_id silently ignored") }
        catch (e: AutomationException) { assertEquals("unsupported_parameter", e.errorCode) }
        try { ResponsesCodec.toChat(JSONObject("""{"input":[{"role":"user","content":[{"type":"input_image","file_id":"file_1"}]}]}""")); fail("file_id silently ignored") }
        catch (e: AutomationException) { assertEquals("unsupported_content", e.errorCode) }
    }
}
