package com.ialocalbridge

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WebApiKeyIntegrationTest {
    @Test fun encryptedKeysPersistAndCanBeDeletedConcurrently() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = ctx.getSharedPreferences("nem_web_api_keys", Context.MODE_PRIVATE)
        val oldIv = prefs.getString("iv", null)
        val oldValue = prefs.getString("value", null)
        try {
            prefs.edit().clear().commit()
            val pool = Executors.newFixedThreadPool(4)
            val calls = (1..8).map { i -> pool.submit { WebApiKeyStore(ctx).create("key-$i") } }
            calls.forEach { it.get(15, TimeUnit.SECONDS) }
            pool.shutdown()
            val store = WebApiKeyStore(ctx)
            val keys = store.list()
            assertEquals(8, keys.size)
            assertEquals(8, keys.map { it.getString("key") }.toSet().size)
            assertFalse(prefs.getString("value", "")!!.contains(keys[0].getString("key")))
            assertTrue(store.delete(keys[0].getString("id")))
            assertFalse(store.delete(keys[0].getString("id")))
            assertEquals(7, WebApiKeyStore(ctx).list().size)
        } finally {
            val e = prefs.edit().clear()
            if (oldIv != null) e.putString("iv", oldIv)
            if (oldValue != null) e.putString("value", oldValue)
            e.commit()
        }
    }
}
