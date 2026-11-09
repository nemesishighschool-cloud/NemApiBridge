package com.ialocalbridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.atomic.AtomicInteger

/** Android agent profiles and conservative in-memory incremental conversation tracking. */
class AgentIntegrationManager(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("nem_agent_integrations_v1", Context.MODE_PRIVATE)
    private val lock = ReentrantLock()
    private val accepted = AtomicInteger(0)
    @Volatile private var state: Session? = null
    private val detected = mutableMapOf<String, Pair<Long, Int>>()
    @Volatile private var full = 0L
    @Volatile private var delta = 0L
    @Volatile private var charsSaved = 0L
    private val localRequests = java.util.concurrent.atomic.AtomicLong(0)
    private val continuations = java.util.concurrent.atomic.AtomicLong(0)
    fun countLocal() { localRequests.incrementAndGet() }
    fun countContinuation() { continuations.incrementAndGet() }
    data class Active(val id: String, val incremental: Boolean, val tools: Boolean, val nativeTools: Boolean, val localSideQueries: Boolean, val continuation: Boolean)
    data class Plan(val prompt: String, val usedDelta: Boolean)
    private data class Session(val agent: String, val provider: String, val history: List<String>, val toolFingerprint: String, val marker: String?)

    private val supported = setOf("incremental_context", "tool_calls", "native_tool_calls", "side_queries_local", "continuation")
    private val descriptions = linkedMapOf(
        "persistent_chat" to Pair("Discussion unique", "L’application ne change pas automatiquement de conversation."),
        "incremental_context" to Pair("Contexte incrémental", "Envoie seulement les nouveaux messages si l’historique et le dernier contenu visible correspondent. Réinitialise si tu changes de discussion."),
        "tool_calls" to Pair("Appels d’outils", "Traduit les appels de fonctions OpenAI déclarés par le client."),
        "continuation" to Pair("Suite des appels coupés", "Demande la suite d’un appel incomplet et ne recolle que si un chevauchement exact et unique le permet ; sinon la requête échoue sans exécuter l’appel."),
        "side_queries_local" to Pair("Requêtes annexes locales", "Traite uniquement les requêtes d’entretien reconnues sans toucher à la discussion. Les décisions de sécurité et schémas incertains sont refusés."),
        "native_tool_calls" to Pair("Appels natifs", "Reconnaît les blocs JSON d’outils au format natif NEMESIS sans liste d’outils OpenAI."),
        "keepalive" to Pair("Maintien de connexion", "Envoie des événements pendant les générations longues en streaming.")
    )
    private val profiles = listOf(Triple("qwen-code", "Qwen Code", "Alibaba · QwenLM"), Triple("monkey-code", "MonkeyCode", "chaitin · OhMyAgent"), Triple("nemesis", "NEMESIS", "Agent de développement"))

    private fun enabled(id: String): Boolean = prefs.getBoolean("$id.enabled", false)
    private fun feature(id: String, name: String): Boolean = prefs.getBoolean("$id.$name", true)
    fun resolve(headers: Map<String, String>, messages: JSONArray): Active? {
        val explicit = headers["x-nemapi-agent"]?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val ua = headers["user-agent"]?.lowercase(Locale.ROOT).orEmpty()
        val first = (0 until minOf(messages.length(), 3)).joinToString(" ") { messages.optJSONObject(it)?.optString("content").orEmpty() }
        val id = when {
            explicit.isNotEmpty() -> explicit
            ua.startsWith("qwencode/") || first.contains("You are Qwen Code") -> "qwen-code"
            ua.startsWith("monkeycode") || ua.startsWith("monkey-code") || ua.startsWith("ohmyagent") || first.contains("You are OhMyAgent") -> "monkey-code"
            ua.startsWith("nemesis-agent") || ua.startsWith("nemesis-cli") || ua.startsWith("nemesis/") || first.contains("You are **NEMESIS**") || first.contains("NEMESIS Runtime Prompt") || first.contains("NEMESIS is a reliable engineering agent") -> "nemesis"
            else -> ""
        }
        if (profiles.none { it.first == id }) return null
        synchronized(detected) { val old = detected[id]; detected[id] = System.currentTimeMillis() to ((old?.second ?: 0) + 1) }
        if (!enabled(id)) return null
        return Active(id, id != "nemesis" && feature(id, "incremental_context"), feature(id, "tool_calls"), id == "nemesis" && feature(id, "native_tool_calls"), id != "nemesis" && feature(id, "side_queries_local"), id != "nemesis" && feature(id, "continuation"))
    }

    fun update(id: String, body: JSONObject): JSONObject {
        require(profiles.any { it.first == id }) { "Agent inconnu." }
        reset()
        val edit = prefs.edit()
        if (body.has("enabled")) {
            require(body.opt("enabled") is Boolean) { "Valeur enabled invalide." }
            edit.putBoolean("$id.enabled", body.getBoolean("enabled"))
        }
        body.optJSONObject("features")?.let { values ->
            val keys = values.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                require(key in supported && values.opt(key) is Boolean) { "Fonction d’intégration non disponible : $key" }
                edit.putBoolean("$id.$key", values.getBoolean(key))
            }
        }
        check(edit.commit())
        return agentJson(profiles.first { it.first == id })
    }
    private fun agentJson(profile: Triple<String, String, String>): JSONObject {
        val (id, name, vendor) = profile
        val isSupported = true
        val features = JSONArray()
        for ((key, description) in descriptions) {
            if (id == "nemesis" && (key == "incremental_context" || key == "side_queries_local" || key == "continuation")) continue
            if (id != "nemesis" && key == "native_tool_calls") continue
            features.put(JSONObject().put("id", key).put("label", description.first)
                .put("description", if (!isSupported) "En cours de portage sur Android." else description.second + if (key !in supported) " Toujours actif ; non configurable sur Android." else "")
                .put("enabled", isSupported && (key !in supported || feature(id, key))).put("tag", description.first).put("available", isSupported && key in supported))
        }
        val d = synchronized(detected) { detected[id] }
        val detection = JSONObject().put("requests", d?.second ?: 0)
            .put("seconds_ago", d?.let { (System.currentTimeMillis() - it.first) / 1000 } ?: JSONObject.NULL)
            .put("reason", "en-tête, User-Agent ou prompt")
        return JSONObject().put("id", id).put("name", name).put("vendor", vendor)
            .put("description", if (isSupported) "Profil Android : fonctions disponibles indiquées ci-dessous." else "Intégration native encore en cours de portage.")
            .put("setup", "Configure l’URL de base, le modèle du provider actif et l’en-tête X-NemApi-Agent: $id.")
            .put("snippet", "OPENAI_BASE_URL={base}\nOPENAI_MODEL=deepseek-chat\nX-NemApi-Agent: $id")
            .put("identifiers", JSONArray().put(JSONObject().put("kind", "header").put("value", "X-NemApi-Agent: $id").put("note", "recommandé")))
            .put("enabled", isSupported && enabled(id)).put("available", isSupported).put("features", features).put("detected", detection)
    }
    fun snapshot(): JSONObject {
        val agents = JSONArray(); profiles.forEach { agents.put(agentJson(it)) }
        val session = state
        val sessions = JSONObject()
        if (session != null) sessions.put(session.provider.lowercase(Locale.ROOT), JSONObject().put("turns", full + delta).put("messages", session.history.size).put("busy", AutomationCoordinator.isRunning))
        return JSONObject().put("agents", agents).put("clients", JSONArray()).put("discussion_owners", JSONObject())
            .put("sessions", JSONObject().put("sessions", sessions).put("requests", JSONObject().put("full", full).put("delta", delta).put("local", localRequests.get()).put("continuations", continuations.get())).put("chars_saved", charsSaved))
    }
    fun reset(): Int {
        if (!lock.tryLock()) throw IllegalArgumentException("Une requête est en cours ; réessaie après sa fin.")
        return try { val count = if (state == null) 0 else 1; state = null; count } finally { lock.unlock() }
    }
    fun <T> serialized(block: () -> T): T {
        if (accepted.incrementAndGet() > 2) {
            accepted.decrementAndGet()
            throw AutomationException("queue_full", "Une autre requête utilise déjà l’application cible.")
        }
        try {
            if (!lock.tryLock(15, TimeUnit.SECONDS)) throw AutomationException("queue_timeout", "La requête a expiré en attendant la file d’automatisation.")
            return try { block() } finally { lock.unlock() }
        } finally { accepted.decrementAndGet() }
    }
    fun plan(active: Active?, provider: String, messages: JSONArray, tools: JSONArray?, marker: String?, fullPrompt: String, makeDelta: (JSONArray) -> String): Plan {
        if (active == null || !active.incremental) { state = null; return Plan(fullPrompt, false) }
        val current = fingerprints(messages)
        val s = state
        if (s != null && s.agent == active.id && s.provider == provider && marker != null && marker == s.marker &&
            s.toolFingerprint == fingerprint(tools ?: JSONArray()) && current.size > s.history.size && current.take(s.history.size) == s.history) {
            val tail = JSONArray()
            for (i in s.history.size until messages.length()) tail.put(messages.getJSONObject(i))
            val prompt = makeDelta(tail)
            if (prompt.isNotBlank() && prompt.length < fullPrompt.length) {
                delta++; charsSaved += fullPrompt.length - prompt.length
                return Plan(prompt, true)
            }
        }
        full++
        return Plan(fullPrompt, false)
    }
    fun completed(active: Active?, provider: String, messages: JSONArray, tools: JSONArray?, assistant: JSONObject, marker: String?) {
        if (active == null || !active.incremental || marker == null) { state = null; return }
        state = Session(active.id, provider, fingerprints(messages) + fingerprintMessage(assistant), fingerprint(tools ?: JSONArray()), marker)
    }
    fun invalidate() { state = null }

    private fun fingerprints(messages: JSONArray): List<String> = (0 until messages.length()).map { fingerprintMessage(messages.getJSONObject(it)) }
    private fun fingerprintMessage(m: JSONObject): String {
        val role = m.optString("role").lowercase(Locale.ROOT)
        val result = JSONObject().put("role", role)
        val calls = m.optJSONArray("tool_calls")
        result.put("content", if (m.isNull("content")) "" else m.opt("content"))
        if (role == "assistant" && calls != null && calls.length() > 0) {
            val simplified = JSONArray()
            for (i in 0 until calls.length()) {
                val fn = calls.getJSONObject(i).optJSONObject("function") ?: JSONObject()
                val arg = fn.opt("arguments")
                val canonicalArg = if (arg is String) try { JSONObject(arg) } catch (_: Exception) { arg } else arg
                simplified.put(JSONObject().put("name", fn.optString("name")).put("arguments", canonicalArg))
            }
            result.put("calls", simplified)
        }
        return fingerprint(result)
    }
    private fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.opt(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.opt(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
    private fun fingerprint(value: Any): String = MessageDigest.getInstance("SHA-256").digest(canonical(value).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
