package com.ialocalbridge

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import com.ialocalbridge.models.ProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AutomationCoordinator(context: Context) {

    private val appContext = context.applicationContext
    private val providerRepository = ProviderRepository(appContext)

    suspend fun processQuestion(config: ProviderConfig, prompt: String): String = withContext(Dispatchers.IO) {
        if (accepted.incrementAndGet() > MAX_ACCEPTED_REQUESTS) {
            accepted.decrementAndGet()
            throw AutomationException("queue_full", "Une autre requête utilise déjà l’application cible.")
        }

        var acquired = false
        try {
            acquired = withTimeoutOrNull(QUEUE_WAIT_MS) {
                semaphore.acquire()
                true
            } ?: false
            if (!acquired) {
                throw AutomationException("queue_timeout", "La requête a expiré en attendant la file d’automatisation.")
            }
            running.set(true)
            cancelled.set(false)
            execute(config, prompt)
        } finally {
            if (acquired) {
                running.set(false)
                cancelled.set(false)
                semaphore.release()
            }
            accepted.decrementAndGet()
        }
    }

    suspend fun stopGeneration(): Boolean = withContext(Dispatchers.Main.immediate) {
        cancelled.set(true)
        val config = providerRepository.get(providerRepository.getActiveName() ?: return@withContext false)
            ?: return@withContext false
        val service = ClickAccessibilityService.instance ?: return@withContext false
        service.configureTarget(config.packageName)
        val stopSelectors = config.selectors.stop
        if (stopSelectors.isEmpty()) return@withContext false

        val node = service.findUniqueNode(config.packageName, stopSelectors, "stop")
        try {
            service.click(node, "stop")
            true
        } finally {
            node.recycle()
        }
    }

    private suspend fun execute(config: ProviderConfig, prompt: String): String {
        if (prompt.isBlank()) throw AutomationException("invalid_request", "Le message utilisateur est vide.")
        if (prompt.length > MAX_PROMPT_CHARS) {
            throw AutomationException("request_too_large", "Le message dépasse la limite autorisée.")
        }
        val service = ClickAccessibilityService.instance
            ?: throw AutomationException("accessibility_disabled", "Active le service d’accessibilité d’API Bridge.")

        withContext(Dispatchers.Main.immediate) {
            service.configureTarget(config.packageName)
        }
        val controls = awaitControls(service, config)
        val initialAnswer = withContext(Dispatchers.Main.immediate) {
            val input = controls.first
            val send = controls.second
            try {
                val previous = try {
                    service.readLatestText(config.packageName, config.selectors.assistantMessage, "assistant_message")
                } catch (e: AutomationException) {
                    if (e.errorCode == "selector_missing") "" else throw e
                }
                service.setText(input, prompt)
                service.click(send, "send")
                previous
            } finally {
                input.recycle()
                send.recycle()
            }
        }

        val startedAt = System.currentTimeMillis()
        var lastObserved = ""
        var stablePolls = 0
        var hasNewAnswer = false

        while (System.currentTimeMillis() - startedAt < config.timeouts.responseMs) {
            if (cancelled.get()) {
                throw AutomationException("cancelled", "La requête a été arrêtée.")
            }
            delay(config.timeouts.pollIntervalMs)

            val (answer, isGenerating) = withContext(Dispatchers.Main.immediate) {
                service.requireTargetForeground(config.packageName)
                val currentAnswer = try {
                    service.readLatestText(
                        config.packageName,
                        config.selectors.assistantMessage,
                        "assistant_message"
                    )
                } catch (e: AutomationException) {
                    if (e.errorCode == "selector_missing") "" else throw e
                }
                val active = service.hasAnyMatch(config.packageName, config.selectors.generating)
                currentAnswer to active
            }

            if (answer.isNotBlank() && answer != initialAnswer) hasNewAnswer = true
            if (hasNewAnswer && answer.isNotBlank()) {
                if (answer == lastObserved) {
                    stablePolls++
                } else {
                    lastObserved = answer
                    stablePolls = 0
                }
                if (!isGenerating && stablePolls >= config.timeouts.stablePolls) {
                    return answer
                }
            }
        }

        throw AutomationException(
            "response_timeout",
            "Aucune réponse complète détectée après ${config.timeouts.responseMs / 1000} secondes."
        )
    }

    private suspend fun awaitControls(
        service: ClickAccessibilityService,
        config: ProviderConfig
    ): Pair<AccessibilityNodeInfo, AccessibilityNodeInfo> {
        val deadline = System.currentTimeMillis() + config.timeouts.elementMs
        while (true) {
            if (cancelled.get()) throw AutomationException("cancelled", "La requête a été arrêtée.")
            val controls = withContext(Dispatchers.Main.immediate) {
                service.requireTargetForeground(config.packageName)
                val input = try {
                    service.findUniqueNode(config.packageName, config.selectors.input, "input")
                } catch (e: AutomationException) {
                    if (e.errorCode == "selector_missing") null else throw e
                }
                val send = try {
                    service.findUniqueNode(config.packageName, config.selectors.send, "send")
                } catch (e: AutomationException) {
                    if (e.errorCode == "selector_missing") null else {
                        input?.recycle()
                        throw e
                    }
                }
                if (input != null && send != null) input to send else {
                    input?.recycle()
                    send?.recycle()
                    null
                }
            }
            if (controls != null) return controls
            if (System.currentTimeMillis() >= deadline) {
                throw AutomationException(
                    "selector_missing",
                    "Les sélecteurs input/send n’ont pas trouvé de contrôle après ${config.timeouts.elementMs / 1000} secondes."
                )
            }
            delay(config.timeouts.pollIntervalMs)
        }
    }

    companion object {
        private const val MAX_PROMPT_CHARS = 100_000
        private const val MAX_ACCEPTED_REQUESTS = 2
        private const val QUEUE_WAIT_MS = 15_000L

        private val running = AtomicBoolean(false)
        private val cancelled = AtomicBoolean(false)
        private val accepted = AtomicInteger(0)
        private val semaphore = Semaphore(1)

        val isRunning: Boolean
            get() = running.get()

        fun stopCurrentJob() {
            cancelled.set(true)
        }
    }
}
