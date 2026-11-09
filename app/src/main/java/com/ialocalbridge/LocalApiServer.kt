package com.ialocalbridge

import android.content.Context
import android.util.Log
import com.ialocalbridge.utils.FileUploader
import com.ialocalbridge.utils.NetworkHelper
import com.ialocalbridge.utils.ToolParser
import com.ialocalbridge.utils.WebInterface
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicLong

class LocalApiServer(private val port: Int, context: Context) : NanoHTTPD("0.0.0.0", port) {

    private val appContext = context.applicationContext
    private val coordinator = AutomationCoordinator(appContext)
    private val providerRepository = ProviderRepository(appContext)
    private val apiKeyStore = ApiKeyStore(appContext)
    private val webKeyStore = WebApiKeyStore(appContext)
    private val fileUploader = FileUploader()
    private val nemStats = NemStatsStore(appContext)
    private val agentIntegrations = AgentIntegrationManager(appContext)

    @Volatile
    private var streamEnabled = appContext.getSharedPreferences(SETTINGS_PREFERENCES, Context.MODE_PRIVATE)
        .getBoolean(STREAM_ENABLED_KEY, true)

    private val totalPromptTokens = AtomicLong(0)
    private val totalCompletionTokens = AtomicLong(0)
    private val totalRequests = AtomicLong(0)
    private val startedAt = System.currentTimeMillis()

