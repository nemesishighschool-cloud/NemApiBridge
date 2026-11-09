package com.ialocalbridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentSideQueriesTest {
    private fun msg(content:String) = JSONArray().put(JSONObject().put("role","user").put("content",content))
    private fun tools(name:String,schema:JSONObject) = JSONArray().put(JSONObject().put("type","function").put("function",JSONObject().put("name",name).put("parameters",schema)))
    @Test fun recognizesOnlyKnownHousekeeping() {
        assertEquals("", AgentSideQueries.classify(msg("[SUGGESTION MODE: test]"),null,null,null)!!.text)
        assertTrue(AgentSideQueries.classify(msg("ordinary coding request"),null,null,null)==null)
        val anyTool=tools("read_file",JSONObject().put("type","object"))
        assertNull(AgentSideQueries.classify(msg("read it"),anyTool,"required",null))
    }
    @Test fun neutralSchemaIsLocalButSafetyIsRefused() {
        val schema=JSONObject("""{"type":"object","properties":{"selected_memories":{"type":"array"}},"required":["selected_memories"]}""")
        val call=AgentSideQueries.classify(msg("choose"),tools("respond_in_schema",schema),"required",null)!!.calls!!.toolCalls.single()
        assertEquals("respond_in_schema",call.name)
        assertEquals(0,JSONObject(call.arguments).getJSONArray("selected_memories").length())
        val impossible=JSONObject(schema.toString()).put("maxProperties",0)
        assertNotNull(AgentSideQueries.classify(msg("choose"),tools("respond_in_schema",impossible),"required",null)!!.error)
        val minItems=JSONObject("""{"type":"object","properties":{"selected_memories":{"type":"array","minItems":1}},"required":["selected_memories"]}""")
        assertNotNull(AgentSideQueries.classify(msg("choose"),tools("respond_in_schema",minItems),"required",null)!!.error)
        val safety=JSONObject("""{"type":"object","properties":{"shouldBlock":{"type":"boolean"}},"required":["shouldBlock"]}""")
        assertNotNull(AgentSideQueries.classify(msg("classify"),tools("respond_in_schema",safety),"required",null)!!.error)
        val uncertain=JSONObject("""{"type":"object","properties":{"action":{"type":"string","enum":["execute","write"]}},"required":["action"]}""")
        assertNull(AgentSideQueries.classify(msg("schema"),tools("respond_in_schema",uncertain),"required",null))
    }
}
