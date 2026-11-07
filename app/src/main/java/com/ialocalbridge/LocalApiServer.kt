package com.ialocalbridge

import android.content.Context
import android.util.Log
import com.ialocalbridge.utils.FileUploader
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
import java.util.concurrent.atomic.AtomicLong

class LocalApiServer(private val port: Int, context: Context) : NanoHTTPD("0.0.0.0", port) {

    private val appContext = context.applicationContext
    private val coordinator = AutomationCoordinator(appContext)
    private val providerRepository = ProviderRepository(appContext)
    private val apiKeyStore = ApiKeyStore(appContext)
    private val fileUploader = FileUploader()

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
        if (session.method == Method.OPTIONS) {
            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "").also(::addCORSHeaders)
        }
        if (uri == "/" || uri == "/index.html") {
            return newFixedLengthResponse(
                Response.Status.OK,
                MIME_HTML,
                WebInterface.getHtml()
            ).also(::addCORSHeaders)
        }
        if (uri == "/health" && session.method == Method.GET) {
            val health = JSONObject()
                .put("status", "ok")
                .put("uptime_seconds", uptimeSeconds())
            return newFixedLengthResponse(Response.Status.OK, MIME_JSON, health.toString()).also(::addCORSHeaders)
        }
        if (!authorized(session)) {
            return jsonError(Response.Status.UNAUTHORIZED, "authentication_error", "Clé API absente ou invalide.")
                .also(::addCORSHeaders)
        }

        val response = try {
            when (uri) {
                "/v1/models" -> if (session.method == Method.GET) handleModelsRequest()
                    else methodNotAllowed("GET")
                "/v1/chat/completions" -> if (session.method == Method.POST) handleChatCompletions(session)
                    else methodNotAllowed("POST")
                "/status" -> if (session.method == Method.GET) handleStatusRequest()
                    else methodNotAllowed("GET")
                "/stats" -> if (session.method == Method.GET) handleStatsRequest()
                    else methodNotAllowed("GET")
                "/config" -> handleConfigRequest(session)
                "/ask" -> if (session.method == Method.GET || session.method == Method.POST) handleAskRequest(session)
                    else methodNotAllowed("GET, POST")
                "/upload" -> if (session.method == Method.POST) handleUploadRequest(session)
                    else methodNotAllowed("POST")
                "/stop" -> if (session.method == Method.GET || session.method == Method.POST) handleStopRequest()
                    else methodNotAllowed("GET, POST")
                else -> jsonError(Response.Status.NOT_FOUND, "not_found", "Endpoint introuvable.")
            }
        } catch (e: AutomationException) {
            val status = when (e.errorCode) {
                "queue_full", "queue_timeout" -> Response.Status.SERVICE_UNAVAILABLE
                "accessibility_disabled", "wrong_app", "selector_missing", "selector_ambiguous",
                "action_failed", "response_timeout", "cancelled" -> Response.Status.CONFLICT
                "response_too_large" -> Response.Status.PAYLOAD_TOO_LARGE
                else -> Response.Status.BAD_REQUEST
            }
            jsonError(status, e.errorCode, e.message)
        } catch (e: Exception) {
            Log.e(TAG, "Request failed: ${e.javaClass.simpleName}")
            jsonError(Response.Status.INTERNAL_ERROR, "internal_error", "La requête n’a pas pu être traitée.")
        }
        return response.also(::addCORSHeaders)
    }

    private fun handleChatCompletions(session: IHTTPSession): Response {
        val request = parseJsonBody(session)
        val messages = request.optJSONArray("messages")
            ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_request", "Le champ messages est requis.")
        if (messages.length() == 0) {
            return jsonError(Response.Status.BAD_REQUEST, "invalid_request", "Le champ messages ne peut pas être vide.")
        }

        val modelName = request.optString("model").ifBlank { providerRepository.getActiveName().orEmpty() }
        val config = providerRepository.get(modelName)
            ?: return jsonError(Response.Status.BAD_REQUEST, "model_not_found", "Le modèle '$modelName' n’est pas configuré.")

        val messagesPrompt = buildPrompt(messages, config.maxHistoryChars)
        val toolsValue = request.opt("tools")
        val tools = when (toolsValue) {
            null, JSONObject.NULL -> null
            is JSONArray -> toolsValue
            else -> throw AutomationException("invalid_request", "Le champ tools doit être un tableau.")
        }
        val toolChoice = request.opt("tool_choice")
        val toolInstruction = buildToolInstruction(tools, toolChoice)
        val prompt = if (toolInstruction.isEmpty()) messagesPrompt else "$messagesPrompt\n\n$toolInstruction"
        val requestedStream = request.optBoolean("stream", false)
        if (requestedStream && !streamEnabled) {
            return jsonError(Response.Status.BAD_REQUEST, "streaming_disabled", "Le streaming est désactivé dans la configuration.")
        }

        val result = runBlocking(Dispatchers.IO) {
            coordinator.processQuestion(config, prompt)
        }
        totalRequests.incrementAndGet()
        val promptTokens = estimateTokens(prompt)
        val completionTokens = estimateTokens(result)
        totalPromptTokens.addAndGet(promptTokens.toLong())
        totalCompletionTokens.addAndGet(completionTokens.toLong())

        val parsed = if (toolChoice == "none") null else ToolParser.parseToolCalls(result)
        return if (requestedStream) {
            buildStreamResponse(modelName, result, parsed, promptTokens, completionTokens)
        } else {
            buildJsonResponse(modelName, result, parsed, promptTokens, completionTokens)
        }
    }

    private fun buildToolInstruction(tools: JSONArray?, choice: Any?): String {
        if (tools == null || tools.length() == 0) {
            if (choice != null && choice != JSONObject.NULL && choice != "auto" && choice != "none") {
                throw AutomationException("invalid_request", "tool_choice nécessite un tableau tools non vide.")
            }
            return ""
        }

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
        val base = try {
            ToolParser.buildToolCallPrompt(tools)
        } catch (_: Exception) {
            throw AutomationException("invalid_request", "La définition tools est invalide.")
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

    private fun buildPrompt(messages: JSONArray, maxHistoryChars: Int): String {
        val normalized = mutableListOf<Pair<String, String>>()
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
            val toolCalls = message.optJSONArray("tool_calls")?.toString()?.let { "\nAppels d’outils précédents : $it" }.orEmpty()
            normalized.add(role to "[${ROLE_LABELS[role]}]\n$text$toolCalls")
        }
        val latestUserIndex = normalized.indexOfLast { it.first == "user" }
        if (latestUserIndex < 0) {
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
                if (index >= latestUserIndex) {
                    throw AutomationException(
                        "request_too_large",
                        "L’historique courant ne tient pas dans la limite sans couper le dernier message utilisateur."
                    )
                }
                break
            }
            retained.addFirst(entry)
            retainedChars += entry.length
        }
        return retained.joinToString("\n\n")
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

    private fun buildStreamResponse(
        model: String,
        rawResult: String,
        parsed: ToolParser.ToolCallResult?,
        promptTokens: Int,
        completionTokens: Int
    ): Response {
        val chatId = "chatcmpl-${UUID.randomUUID().toString().take(12)}"
        val content = parsed?.remainingText?.trim().orEmpty().ifEmpty { if (parsed == null) rawResult else "" }
        val stream = PipedInputStream(STREAM_BUFFER_SIZE)
        val output = PipedOutputStream(stream)
        Thread({
            try {
                writeSseChunk(output, chatId, model, JSONObject().put("role", "assistant").put("content", ""))
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
            } catch (e: Exception) {
                Log.i(TAG, "SSE client disconnected")
            } finally {
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
            .put("active_provider", providerRepository.getActiveName() ?: JSONObject.NULL)
            .put("providers", JSONArray().apply {
                providerRepository.getAll().forEach { put(it.provider) }
            })
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, config.toString())
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
        val stopped = runBlocking(Dispatchers.IO) { coordinator.stopGeneration() }
        return newFixedLengthResponse(
            if (stopped) Response.Status.OK else Response.Status.CONFLICT,
            MIME_JSON,
            JSONObject()
                .put("stopped", stopped)
                .put("message", if (stopped) "Arrêt demandé." else "Aucun contrôle stop configuré ou aucune requête active.")
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

    private fun parseJsonBody(session: IHTTPSession): JSONObject {
        val files = HashMap<String, String>()
        val body = try {
            session.parseBody(files)
            files["postData"].orEmpty()
        } catch (e: Exception) {
            throw AutomationException("invalid_request", "Impossible de lire le corps JSON de la requête.")
        }
        if (body.isBlank() || body.length > MAX_REQUEST_CHARS) {
            throw AutomationException("invalid_request", "Le corps JSON est vide ou dépasse 512 Ko.")
        }
        return try {
            JSONObject(body)
        } catch (e: Exception) {
            throw AutomationException("invalid_request", "Le corps de la requête doit être un objet JSON valide.")
        }
    }

    private fun authorized(session: IHTTPSession): Boolean {
        val expected = apiKeyStore.get()
            ?: throw IllegalStateException("La clé API locale n’est pas configurée.")
        val authorization = session.headers.entries
            .firstOrNull { it.key.equals("authorization", ignoreCase = true) }
            ?.value ?: return false
        if (!authorization.startsWith("Bearer ", ignoreCase = true)) return false
        val supplied = authorization.substringAfter(' ').trim()
        return constantTimeEquals(expected, supplied)
    }

    private fun constantTimeEquals(expected: String, supplied: String): Boolean {
        if (expected.length != supplied.length) return false
        var difference = 0
        for (index in expected.indices) difference = difference or (expected[index].code xor supplied[index].code)
        return difference == 0
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
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
        response.addHeader("Cache-Control", "no-store")
    }

    companion object {
        private const val TAG = "LocalApiServer"
        private const val SETTINGS_PREFERENCES = "server_settings"
        private const val STREAM_ENABLED_KEY = "stream_enabled"
        private const val MAX_REQUEST_CHARS = 512 * 1024
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
            "user" to "Utilisateur",
            "assistant" to "Assistant",
            "tool" to "Résultat d’outil"
        )

        val isRunning: Boolean
            get() = AutomationCoordinator.isRunning
    }
}