    override fun serve(session: IHTTPSession): Response {
        if (!isLocalClient(session)) {
            return jsonError(Response.Status.FORBIDDEN, "forbidden", "Seules les connexions locales sont autorisées.")
        }

        val uri = session.uri
        val sensitiveWeb = uri.startsWith("/web/nem/") || uri == "/next" || uri.startsWith("/next/") ||
            uri in setOf("/", "/index.html", "/admin", "/chat", "/legacy", "/legacy/chat")
        if (sensitiveWeb && !isSameOriginOrDirect(session)) {
            return jsonError(Response.Status.FORBIDDEN, "forbidden_origin", "L’administration web refuse les requêtes provenant d’un autre site.")
        }
        if (session.method == Method.OPTIONS) {
            return if (sensitiveWeb) newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "")
            else newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "").also(::addCORSHeaders)
        }
        if (session.method == Method.GET && (uri == "/next" || uri.startsWith("/next/"))) {
            return stagedWebAsset(uri).also { it.addHeader("Cache-Control", "no-store") }
        }
        if (session.method == Method.GET && uri in setOf("/", "/index.html", "/admin", "/chat")) {
            val path = if (uri == "/chat") "/next/chat" else "/next/"
            return stagedWebAsset(path).also { it.addHeader("Cache-Control", "no-store") }
        }
        // Keep the original Android bridge interface accessible for existing users.
        if (session.method == Method.GET && uri in setOf("/legacy", "/legacy/chat")) {
            return newFixedLengthResponse(Response.Status.OK, MIME_HTML, WebInterface.getHtml(appContext))
                .also { it.addHeader("Cache-Control", "no-store") }
        }
        if (session.method == Method.GET && uri == "/web/logo.png") {
            return newChunkedResponse(Response.Status.OK, "image/png", appContext.assets.open("www/logo.png"))
                .also(::addCORSHeaders)
        }
        if (uri == "/health" && session.method == Method.GET) {
            val health = JSONObject()
                .put("status", "ok")
                .put("uptime_seconds", uptimeSeconds())
            return newFixedLengthResponse(Response.Status.OK, MIME_JSON, health.toString()).also(::addCORSHeaders)
        }
        // Browser dashboard and chat are intentionally open on the local network.
        // API Bearer protection, when enabled, applies only to the API routes.
        if (!uri.startsWith("/web/") && !authorized(session)) {
            return jsonError(Response.Status.UNAUTHORIZED, "authentication_error", "Clé API absente ou invalide.")
                .also(::addCORSHeaders)
        }

        val response = try {
            when (uri) {
                "/web/nem/providers" -> if (session.method == Method.GET) nemProviders() else methodNotAllowed("GET")
                "/web/nem/extension/state" -> if (session.method == Method.GET) nemState() else methodNotAllowed("GET")
                "/web/nem/settings" -> nemSettings(session)
                "/web/nem/stats" -> if (session.method == Method.GET) nemJson(nemStats.report(uptimeSeconds())) else methodNotAllowed("GET")
                "/web/nem/stats/clear" -> if (session.method == Method.POST) { drainBody(session); nemStats.clear(); nemJson(JSONObject().put("ok", true)) } else methodNotAllowed("POST")
                "/web/nem/agent-integrations" -> if (session.method == Method.GET) nemJson(agentIntegrations.snapshot()) else methodNotAllowed("GET")
                "/web/nem/sessions/reset" -> if (session.method == Method.POST) {
                    drainBody(session)
                    try { nemJson(JSONObject().put("reset", agentIntegrations.reset())) }
                    catch (e: IllegalArgumentException) { throw AutomationException("user_intervention_required", e.message ?: "Requête en cours.") }
                } else methodNotAllowed("POST")
                "/web/nem/api-keys/config" -> if (session.method == Method.GET) nemJson(JSONObject().put("require_auth", apiKeyRequired()).put("key_count", webKeyStore.list().size + if (apiKeyStore.get() == null) 0 else 1)) else methodNotAllowed("GET")
                "/web/nem/api-keys" -> nemApiKeys(session)
                "/web/nem/api-keys/enable", "/web/nem/api-keys/disable" -> if (session.method == Method.POST) {
                    drainBody(session)
                    apiKeyStore.getOrCreate()
                    appContext.getSharedPreferences(SETTINGS_PREFERENCES, Context.MODE_PRIVATE).edit()
                        .putBoolean(API_KEY_REQUIRED_KEY, uri.endsWith("/enable")).apply()
                    nemJson(JSONObject().put("require_auth", apiKeyRequired()))
                } else methodNotAllowed("POST")
                "/web/nem/health" -> if (session.method == Method.GET) nemJson(JSONObject().put("app_version", "1.1.0")) else methodNotAllowed("GET")
                "/web/nem/stop" -> if (session.method == Method.POST) { drainBody(session); handleStopRequest() } else methodNotAllowed("POST")
                "/web/models" -> if (session.method == Method.GET) handleModelsRequest() else methodNotAllowed("GET")
                "/web/status" -> if (session.method == Method.GET) handleStatusRequest() else methodNotAllowed("GET")
                "/web/stats" -> if (session.method == Method.GET) handleStatsRequest() else methodNotAllowed("GET")
                "/web/network" -> if (session.method == Method.GET) handleNetworkRequest() else methodNotAllowed("GET")
                "/web/config" -> handleConfigRequest(session)
                "/web/chat" -> if (session.method == Method.POST) handleChatCompletions(session) else methodNotAllowed("POST")
                "/web/stop" -> if (session.method == Method.POST) { drainBody(session); handleStopRequest() } else methodNotAllowed("POST")
                "/v1/models", "/models" -> if (session.method == Method.GET) handleModelsRequest()
                    else methodNotAllowed("GET")
                "/v1/chat/completions", "/chat/completions" -> if (session.method == Method.POST) handleChatCompletions(session)
                    else methodNotAllowed("POST")
                "/v1/responses", "/responses" -> if (session.method == Method.POST) handleChatCompletions(session, true) else methodNotAllowed("POST")
                "/status" -> if (session.method == Method.GET) handleStatusRequest()
                    else methodNotAllowed("GET")
                "/stats" -> if (session.method == Method.GET) handleStatsRequest()
                    else methodNotAllowed("GET")
                "/config" -> handleConfigRequest(session)
                "/ask" -> if (session.method == Method.GET || session.method == Method.POST) handleAskRequest(session)
                    else methodNotAllowed("GET, POST")
                "/upload" -> if (session.method == Method.POST) handleUploadRequest(session)
                    else methodNotAllowed("POST")
                "/stop" -> if (session.method == Method.GET || session.method == Method.POST) { if (session.method == Method.POST) drainBody(session); handleStopRequest() }
                    else methodNotAllowed("GET, POST")
                else -> if (uri.startsWith("/web/nem/agent-integrations/") && session.method == Method.POST) {
                    try { nemJson(agentIntegrations.update(uri.substringAfterLast('/'), parseJsonBody(session))) }
                    catch (e: IllegalArgumentException) { throw AutomationException("invalid_request", e.message ?: "Intégration invalide.") }
                } else if (uri.startsWith("/web/nem/api-keys/") && session.method == Method.DELETE) {
                    if (webKeyStore.delete(uri.substringAfterLast('/'))) nemJson(JSONObject().put("deleted", true))
                    else jsonError(Response.Status.NOT_FOUND, "not_found", "Clé introuvable ou gérée sur le téléphone.")
                } else jsonError(Response.Status.NOT_FOUND, "not_found", "Endpoint introuvable.")
            }
        } catch (e: AutomationException) {
            val status = when (e.errorCode) {
                "queue_full", "queue_timeout" -> Response.Status.SERVICE_UNAVAILABLE
                "accessibility_disabled", "wrong_app", "selector_missing", "selector_ambiguous",
                "action_failed", "response_timeout", "response_incomplete", "user_intervention_required", "cancelled", "tool_call_required", "tool_call_incomplete", "continuation_ambiguous" -> Response.Status.CONFLICT
                "response_too_large", "request_too_large" -> Response.Status.PAYLOAD_TOO_LARGE
                else -> Response.Status.BAD_REQUEST
            }
            jsonError(status, e.errorCode, e.message).also { if (e.errorCode == "request_too_large") it.closeConnection(true) }
        } catch (e: Exception) {
            Log.e(TAG, "Request failed: ${e.javaClass.simpleName}")
            jsonError(Response.Status.INTERNAL_ERROR, "internal_error", "La requête n’a pas pu être traitée.")
        }
        return if (sensitiveWeb) response.also { it.addHeader("Cache-Control", "no-store") } else response.also(::addCORSHeaders)
    }

    private fun stagedWebAsset(uri: String): Response {
        val route = uri.removePrefix("/next").trimStart('/')
        val file = when (route) {
            "", "index.html" -> "admin.html"
            "chat" -> "chat.html"
            "analytics" -> "analytics.html"
            "config" -> "config.html"
            "integrations" -> "integrations.html"
            "api-keys-page" -> "api-keys.html"
            "admin.css", "admin.js" -> route
            "icons/logo-16.png", "icons/logo-32.png", "icons/logo-48.png", "icons/logo-128.png", "icons/logo.svg" -> route
            "icons/providers/deepseek.png", "icons/providers/chatgpt.png", "icons/providers/claude.png",
            "icons/providers/gemini.png", "icons/providers/qwen.png", "icons/providers/kimi.png", "icons/providers/zai.png" -> route
            else -> return jsonError(Response.Status.NOT_FOUND, "not_found", "Fichier introuvable.")
        }
        val mime = when (file.substringAfterLast('.')) {
            "html" -> "text/html; charset=utf-8"
            "js" -> "application/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "png" -> "image/png"
            "svg" -> "image/svg+xml"
            else -> "application/octet-stream"
        }
        return newChunkedResponse(Response.Status.OK, mime, appContext.assets.open("nemapi-stage/$file"))
    }

    private fun nemJson(obj: JSONObject): Response = newFixedLengthResponse(Response.Status.OK, MIME_JSON, obj.toString())

    private fun nemProviders(): Response {
        val config = providerRepository.getActiveName()?.let(providerRepository::get)
        val providers = JSONArray()
        if (config != null) providers.put(JSONObject().put("id", config.provider.lowercase())
            .put("models", JSONArray().put(config.provider)).put("name", config.provider)
            .put("supports_images", config.selectors.imageAttach.isNotEmpty() && config.selectors.imageDocument.isNotEmpty() && config.selectors.imageDelete.isNotEmpty()))
        return nemJson(JSONObject().put("providers", providers))
    }

    private fun nemState(): Response {
        val service = ClickAccessibilityService.instance
        val config = providerRepository.getActiveName()?.let(providerRepository::get)
        val current = if (service != null) runBlocking(Dispatchers.Main.immediate) { service.currentPackageName() } else null
        val tabs = JSONArray()
        val targets = JSONObject()
        if (config != null && current == config.packageName) {
            val id = config.provider.lowercase()
            tabs.put(JSONObject().put("id", 1).put("provider", id).put("title", config.packageName).put("url", ""))
            targets.put(id, 1)
        }
        return nemJson(JSONObject().put("connected", service != null).put("busy", AutomationCoordinator.isRunning)
            .put("tabs", tabs).put("targetTabs", targets).put("logs", JSONArray()))
    }

    private fun nemApiKeys(session: IHTTPSession): Response = when (session.method) {
        Method.GET -> {
            val keys = JSONArray()
            // The pre-existing Android key remains managed on the device and is not disclosed here.
            if (apiKeyStore.get() != null) keys.put(JSONObject().put("id", "android-key").put("name", "Clé Android (gérée sur le téléphone)").put("key", "").put("created_at", "—"))
            webKeyStore.list().forEach(keys::put)
            nemJson(JSONObject().put("require_auth", apiKeyRequired()).put("api_keys", keys))
        }
        Method.POST -> {
            val name = parseJsonBody(session).optString("name", "").trim()
            try {
                val key = webKeyStore.create(name)
                nemJson(JSONObject().put("api_key", key.getString("key")))
            } catch (e: IllegalArgumentException) {
                throw AutomationException("invalid_request", e.message ?: "Nom de clé invalide.")
            }
        }
        else -> methodNotAllowed("GET, POST")
    }

    private fun nemSettings(session: IHTTPSession): Response {
        when (session.method) {
            Method.GET -> Unit
            Method.POST -> {
                val body = parseJsonBody(session)
                if (!body.has("stream_enabled") || body.length() != 1 || body.opt("stream_enabled") !is Boolean) {
                    throw AutomationException("invalid_request", "Seul le réglage stream_enabled est actuellement disponible.")
                }
                streamEnabled = body.getBoolean("stream_enabled")
                appContext.getSharedPreferences(SETTINGS_PREFERENCES, Context.MODE_PRIVATE).edit()
                    .putBoolean(STREAM_ENABLED_KEY, streamEnabled).apply()
            }
            else -> return methodNotAllowed("GET, POST")
        }
        return nemJson(JSONObject().put("stream_enabled", streamEnabled))
    }

    private fun handleChatCompletions(session: IHTTPSession, responseMode: Boolean = false): Response {
        val incoming = parseJsonBody(session)
        RequestParameterAudit.validate(incoming)
        val request = if (responseMode) ResponsesCodec.toChat(incoming) else incoming
        val format = request.optJSONObject("response_format")
        if (format != null && format.optString("type", "text") != "text")
            throw AutomationException("unsupported_parameter", "Les formats de réponse JSON structurés ne sont pas garantis.")
        if (request.has("parallel_tool_calls") && !request.optBoolean("parallel_tool_calls", true))
            throw AutomationException("unsupported_parameter", "parallel_tool_calls=false n’est pas encore pris en charge.")
        val rawMessages = request.optJSONArray("messages")
            ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_request", "Le champ messages est requis.")
        if (rawMessages.length() == 0) {
            return jsonError(Response.Status.BAD_REQUEST, "invalid_request", "Le champ messages ne peut pas être vide.")
        }

        val imagePayload = ImagePayload.parse(rawMessages)
        val messages = imagePayload.messages
        val modelName = request.optString("model").ifBlank { providerRepository.getActiveName().orEmpty() }
        val config = providerRepository.get(modelName) ?: providerRepository.getActiveName()?.let(providerRepository::get)
            ?.takeIf { modelName.equals(it.provider + "-chat", ignoreCase = true) }
            ?: return jsonError(Response.Status.BAD_REQUEST, "model_not_found", "Le modèle '$modelName' n’est pas configuré.")

        if (imagePayload.images.isNotEmpty() && (config.selectors.imageAttach.isEmpty() || config.selectors.imageDocument.isEmpty() || config.selectors.imageDelete.isEmpty()))
            throw AutomationException("unsupported_content", "Ce provider n’a pas de sélecteurs d’image configurés.")
        val toolsValue = request.opt("tools")
        val tools = when (toolsValue) {
            null, JSONObject.NULL -> null
            is JSONArray -> toolsValue
            else -> throw AutomationException("invalid_request", "Le champ tools doit être un tableau.")
        }
        val toolChoice = request.opt("tool_choice")
        val agent = agentIntegrations.resolve(session.headers, messages)
        if (agent != null && !agent.tools && tools != null && tools.length() > 0)
            throw AutomationException("unsupported_tool", "Les appels d’outils sont désactivés pour cette intégration.")
        val toolInstruction = buildToolInstruction(tools, toolChoice)
        val requestedStream = request.optBoolean("stream", false)
        if (requestedStream && !streamEnabled) {
            return jsonError(Response.Status.BAD_REQUEST, "streaming_disabled", "Le streaming est désactivé dans la configuration.")
        }
        if (agent?.localSideQueries == true && imagePayload.images.isEmpty()) {
            val local = AgentSideQueries.classify(messages, tools, toolChoice, incoming.optJSONObject("metadata"))
            if (local != null) {
                agentIntegrations.countLocal()
                if (local.error != null) throw AutomationException("side_query_not_answered", local.error)
                val done = CompletionData(local.text, local.calls, 0, 0)
                val localResponse = if (requestedStream) {
                    if (responseMode) buildResponsesStream(modelName) { done } else buildStreamResponse(modelName) { done }
                } else if (responseMode) nemJson(responsesOutput(ResponsesCodec.newResponseId(), modelName, done))
                else buildJsonResponse(modelName, done.result, done.parsed, 0, 0)
                return localResponse.also { addIgnoredParameterWarning(it, incoming) }
            }
        }
        val historyPrompt = buildPrompt(messages, config.maxHistoryChars, rejectHistoryTruncation = imagePayload.images.isNotEmpty() || agent != null)
        val messagesPrompt = historyPrompt + if (imagePayload.images.isNotEmpty())
            "\n\nThe attached images are numbered in the same order as the [Image N attached] markers in the history. Earlier images are reattached as context." else ""
        val prompt = if (toolInstruction.isEmpty()) messagesPrompt else "$messagesPrompt\n\n$toolInstruction"

        fun screenMarker(): String? {
            val service = ClickAccessibilityService.instance ?: return null
            return try {
                runBlocking(Dispatchers.Main.immediate) {
                    if (service.currentPackageName() != config.packageName) null
                    else service.readLatestText(config.packageName, config.selectors.assistantMessage, "assistant_message").takeLast(1024).takeIf { it.isNotBlank() }
                }
            } catch (e: Exception) { Log.w(TAG, "Agent screen marker unavailable: ${(e as? AutomationException)?.errorCode ?: e.javaClass.simpleName}"); null }
        }
        val cancellationTicket = AutomationCoordinator.cancellationTicket()
        fun complete(): CompletionData = agentIntegrations.serialized {
          try {
            // A request can wait on the agent lock before it reaches the coordinator.
            AutomationCoordinator.ensureNotCancelled(cancellationTicket)
            val sessionAgent = if (imagePayload.images.isEmpty()) agent else null
            val beforeMarker = screenMarker()
            val plan = agentIntegrations.plan(sessionAgent, config.provider, messages, tools, beforeMarker, prompt) { tail ->
                val text = buildPrompt(tail, config.maxHistoryChars, requireUser = false)
                if (tools != null && tools.length() > 0) text + "\n\nReminder: to call a tool, use a fenced tool block with <tool>{JSON}</tool>." else text
            }
            val sentPrompt = plan.prompt
            val result = runBlocking(Dispatchers.IO) { coordinator.processQuestion(config, sentPrompt, imagePayload.images,
                detectIncompleteTool = toolChoice != "none" && ((tools != null && tools.length() > 0) || agent?.nativeTools == true),
                allowContinuation = agent?.continuation == true, onContinuation = agentIntegrations::countContinuation, ticket = cancellationTicket) }
            totalRequests.incrementAndGet()
            val promptTokens = estimateTokens(sentPrompt)
            val completionTokens = estimateTokens(result)
            totalPromptTokens.addAndGet(promptTokens.toLong())
            totalCompletionTokens.addAndGet(completionTokens.toLong())
            try { nemStats.record(config.provider, promptTokens, completionTokens) } catch (e: Exception) {
                Log.w(TAG, "Could not persist request statistics", e)
            }
            val names = tools?.let { array -> (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.let { (it.optJSONObject("function") ?: it).optString("name") }
            }.toSet() }
            val parsed = when {
                toolChoice == "none" -> null
                tools != null && tools.length() > 0 -> ToolParser.parseToolCalls(result, names)
                agent?.nativeTools == true -> ToolParser.parseToolCalls(result, null, generic = true)
                else -> null
            }
            val requiredName = (toolChoice as? JSONObject)?.optJSONObject("function")?.optString("name")
            if ((toolChoice == "required" || requiredName != null) &&
                (parsed == null || parsed.toolCalls.isEmpty() || (requiredName != null && parsed.toolCalls.any { it.name != requiredName }))) {
                throw AutomationException("tool_call_required", "Le fournisseur n’a pas produit l’appel d’outil demandé dans un format reconnu.")
            }
            val assistant = JSONObject().put("role", "assistant")
            if (parsed == null) assistant.put("content", result) else {
                assistant.put("content", parsed.remainingText.trim())
                val calls = JSONArray()
                parsed.toolCalls.forEach { call -> calls.put(JSONObject().put("id", call.id).put("type", "function")
                    .put("function", JSONObject().put("name", call.name).put("arguments", call.arguments))) }
                assistant.put("tool_calls", calls)
            }
            var finalMarker: String? = null
            if (sessionAgent?.incremental == true && beforeMarker != null) {
                // Copy closes a focus overlay and DeepSeek may briefly expose the previous
                // message. Never store that transient marker as the completed answer.
                var candidate: String? = null
                var stable = 0
                for (attempt in 0 until 32) {
                    val next = screenMarker()
                    if (next != null && next != beforeMarker && next == candidate) stable++
                    else { candidate = next; stable = if (next != null && next != beforeMarker) 1 else 0 }
                    if (stable >= 4) { finalMarker = candidate; break }
                    Thread.sleep(250)
                }
            }
            agentIntegrations.completed(sessionAgent, config.provider, messages, tools, assistant, finalMarker)
            CompletionData(result, parsed, promptTokens, completionTokens)
          } catch (e: Throwable) {
            agentIntegrations.invalidate()
            throw e
          }
        }
        val completionResponse = if (requestedStream) {
            if (responseMode) buildResponsesStream(modelName, ::complete) else buildStreamResponse(modelName, ::complete)
        } else {
            val done = complete()
            if (responseMode) nemJson(responsesOutput(ResponsesCodec.newResponseId(), modelName, done))
            else buildJsonResponse(modelName, done.result, done.parsed, done.promptTokens, done.completionTokens)
        }
        return completionResponse.also { addIgnoredParameterWarning(it, incoming) }
    }

    private fun addIgnoredParameterWarning(response: Response, request: JSONObject) {
        val ignored = RequestParameterAudit.ignoredFields(request)
        if (ignored.isNotEmpty()) response.addHeader("X-NemApi-Ignored-Parameters", ignored.joinToString(", "))
    }

    private data class CompletionData(val result: String, val parsed: ToolParser.ToolCallResult?, val promptTokens: Int, val completionTokens: Int)

    private fun buildToolInstruction(tools: JSONArray?, choice: Any?): String {
        if (tools == null || tools.length() == 0) {
            if (choice != null && choice != JSONObject.NULL && choice != "auto" && choice != "none") {
                throw AutomationException("invalid_request", "tool_choice nécessite un tableau tools non vide.")
            }
            return ""
        }

        for (i in 0 until tools.length()) {
            val tool = tools.optJSONObject(i) ?: throw AutomationException("invalid_request", "Définition d’outil invalide.")
            if (tool.has("type") && tool.opt("type") !is String)
                throw AutomationException("invalid_request", "Le type de l’outil doit être une chaîne.")
            if (tool.has("type") && tool.optString("type") != "function")
                throw AutomationException("unsupported_tool", "Seuls les outils function sont pris en charge.")
            if (tool.has("function") && tool.opt("function") !is JSONObject)
                throw AutomationException("invalid_request", "La définition function doit être un objet.")
            val fn = tool.optJSONObject("function") ?: tool
            if (fn.opt("name") !is String || fn.optString("name").isBlank())
                throw AutomationException("invalid_request", "Le nom de fonction est requis.")
            if (fn.has("parameters") && !fn.isNull("parameters") && fn.opt("parameters") !is JSONObject)
                throw AutomationException("invalid_request", "Le champ parameters doit être un objet JSON.")
            if (tool.optBoolean("strict", false) || fn.optBoolean("strict", false))
                throw AutomationException("unsupported_parameter", "Les outils strict=true ne sont pas garantis sur le provider web.")
        }
        // Validate duplicate names and definitions even when tool_choice is none.
        val base = try { ToolParser.buildToolCallPrompt(tools) }
        catch (_: Exception) { throw AutomationException("invalid_request", "La définition tools est invalide.") }
        var toolCallRequired = false
        val selectedFunction = when (choice) {
            null, JSONObject.NULL, "auto" -> null
            "none" -> return ""
            "required" -> {
                toolCallRequired = true
                null
            }
            is JSONObject -> {
                if (choice.optString("type") != "function") {
                    throw AutomationException("unsupported_tool_choice", "Seul tool_choice de type function est pris en charge.")
                }
                choice.optJSONObject("function")?.optString("name")
                    ?.takeIf(String::isNotBlank)
                    ?: throw AutomationException("invalid_request", "tool_choice.function.name est requis.")
            }
            else -> throw AutomationException("unsupported_tool_choice", "La valeur tool_choice n’est pas prise en charge.")
        }

        if (selectedFunction != null && !containsTool(tools, selectedFunction)) {
            throw AutomationException("invalid_request", "La fonction '$selectedFunction' ne figure pas dans tools.")
        }
        return when {
            toolCallRequired -> "$base\nUn appel d’outil est obligatoire pour cette réponse."
            selectedFunction == null -> base
            else -> "$base\nTu dois appeler uniquement la fonction '$selectedFunction'."
        }
    }

    private fun containsTool(tools: JSONArray, functionName: String): Boolean {
        for (index in 0 until tools.length()) {
            val tool = tools.optJSONObject(index) ?: continue
            val function = tool.optJSONObject("function") ?: tool
            if (function.optString("name") == functionName) return true
        }
        return false
    }

    private fun buildPrompt(messages: JSONArray, maxHistoryChars: Int, requireUser: Boolean = true, rejectHistoryTruncation: Boolean = false): String {
        val normalized = mutableListOf<Pair<String, String>>()
        val callNames = mutableMapOf<String, String>()
        for (index in 0 until messages.length()) {
            val calls = messages.optJSONObject(index)?.optJSONArray("tool_calls") ?: continue
            for (i in 0 until calls.length()) {
                val call = calls.optJSONObject(i) ?: continue
                val id = call.optString("id")
                val name = call.optJSONObject("function")?.optString("name").orEmpty()
                if (id.isNotBlank() && name.isNotBlank()) callNames[id] = name
            }
        }
        var hasToolResult = false
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index)
                ?: throw AutomationException("invalid_request", "Chaque message doit être un objet JSON.")
            val role = message.optString("role").lowercase()
            if (role !in SUPPORTED_ROLES) {
                throw AutomationException("unsupported_role", "Le rôle '$role' n’est pas pris en charge.")
            }
            val content = message.opt("content")
            val text = when (content) {
                is String -> content
                null, JSONObject.NULL -> ""
                else -> throw AutomationException(
                    "unsupported_content",
                    "Seuls les contenus textuels sont pris en charge. Utilise /upload pour les fichiers."
                )
            }
            if (role == "user" && text.isBlank()) continue
            val toolCalls = message.optJSONArray("tool_calls")?.toString()?.let { "\nPrevious tool calls: $it" }.orEmpty()
            val label = if (role == "tool") {
                hasToolResult = true
                val name = callNames[message.optString("tool_call_id")]
                    ?: message.optString("name").ifBlank { "tool" }
                "Tool result ($name)"
            } else ROLE_LABELS[role]
            normalized.add(role to "$label: $text$toolCalls")
        }
        val latestUserIndex = normalized.indexOfLast { it.first == "user" }
        if (latestUserIndex < 0 && requireUser) {
            throw AutomationException("invalid_request", "La conversation doit contenir au moins un message user.")
        }

        val retained = ArrayDeque<String>()
        var retainedChars = 0
        for (index in normalized.lastIndex downTo 0) {
            val entry = normalized[index].second
            if (entry.length > maxHistoryChars && retained.isEmpty()) {
                throw AutomationException("request_too_large", "Un message courant dépasse la limite d’historique du provider.")
            }
            if (retainedChars + entry.length > maxHistoryChars) {
                if (index >= latestUserIndex || rejectHistoryTruncation) {
                    throw AutomationException(
                        "request_too_large",
                        "L’historique courant ne tient pas dans la limite du provider sans perdre des instructions ou du contexte."
                    )
                }
                // Earlier dialogue may be dropped, but never silently discard
                // system/developer instructions that precede the retained turns.
                val instructions = normalized.take(index + 1).filter { it.first == "system" || it.first == "developer" }
                val instructionChars = instructions.sumOf { it.second.length }
                if (retainedChars + instructionChars > maxHistoryChars)
                    throw AutomationException("request_too_large", "Les instructions et le dernier tour dépassent la limite d’historique du provider.")
                for (item in instructions.asReversed()) retained.addFirst(item.second)
                break
            }
            retained.addFirst(entry)
            retainedChars += entry.length
        }
        val footer = if (hasToolResult) "\n\nContinue using the tool results above. Do not repeat successful tool calls; take the next step or give the final answer." else ""
        return retained.joinToString("\n\n") + footer
    }

    private fun buildJsonResponse(
        model: String,
        rawResult: String,
        parsed: ToolParser.ToolCallResult?,
        promptTokens: Int,
        completionTokens: Int
    ): Response {
        val message = JSONObject().put("role", "assistant")
        val choice = JSONObject().put("index", 0)
        val finishReason: String
        if (parsed != null) {
            message.put("content", parsed.remainingText.trim().ifEmpty { JSONObject.NULL })
            val toolCalls = JSONArray()
            parsed.toolCalls.forEach { call ->
                toolCalls.put(
                    JSONObject()
                        .put("id", call.id)
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject().put("name", call.name).put("arguments", call.arguments)
                        )
                )
            }
            message.put("tool_calls", toolCalls)
            finishReason = "tool_calls"
        } else {
            message.put("content", rawResult)
            finishReason = "stop"
        }
        choice.put("message", message).put("finish_reason", finishReason)

        val response = JSONObject()
            .put("id", "chatcmpl-${UUID.randomUUID().toString().take(12)}")
            .put("object", "chat.completion")
            .put("created", System.currentTimeMillis() / 1000)
            .put("model", model)
            .put("choices", JSONArray().put(choice))
            .put(
                "usage",
                JSONObject()
                    .put("prompt_tokens", promptTokens)
                    .put("completion_tokens", completionTokens)
                    .put("total_tokens", promptTokens + completionTokens)
            )
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, response.toString())
    }

    private fun responsesOutput(id: String, model: String, done: CompletionData): JSONObject {
        val text = done.parsed?.remainingText?.trim() ?: done.result
        val calls = done.parsed?.toolCalls?.map { ResponsesCodec.ToolParserCall(it.id, it.name, it.arguments) }.orEmpty()
        return ResponsesCodec.output(id, model, text, calls, done.promptTokens, done.completionTokens)
    }

    private fun buildResponsesStream(model: String, complete: () -> CompletionData): Response {
        val responseId = ResponsesCodec.newResponseId()
        val stream = PipedInputStream(STREAM_BUFFER_SIZE)
        val output = PipedOutputStream(stream)
        val task = FutureTask { complete() }
        Thread({
            var sequence = 0
            fun event(name: String, body: JSONObject) {
                body.put("type", name).put("sequence_number", sequence++)
                output.write("event: $name\ndata: $body\n\n".toByteArray(Charsets.UTF_8))
                output.flush()
            }
            try {
                val created = ResponsesCodec.output(responseId, model, "", emptyList(), 0, 0)
                    .put("status", "in_progress").put("usage", JSONObject.NULL)
                event("response.created", JSONObject().put("response", created))
                event("response.in_progress", JSONObject().put("response", created))
                Thread(task, "api-bridge-responses-generation").apply { isDaemon = true; start() }
                lateinit var done: CompletionData
                while (true) {
                    try { done = task.get(10, TimeUnit.SECONDS); break }
                    catch (_: TimeoutException) { output.write(": keepalive\n\n".toByteArray(Charsets.UTF_8)); output.flush() }
                }
                val result = responsesOutput(responseId, model, done)
                val items = result.getJSONArray("output")
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    val itemId = item.getString("id")
                    val added = JSONObject(item.toString()).put("status", "in_progress")
                    if (item.getString("type") == "message") added.put("content", JSONArray()) else added.put("arguments", "")
                    event("response.output_item.added", JSONObject().put("output_index", i).put("item", added))
                    if (item.getString("type") == "message") {
                        val part = item.getJSONArray("content").getJSONObject(0)
                        val text = part.getString("text")
                        event("response.content_part.added", JSONObject().put("item_id", itemId).put("output_index", i).put("content_index", 0)
                            .put("part", JSONObject().put("type", "output_text").put("text", "").put("annotations", JSONArray())))
                        var offset = 0
                        while (offset < text.length) {
                            val count = minOf(240, text.codePointCount(offset, text.length))
                            val next = text.offsetByCodePoints(offset, count)
                            event("response.output_text.delta", JSONObject().put("item_id", itemId).put("output_index", i).put("content_index", 0).put("delta", text.substring(offset, next)))
                            offset = next
                        }
                        event("response.output_text.done", JSONObject().put("item_id", itemId).put("output_index", i).put("content_index", 0).put("text", text))
                        event("response.content_part.done", JSONObject().put("item_id", itemId).put("output_index", i).put("content_index", 0).put("part", part))
                    } else {
                        val args = item.getString("arguments")
                        event("response.function_call_arguments.delta", JSONObject().put("item_id", itemId).put("output_index", i).put("delta", args))
                        event("response.function_call_arguments.done", JSONObject().put("item_id", itemId).put("output_index", i).put("arguments", args))
                    }
                    event("response.output_item.done", JSONObject().put("output_index", i).put("item", item))
                }
                event("response.completed", JSONObject().put("response", result))
                output.write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8)); output.flush()
            } catch (e: ExecutionException) {
                val cause = e.cause
                val code = (cause as? AutomationException)?.errorCode ?: "internal_error"
                val message = if (cause is AutomationException) cause.message else "La requête n’a pas pu être traitée."
                try { event("error", JSONObject().put("code", code).put("message", message).put("param", JSONObject.NULL)
                    .put("error", JSONObject().put("code", code).put("message", message))) } catch (_: Exception) { }
            } catch (_: Exception) {
                Log.i(TAG, "Responses SSE client disconnected")
            } finally { task.cancel(true); try { output.close() } catch (_: Exception) { } }
        }, "api-bridge-responses-sse").apply { isDaemon = true; start() }
        return newChunkedResponse(Response.Status.OK, MIME_EVENT_STREAM, stream)
    }

    private fun buildStreamResponse(model: String, complete: () -> CompletionData): Response {
        val chatId = "chatcmpl-${UUID.randomUUID().toString().take(12)}"
        val stream = PipedInputStream(STREAM_BUFFER_SIZE)
        val output = PipedOutputStream(stream)
        val task = FutureTask { complete() }
        Thread({
            try {
                writeSseChunk(output, chatId, model, JSONObject().put("role", "assistant").put("content", ""))
                Thread(task, "api-bridge-generation").apply { isDaemon = true; start() }
                lateinit var done: CompletionData
                while (true) {
                    try {
                        done = task.get(10, TimeUnit.SECONDS)
                        break
                    } catch (_: TimeoutException) {
                        writeSseChunk(output, chatId, model, JSONObject().put("content", ""))
                    }
                }
                val parsed = done.parsed
                val promptTokens = done.promptTokens
                val completionTokens = done.completionTokens
                val content = parsed?.remainingText?.trim().orEmpty().ifEmpty { if (parsed == null) done.result else "" }
                if (parsed != null && parsed.toolCalls.isNotEmpty()) {
                    writeContentChunks(output, chatId, model, content)
                    val calls = JSONArray()
                    parsed.toolCalls.forEachIndexed { index, call ->
                        calls.put(
                            JSONObject()
                                .put("index", index)
                                .put("id", call.id)
                                .put("type", "function")
                                .put(
                                    "function",
                                    JSONObject().put("name", call.name).put("arguments", call.arguments)
                                )
                        )
                    }
                    writeSseChunk(output, chatId, model, JSONObject().put("tool_calls", calls))
                } else {
                    writeContentChunks(output, chatId, model, content)
                }

                val finalChunk = JSONObject()
                    .put("id", chatId)
                    .put("object", "chat.completion.chunk")
                    .put("created", System.currentTimeMillis() / 1000)
                    .put("model", model)
                    .put(
                        "choices",
                        JSONArray().put(
                            JSONObject()
                                .put("index", 0)
                                .put("delta", JSONObject())
                                .put("finish_reason", if (parsed == null) "stop" else "tool_calls")
                        )
                    )
                    .put(
                        "usage",
                        JSONObject()
                            .put("prompt_tokens", promptTokens)
                            .put("completion_tokens", completionTokens)
                            .put("total_tokens", promptTokens + completionTokens)
                    )
                output.write("data: ${finalChunk}\n\ndata: [DONE]\n\n".toByteArray(Charsets.UTF_8))
                output.flush()
            } catch (e: ExecutionException) {
                val cause = e.cause
                val code = (cause as? AutomationException)?.errorCode ?: "internal_error"
                val message = if (cause is AutomationException) cause.message else "La requête n’a pas pu être traitée."
                try {
                    output.write(("data: " + JSONObject().put("error", JSONObject().put("code", code).put("message", message)) + "\n\ndata: [DONE]\n\n").toByteArray(Charsets.UTF_8))
                    output.flush()
                } catch (_: Exception) { }
            } catch (e: Exception) {
                Log.i(TAG, "SSE client disconnected")
            } finally {
                task.cancel(true)
                try {
                    output.close()
                } catch (_: Exception) {
                    Unit
                }
            }
        }, "api-bridge-sse").apply {
            isDaemon = true
            start()
        }
        return newChunkedResponse(Response.Status.OK, MIME_EVENT_STREAM, stream)
    }

    private fun writeSseChunk(output: PipedOutputStream, id: String, model: String, delta: JSONObject) {
        val chunk = JSONObject()
            .put("id", id)
            .put("object", "chat.completion.chunk")
            .put("created", System.currentTimeMillis() / 1000)
            .put("model", model)
            .put(
                "choices",
                JSONArray().put(
                    JSONObject()
                        .put("index", 0)
                        .put("delta", delta)
                        .put("finish_reason", JSONObject.NULL)
                )
            )
        output.write("data: $chunk\n\n".toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun writeContentChunks(output: PipedOutputStream, id: String, model: String, content: String) {
        var offset = 0
        while (offset < content.length) {
            val codePoints = minOf(STREAM_CHUNK_CHARS, content.codePointCount(offset, content.length))
            val nextOffset = content.offsetByCodePoints(offset, codePoints)
            writeSseChunk(output, id, model, JSONObject().put("content", content.substring(offset, nextOffset)))
            offset = nextOffset
        }
    }

    private fun handleModelsRequest(): Response {
        val models = JSONArray()
        providerRepository.getAll().forEachIndexed { index, config ->
            models.put(
                JSONObject()
                    .put("id", config.provider)
                    .put("object", "model")
                    .put("created", System.currentTimeMillis() / 1000 - index)
                    .put("owned_by", config.packageName)
            )
        }
        return newFixedLengthResponse(
            Response.Status.OK,
            MIME_JSON,
            JSONObject().put("object", "list").put("data", models).toString()
        )
    }

    private fun handleStatusRequest(): Response {
        val activeName = providerRepository.getActiveName()
        val config = activeName?.let(providerRepository::get)
        val service = ClickAccessibilityService.instance
        val currentPackage = if (service != null) {
            runBlocking(Dispatchers.Main.immediate) { service.currentPackageName() }
        } else {
            null
        }
        val obj = JSONObject()
            .put("status", if (service != null) "Ready" else "Accessibility Service Disabled")
            .put("accessibility_enabled", service != null)
            .put("active_provider", activeName ?: JSONObject.NULL)
            .put("target_package", config?.packageName ?: JSONObject.NULL)
            .put("target_foreground", config != null && currentPackage == config.packageName)
            .put("queue_busy", AutomationCoordinator.isRunning)
            .put("current_package", currentPackage ?: JSONObject.NULL)
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, obj.toString())
    }

    private fun handleStatsRequest(): Response {
        val stats = JSONObject()
            .put("total_requests", totalRequests.get())
            .put("total_prompt_tokens", totalPromptTokens.get())
            .put("total_completion_tokens", totalCompletionTokens.get())
            .put("stream_enabled", streamEnabled)
            .put("uptime_seconds", uptimeSeconds())
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, stats.toString())
    }

    private fun handleConfigRequest(session: IHTTPSession): Response {
        if (session.method == Method.POST) {
            val body = parseJsonBody(session)
            if (body.has("stream_enabled")) {
                streamEnabled = body.getBoolean("stream_enabled")
                appContext.getSharedPreferences(SETTINGS_PREFERENCES, Context.MODE_PRIVATE).edit()
                    .putBoolean(STREAM_ENABLED_KEY, streamEnabled)
                    .apply()
            }
            if (body.has("active_provider")) {
                val name = body.getString("active_provider")
                providerRepository.setActive(name)
            }
        } else if (session.method != Method.GET) {
            return methodNotAllowed("GET, POST")
        }
        val config = JSONObject()
            .put("stream_enabled", streamEnabled)
            .put("api_key_required", apiKeyRequired())
            .put("active_provider", providerRepository.getActiveName() ?: JSONObject.NULL)
            .put("providers", JSONArray().apply {
                providerRepository.getAll().forEach { put(it.provider) }
            })
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, config.toString())
    }

    private fun handleNetworkRequest(): Response {
        val wifi = NetworkHelper.getWifiIPAddress(appContext)
        val obj = JSONObject()
            .put("port", port)
            .put("wifi_ipv4", wifi ?: JSONObject.NULL)
            .put("wifi_api", wifi?.let { "http://$it:$port/v1" } ?: JSONObject.NULL)
            .put("wifi_web", wifi?.let { "http://$it:$port/" } ?: JSONObject.NULL)
            .put("usb_api", "http://127.0.0.1:$port/v1")
            .put("usb_web", "http://127.0.0.1:$port/")
            .put("adb_command", "adb forward tcp:$port tcp:$port")
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, obj.toString())
    }

    private fun handleAskRequest(session: IHTTPSession): Response {
        val question = if (session.method == Method.POST) {
            val body = parseJsonBody(session)
            body.optString("q", body.optString("question"))
        } else {
            session.parameters["q"]?.firstOrNull()
                ?: session.parameters["question"]?.firstOrNull().orEmpty()
        }
        if (question.isBlank()) {
            return jsonError(Response.Status.BAD_REQUEST, "invalid_request", "Le paramètre q est requis.")
        }
        val config = providerRepository.get(providerRepository.getActiveName().orEmpty())
            ?: return jsonError(Response.Status.CONFLICT, "provider_missing", "Configure un provider avant d’envoyer une requête.")
        val result = runBlocking(Dispatchers.IO) { coordinator.processQuestion(config, question) }
        return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", result)
    }

    private fun handleStopRequest(): Response {
        val pending = agentIntegrations.hasPendingWork()
        val result = runBlocking(Dispatchers.IO) { coordinator.stopGeneration() }
        val stopped = result.requested || pending
        return newFixedLengthResponse(
            if (stopped) Response.Status.OK else Response.Status.CONFLICT,
            MIME_JSON,
            JSONObject()
                .put("stopped", stopped)
                .put("target_stop_clicked", result.targetStopClicked)
                .put("target_generation_inactive", result.targetGenerationInactive)
                .put("message", when {
                    result.targetGenerationInactive -> "Arrêt demandé ; l’indicateur de génération n’est plus visible."
                    result.targetStopClicked -> "Action Stop envoyée, mais la fin de génération n’a pas été confirmée."
                    stopped -> "Annulation demandée, mais le bouton d’arrêt cible n’a pas été actionné : la génération peut continuer."
                    else -> "Aucune requête active détectée et bouton d’arrêt cible inaccessible."
                })
                .toString()
        )
    }

    private fun handleUploadRequest(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val temporaryFile = files["file"]?.let(::File)
            ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_request", "Aucun fichier fourni.")
        if (!temporaryFile.isFile || temporaryFile.length() > MAX_UPLOAD_BYTES) {
            return jsonError(Response.Status.BAD_REQUEST, "file_too_large", "Le fichier est invalide ou dépasse 20 Mo.")
        }
        val filename = session.parameters["file"]?.firstOrNull()?.take(MAX_FILENAME_LENGTH)
            ?: "upload.bin"
        val uploaded = fileUploader.uploadWithFallback(temporaryFile, filename)
        return if (uploaded.success && uploaded.url != null) {
            newFixedLengthResponse(
                Response.Status.OK,
                MIME_JSON,
                JSONObject().put("success", true).put("url", uploaded.url).toString()
            )
        } else {
            Log.w(TAG, "Upload failed across configured hosts")
            jsonError(Response.Status.INTERNAL_ERROR, "upload_failed", "Le fichier n’a pas pu être transféré.")
        }
    }

    private fun drainBody(session: IHTTPSession) {
        try { session.parseBody(HashMap()) }
        catch (_: Exception) { throw AutomationException("invalid_request", "Impossible de lire le corps de la requête.") }
    }

    private fun parseJsonBody(session: IHTTPSession): JSONObject {
        // NanoHTTPD 2.3.1 otherwise decodes application/json WITHOUT a charset
        // as US-ASCII, corrupting every non-ASCII byte. JSON over HTTP is UTF-8.
        val length = session.headers["content-length"]?.toLongOrNull()
        if (length != null && length > MAX_REQUEST_CHARS) throw AutomationException("request_too_large", "Le corps JSON dépasse 6 Mo.")
        val header = session.headers["content-type"].orEmpty()
        val charset = Regex("(?i)(?:^|;)\\s*charset\\s*=\\s*[\"']?([^;\"'\\s]+)").find(header)?.groupValues?.get(1)
        if (charset != null && !charset.equals("utf-8", true) && !charset.equals("utf8", true)) {
            session.headers["content-type"] = "application/json; charset=UTF-8"
            drainBody(session)
            throw AutomationException("unsupported_parameter", "Le corps JSON doit être encodé en UTF-8.")
        }
        session.headers["content-type"] = "application/json; charset=UTF-8"
        val files = HashMap<String, String>()
        val body = try {
            session.parseBody(files)
            files["postData"].orEmpty()
        } catch (e: Exception) {
            throw AutomationException("invalid_request", "Impossible de lire le corps JSON de la requête.")
        }
        if (body.length > MAX_REQUEST_CHARS) throw AutomationException("request_too_large", "Le corps JSON dépasse 6 Mo.")
        if (body.isBlank()) throw AutomationException("invalid_request", "Le corps JSON est vide.")
        return try {
            JSONObject(body)
        } catch (e: Exception) {
            throw AutomationException("invalid_request", "Le corps de la requête doit être un objet JSON valide.")
        }
    }

    private fun apiKeyRequired(): Boolean = appContext
        .getSharedPreferences(SETTINGS_PREFERENCES, Context.MODE_PRIVATE)
        .getBoolean(API_KEY_REQUIRED_KEY, false)

    private fun authorized(session: IHTTPSession): Boolean {
        if (!apiKeyRequired()) return true
        val authorization = session.headers.entries.firstOrNull { it.key.equals("authorization", ignoreCase = true) }?.value
        val supplied = if (authorization?.startsWith("Bearer ", ignoreCase = true) == true) {
            authorization.substringAfter(' ').trim()
        } else session.headers.entries.firstOrNull { it.key.equals("x-api-key", ignoreCase = true) }?.value?.trim().orEmpty()
        if (supplied.isBlank()) return false
        val expected = apiKeyStore.get()
        if (expected != null && constantTimeEquals(expected, supplied)) return true
        return webKeyStore.list().any { constantTimeEquals(it.getString("key"), supplied) }
    }

    private fun constantTimeEquals(expected: String, supplied: String): Boolean {
        if (expected.length != supplied.length) return false
        var difference = 0
        for (index in expected.indices) difference = difference or (expected[index].code xor supplied[index].code)
        return difference == 0
    }

    private fun isSameOriginOrDirect(session: IHTTPSession): Boolean {
        val origin = session.headers["origin"] ?: return true
        val host = session.headers["host"] ?: return false
        return origin.equals("http://$host", ignoreCase = true) || origin.equals("https://$host", ignoreCase = true)
    }

    private fun isLocalClient(session: IHTTPSession): Boolean {
        return try {
            val address = InetAddress.getByName(session.remoteIpAddress)
            address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress
        } catch (_: Exception) {
            false
        }
    }

    private fun estimateTokens(text: String): Int = maxOf(1, text.length / 4)

    private fun uptimeSeconds(): Long = (System.currentTimeMillis() - startedAt) / 1000

    private fun methodNotAllowed(allowed: String): Response =
        jsonError(Response.Status.METHOD_NOT_ALLOWED, "method_not_allowed", "Méthode autorisée : $allowed.")

    private fun jsonError(status: Response.Status, code: String, message: String): Response {
        val error = JSONObject()
            .put("message", message)
            .put("type", if (code == "authentication_error") "authentication_error" else "api_error")
            .put("code", code)
        return newFixedLengthResponse(
            status,
            MIME_JSON,
            JSONObject().put("error", error).toString()
        )
    }

    private fun addCORSHeaders(response: Response) {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, X-API-Key, X-NemApi-Agent")
        response.addHeader("Access-Control-Expose-Headers", "X-NemApi-Ignored-Parameters")
        response.addHeader("Cache-Control", "no-store")
    }

    companion object {
        private const val TAG = "LocalApiServer"
        private const val SETTINGS_PREFERENCES = "server_settings"
        private const val STREAM_ENABLED_KEY = "stream_enabled"
        private const val API_KEY_REQUIRED_KEY = "api_key_required"
        private const val MAX_REQUEST_CHARS = 6 * 1024 * 1024
        private const val MAX_UPLOAD_BYTES = 20L * 1024 * 1024
        private const val MAX_FILENAME_LENGTH = 180
        private const val STREAM_BUFFER_SIZE = 8 * 1024
        private const val STREAM_CHUNK_CHARS = 15
        private val MIME_JSON = "application/json; charset=utf-8"
        private val MIME_EVENT_STREAM = "text/event-stream; charset=utf-8"
        private val SUPPORTED_ROLES = setOf("system", "developer", "user", "assistant", "tool")
        private val ROLE_LABELS = mapOf(
            "system" to "Instructions système",
            "developer" to "Instructions développeur",
            "user" to "User",
            "assistant" to "Assistant",
            "tool" to "Résultat d’outil"
        )

        val isRunning: Boolean
            get() = AutomationCoordinator.isRunning
    }
}
