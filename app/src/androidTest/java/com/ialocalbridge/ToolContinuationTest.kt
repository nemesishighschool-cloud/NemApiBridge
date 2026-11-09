package com.ialocalbridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ToolContinuationTest {
    @Test fun detectsIncompleteToolAndDoesNotRepairJson() {
        assertTrue(ToolContinuation.isIncomplete("<tool>{\"name\":\"write_file\",\"arguments\":{\"content\":\"partial"))
        assertTrue(ToolContinuation.isIncomplete("```tool\n{\"name\":\"write_file\",\"arguments\":{\"content\":\"unfinished\n```"))
        assertFalse(ToolContinuation.isIncomplete("<tool>{\"name\":\"read_file\",\"arguments\":{\"path\":\"a.txt\"}}</tool>"))
        assertFalse(ToolContinuation.isIncomplete("An ordinary code sample."))
    }
    @Test fun preservesExactOverlapAndRefusesAmbiguity() {
        val tail="BEGIN_unique_chunk_1234567890_abcdefghijklmnopqrstuvwxyz"
        val prefix="<tool>{\"name\":\"write_file\",\"arguments\":{\"content\":\""+tail
        val continuation=tail+"_END\"}}</tool>"
        assertEquals(prefix+"_END\"}}</tool>", ToolContinuation.stitch(prefix,continuation))
        assertNull(ToolContinuation.stitch(prefix,"no overlap, cannot safely join"))
        val repeated="x".repeat(5000)
        assertNull(ToolContinuation.stitch(repeated,"x".repeat(4096)+"tail"))
    }
    @Test fun detectsTruncatedXmlAndIgnoresLiteralTagsInJson() {
        assertTrue(ToolContinuation.isIncomplete("<function=write><parameter=content>partial"))
        assertTrue(ToolContinuation.isIncomplete("<invoke name=\"write\"><parameter name=\"content\">partial"))
        assertTrue(ToolContinuation.isIncomplete("<｜DSML｜tool_calls><｜DSML｜invoke name=\"write\"><｜DSML｜parameter name=\"content\">partial"))
        assertTrue(ToolContinuation.isIncomplete("<｜tool▁calls▁begin｜><｜tool▁call▁begin｜>function<｜tool▁sep｜>write {}"))
        assertFalse(ToolContinuation.isIncomplete("<function=write><parameter=content>ok</parameter></function>"))
        val json = org.json.JSONObject().put("name", "write").put("arguments", org.json.JSONObject().put("content", "literal <function=broken> and <tool>"))
        assertFalse(ToolContinuation.isIncomplete("<tool>$json</tool>"))
        assertFalse(ToolContinuation.isIncomplete("```tool\n$json\n```"))
    }
}
