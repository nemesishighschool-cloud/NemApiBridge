package com.ialocalbridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ialocalbridge.utils.ToolParser
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ToolParserIntegrationTest {
    @Test fun parsesFencedToolCallWithoutDamagingArguments() {
        val arg = JSONObject().put("command", "printf '%s' \"\$HOME\" && grep -E '\\d+' file").toString()
        val raw = "First\n```tool\n<tool>{\"name\":\"run\",\"arguments\":$arg}</tool>\n```\nAfter"
        val parsed = ToolParser.parseToolCalls(raw, setOf("run"))!!
        assertEquals("First\n\nAfter", parsed.remainingText)
        assertEquals("run", parsed.toolCalls.single().name)
        assertEquals(JSONObject(arg).getString("command"), JSONObject(parsed.toolCalls.single().arguments).getString("command"))
    }
    @Test fun parsesMultipleCallsWithDistinctIds() {
        val raw = "<tool>{\"name\":\"read\",\"arguments\":{\"path\":\"a\"}}</tool>\n<tool>{\"name\":\"read\",\"arguments\":{\"path\":\"b\"}}</tool>"
        val calls = ToolParser.parseToolCalls(raw, setOf("read"))!!.toolCalls
        assertEquals(2, calls.size)
        assertNotEquals(calls[0].id, calls[1].id)
        assertEquals("b", JSONObject(calls[1].arguments).getString("path"))
    }
    @Test fun parsesDeepseekDsmlValues() {
        val raw = "Intro\n<｜DSML｜tool_calls><｜DSML｜invoke name=\"read\"><｜DSML｜parameter name=\"path\" string=\"true\">a.py</｜DSML｜parameter><｜DSML｜parameter name=\"limit\" string=\"false\">50</｜DSML｜parameter></｜DSML｜invoke></｜DSML｜tool_calls>"
        val parsed = ToolParser.parseToolCalls(raw, setOf("read"))!!
        assertEquals("Intro", parsed.remainingText)
        assertEquals("a.py", JSONObject(parsed.toolCalls.single().arguments).getString("path"))
        assertEquals(50, JSONObject(parsed.toolCalls.single().arguments).getInt("limit"))
    }
    @Test fun refusesUnknownOrMalformedAndLeavesNormalCodeUntouched() {
        assertNull(ToolParser.parseToolCalls("<tool>{\"name\":\"delete\",\"arguments\":{}}</tool>", setOf("read")))
        assertNull(ToolParser.parseToolCalls("<tool>{\"name\":\"read\",\"arguments\":[]}</tool>", setOf("read")))
        assertNull(ToolParser.parseToolCalls("```html\n<tool>{\"name\":\"read\",\"arguments\":{}}</tool>\n```", setOf("read")))
    }
    @Test fun parsesJsonInsideToolFenceFromRealDeepSeek() {
        val raw = "```tool\n{\"name\":\"read_file\",\"arguments\":{\"file_path\":\"main.py\"}}\n```"
        val parsed = ToolParser.parseToolCalls(raw, setOf("read_file"))!!
        assertEquals("", parsed.remainingText)
        assertEquals("main.py", JSONObject(parsed.toolCalls.single().arguments).getString("file_path"))
    }
    @Test fun parsesQwenAnthropicAndJsonFences() {
        val qwen = "<tool_call><function=write_file><parameter=file_path>src/a.py</parameter><parameter=content>print(1)</parameter></function></tool_call>"
        val a = ToolParser.parseToolCalls(qwen, setOf("write_file"))!!
        assertEquals("", a.remainingText)
        assertEquals("src/a.py", JSONObject(a.toolCalls.single().arguments).getString("file_path"))
        val anthropic = "<invoke name=\"read_file\"><parameter name=\"file_path\">a</parameter></invoke>"
        assertEquals("a", JSONObject(ToolParser.parseToolCalls(anthropic, setOf("read_file"))!!.toolCalls.single().arguments).getString("file_path"))
        val json = "```json\n{\"name\":\"read_file\",\"arguments\":{\"file_path\":\"a\"}}\n```"
        assertEquals("", ToolParser.parseToolCalls(json, setOf("read_file"))!!.remainingText)
        assertNull(ToolParser.parseToolCalls(json, setOf("different")))
    }
    @Test fun parsesMultipleDeepseekV3Calls() {
        val raw = "<｜tool▁calls▁begin｜><｜tool▁call▁begin｜>function<｜tool▁sep｜>read_file\n{\"file_path\":\"a.py\"}<｜tool▁call▁end｜><｜tool▁call▁begin｜>function<｜tool▁sep｜>run\n{\"command\":\"ls\"}<｜tool▁call▁end｜><｜tool▁calls▁end｜>"
        val parsed = ToolParser.parseToolCalls(raw, setOf("read_file", "run"))!!
        assertEquals("", parsed.remainingText)
        assertEquals(listOf("read_file", "run"), parsed.toolCalls.map { it.name })
        assertEquals("ls", JSONObject(parsed.toolCalls[1].arguments).getString("command"))
    }
    @Test fun parsesLegacyProtocol() {
        val raw = "<|tool_calls_begin|>[{\"name\":\"read\",\"arguments\":{\"path\":\"a\"}}]<|tool_calls_end|>"
        assertEquals("read", ToolParser.parseToolCalls(raw, setOf("read"))!!.toolCalls.single().name)
    }
    @Test fun literalToolTagsInsideJsonStringsDoNotTruncateOrExecutePartialFiles() {
        val content = "<tool>literal</tool>\nfunction x() { return \"quoted\\\\path\"; }"
        val call = JSONObject().put("name", "write_file").put("arguments", JSONObject().put("content", content)).toString()
        val raw = "```tool\n<tool>$call</tool>\n```"
        val parsed = ToolParser.parseToolCalls(raw, setOf("write_file"))!!
        assertEquals(content, JSONObject(parsed.toolCalls.single().arguments).getString("content"))
        assertFalse(ToolContinuation.isIncomplete(raw))
        assertNull(ToolParser.parseToolCalls("<tool>$call unexpected</tool>", setOf("write_file")))
        assertTrue(ToolContinuation.isIncomplete("<tool>$call unexpected</tool>"))
    }
}
