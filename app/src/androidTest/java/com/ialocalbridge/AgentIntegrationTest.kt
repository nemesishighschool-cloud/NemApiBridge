package com.ialocalbridge

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentIntegrationTest {
    @Test fun onlyUsesDeltaForVerifiedPrefixAndUnchangedVisibleAnswer() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = ctx.getSharedPreferences("nem_agent_integrations_v1", Context.MODE_PRIVATE)
        val previous = prefs.all.toMap()
        val mgr = AgentIntegrationManager(ctx)
        try {
            prefs.edit().clear().commit()
            mgr.update("qwen-code", JSONObject().put("enabled", true))
            val one = JSONArray().put(JSONObject().put("role", "user").put("content", "initial request"))
            val active = mgr.resolve(mapOf("x-nemapi-agent" to "qwen-code"), one)!!
            val tools = JSONArray()
            mgr.serialized {
                val first = mgr.plan(active, "DeepSeek", one, tools, "old", "full initial") { "delta" }
                assertFalse(first.usedDelta)
                val reply = JSONObject().put("role", "assistant").put("content", "first response")
                mgr.completed(active, "DeepSeek", one, tools, reply, "marker")
                val next = JSONArray(one.toString()).put(reply).put(JSONObject().put("role", "user").put("content", "new"))
                assertTrue(mgr.plan(active, "DeepSeek", next, tools, "marker", "a full long prompt") { "new" }.usedDelta)
                assertFalse(mgr.plan(active, "DeepSeek", next, tools, "changed", "a full long prompt") { "new" }.usedDelta)
                assertFalse(mgr.plan(active, "DeepSeek", next, JSONArray().put(JSONObject().put("x", 1)), "marker", "a full long prompt") { "new" }.usedDelta)
                val changed = JSONArray().put(JSONObject().put("role", "user").put("content", "other")).put(reply).put(JSONObject().put("role", "user").put("content", "new"))
                assertFalse(mgr.plan(active, "DeepSeek", changed, tools, "marker", "a full long prompt") { "new" }.usedDelta)
            }
            assertEquals(1, mgr.reset())
            assertEquals(0, mgr.reset())
            mgr.update("nemesis", JSONObject().put("enabled", true))
            assertTrue(mgr.resolve(mapOf("x-nemapi-agent" to "nemesis"), one)!!.nativeTools)
            val valid = com.ialocalbridge.utils.ToolParser.parseToolCalls("```json\n{\"tool\":\"read_file\",\"parameters\":{\"file_path\":\"a.txt\"}}\n```", generic = true)
            assertEquals("read_file", valid!!.toolCalls.single().name)
            assertNull(com.ialocalbridge.utils.ToolParser.parseToolCalls("```json\n{\"name\":\"ordinary\"}\n```", generic = true))
            assertNull(com.ialocalbridge.utils.ToolParser.parseToolCalls("```json\n{\"tool\":\"read_file\",\"parameters\":\"not an object\"}\n```", generic = true))
        } finally {
            val e = prefs.edit().clear()
            previous.forEach { (k, v) -> if (v is Boolean) e.putBoolean(k, v) }
            e.commit()
        }
    }
}
