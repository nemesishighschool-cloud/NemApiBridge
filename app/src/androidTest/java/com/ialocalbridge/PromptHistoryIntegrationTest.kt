package com.ialocalbridge

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.InvocationTargetException

@RunWith(AndroidJUnit4::class)
class PromptHistoryIntegrationTest {
    private fun msg(role:String,text:String) = JSONObject().put("role",role).put("content",text)
    private fun format(messages:JSONArray, limit:Int, reject:Boolean):String {
        val server = LocalApiServer(0, ApplicationProvider.getApplicationContext())
        val method = LocalApiServer::class.java.getDeclaredMethod("buildPrompt", JSONArray::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        method.isAccessible = true
        try { return method.invoke(server,messages,limit,true,reject) as String }
        catch (e:InvocationTargetException) { throw e.targetException }
    }
    @Test fun preservesSystemWhenTrimmingOldDialogue() {
        val messages=JSONArray().put(msg("system","KEEP_SYSTEM"))
            .put(msg("user","old".repeat(40))).put(msg("assistant","old reply"))
            .put(msg("user","CURRENT"))
        val out=format(messages,80,false)
        assertTrue(out.contains("KEEP_SYSTEM"));assertTrue(out.contains("CURRENT"));assertFalse(out.contains("oldold"))
        try { format(messages,80,true); fail("agent history was silently truncated") } catch(e:AutomationException) { assertEquals("request_too_large",e.errorCode) }
    }
    @Test fun failsRatherThanDroppingOversizedSystem() {
        val messages=JSONArray().put(msg("system","x".repeat(120))).put(msg("user","CURRENT"))
        try { format(messages,80,false);fail("system instruction was dropped") } catch(e:AutomationException) { assertEquals("request_too_large",e.errorCode) }
    }
}
