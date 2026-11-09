package com.ialocalbridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Persistent estimated token counts. No unverified claim about API pricing. */
class NemStatsStore(context: Context) {
    private val prefs = context.getSharedPreferences("nem_stats_v1", Context.MODE_PRIVATE)
    private fun read(): JSONObject = try { JSONObject(prefs.getString("stats", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
    private fun emptyBucket() = JSONObject().put("requests", 0).put("prompt_tokens", 0).put("completion_tokens", 0)
    private fun increment(bucket: JSONObject, prompt: Int, completion: Int) {
        bucket.put("requests", bucket.optLong("requests") + 1)
        bucket.put("prompt_tokens", bucket.optLong("prompt_tokens") + prompt)
        bucket.put("completion_tokens", bucket.optLong("completion_tokens") + completion)
    }
    private fun day(date: Date): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)

    fun record(provider: String, prompt: Int, completion: Int) = synchronized(LOCK) {
        val data = read()
        increment(data, prompt, completion)
        val providers = data.optJSONObject("providers") ?: JSONObject()
        val p = providers.optJSONObject(provider) ?: emptyBucket()
        increment(p, prompt, completion)
        providers.put(provider, p)
        val daily = data.optJSONObject("daily") ?: JSONObject()
        val date = day(Date())
        val d = daily.optJSONObject(date) ?: emptyBucket()
        increment(d, prompt, completion)
        daily.put(date, d)
        val cutoff = Calendar.getInstance().apply { add(Calendar.DATE, -13) }.time
        val keys = daily.keys().asSequence().toList()
        for (key in keys) if (key < day(cutoff)) daily.remove(key)
        data.put("providers", providers).put("daily", daily)
            .put("updated_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))
        check(prefs.edit().putString("stats", data.toString()).commit()) { "Impossible d’enregistrer les statistiques." }
    }

    fun clear() = synchronized(LOCK) { check(prefs.edit().remove("stats").commit()) }

    fun report(uptimeSeconds: Long): JSONObject = synchronized(LOCK) {
        val data = read()
        val result = JSONObject()
            .put("total_requests", data.optLong("requests"))
            .put("prompt_tokens", data.optLong("prompt_tokens"))
            .put("completion_tokens", data.optLong("completion_tokens"))
            .put("uptime_seconds", uptimeSeconds)
            .put("requests_per_minute", if (uptimeSeconds > 0) data.optLong("requests") * 60.0 / uptimeSeconds else 0.0)
            .put("providers", data.optJSONObject("providers") ?: JSONObject())
            .put("updated_at", data.optString("updated_at"))
        val savedDays = data.optJSONObject("daily") ?: JSONObject()
        val days = JSONArray()
        for (offset in 13 downTo 0) {
            val date = day(Calendar.getInstance().apply { add(Calendar.DATE, -offset) }.time)
            days.put((savedDays.optJSONObject(date) ?: emptyBucket()).put("date", date))
        }
        result.put("daily", days)
    }
    companion object { private val LOCK = Any() }
}
