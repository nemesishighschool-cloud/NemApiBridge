package com.ialocalbridge

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NemStatsIntegrationTest {
    @Test fun persistsCountsAndClearsThem() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = ctx.getSharedPreferences("nem_stats_v1", Context.MODE_PRIVATE)
        val previous = prefs.getString("stats", null)
        try {
            val store = NemStatsStore(ctx)
            store.clear()
            store.record("DeepSeek", 12, 7)
            store.record("DeepSeek", 3, 4)
            val report = NemStatsStore(ctx).report(120)
            assertEquals(2, report.getLong("total_requests"))
            assertEquals(15, report.getLong("prompt_tokens"))
            assertEquals(11, report.getLong("completion_tokens"))
            assertEquals(2, report.getJSONObject("providers").getJSONObject("DeepSeek").getLong("requests"))
            assertEquals(2, report.getJSONArray("daily").getJSONObject(13).getLong("requests"))
            store.clear()
            assertEquals(0, store.report(0).getLong("total_requests"))
        } finally {
            val editor = prefs.edit()
            if (previous == null) editor.remove("stats") else editor.putString("stats", previous)
            editor.commit()
        }
    }
}
