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
        // A short conversation can have no scroll container at all. If so,
        // verify new prompt/answer ordering before accepting any reply and
        // reject an existing answer unless it is wholly inside the viewport.
        val initialScrollMissing = scrollToEnd(service, config, allowNonScrolling = true)
        val initialAnswer = withContext(Dispatchers.Main.immediate) {
            try {
                service.readLatestText(config.packageName, config.selectors.assistantMessage, "assistant_message")
            } catch (e: AutomationException) {
                if (e.errorCode == "selector_missing") "" else throw e
            }
        }
        val baseline = if (config.selectors.detectIdenticalResponses || initialScrollMissing) withContext(Dispatchers.Main.immediate) {
            service.responseBaseline(config.packageName, config.selectors.assistantMessage, prompt)
        } else null
        try {
            val input = awaitInput(service, config)
            withContext(Dispatchers.Main.immediate) {
                try { service.setText(input, prompt) } finally { input.recycle() }
            }
            // Some apps only create the send control after the input is non-empty.
            val send = awaitNode(service, config, config.selectors.send, "send")
            withContext(Dispatchers.Main.immediate) {
                try {
                    if (service.hasAnyMatch(config.packageName, config.selectors.generating)) {
                        throw AutomationException("user_intervention_required", "Une génération est déjà en cours dans l’application cible.")
                    }
                    service.click(send, "send")
                } finally { send.recycle() }
            }

            val startedAt = System.currentTimeMillis()
            var lastObserved = ""
            var stablePolls = 0
            var sawGenerating = false

            while (System.currentTimeMillis() - startedAt < config.timeouts.responseMs) {
                if (cancelled.get()) {
                    throw AutomationException("cancelled", "La requête a été arrêtée.")
                }
                delay(config.timeouts.pollIntervalMs)

                scrollToEnd(service, config, allowNonScrolling = initialScrollMissing, validateVisibleAnswer = false)
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

                sawGenerating = sawGenerating || isGenerating
                // A shrinking view of the previous response is not evidence of a new answer.
                val resemblesPrevious = answer.isNotBlank() && initialAnswer.isNotBlank() && initialAnswer.contains(answer)
                val newByIdentity = if ((resemblesPrevious || initialScrollMissing) && baseline != null && answer.isNotBlank()) {
                    withContext(Dispatchers.Main.immediate) {
                        service.hasNewAnswerAfterPrompt(config.packageName, config.selectors.assistantMessage, prompt, baseline) ||
                            // Generation was absent just before the send click; an
                            // observed generation and a new response identity can
                            // establish freshness even if the prompt was virtualized.
                            (sawGenerating && service.hasNewAnswerIdentity(config.packageName, config.selectors.assistantMessage, baseline))
                    }
                } else false
                val candidateIsNew = ResponseIdentityPolicy.isNew(answer, initialAnswer, initialScrollMissing, newByIdentity)
                if (candidateIsNew) {
                    if (answer == lastObserved) {
                        stablePolls++
                    } else {
                        lastObserved = answer
                        stablePolls = 0
                    }
                    if (!isGenerating && stablePolls >= config.timeouts.stablePolls) {
                        if (config.selectors.copyResponse.isNotEmpty()) {
                            return service.copyLatestResponse(config)
                        }
                        return readCompleteResponse(service, config, initialScrollMissing)
                    }
                } else {
                    lastObserved = ""
                    stablePolls = 0
                }
            }

            throw AutomationException(
                "response_timeout",
                "Aucune réponse complète détectée après ${config.timeouts.responseMs / 1000} secondes."
            )
        } finally { baseline?.recycle() }
    }

    private suspend fun readCompleteResponse(service: ClickAccessibilityService, config: ProviderConfig, startedWithoutScroll: Boolean): String {
        val (container, selector) = withContext(Dispatchers.Main.immediate) {
            service.latestResponseContainer(config.packageName, config.selectors.assistantMessage, "assistant_message")
        }
        try {
            var fragments = withContext(Dispatchers.Main.immediate) {
                service.responseTextParts(container)
            }
            if (!selector.textOnly) {
                return withContext(Dispatchers.Main.immediate) {
                    service.readLatestText(config.packageName, listOf(selector), "assistant_message", requireComplete = true)
                }
            }
            if (!selector.rejectClipped) return fragments.joinToString("\n").trim()
            // Some chat UIs expose no scrollable node at all until the conversation
            // overflows. Accept only a wholly visible response inside a verifiable
            // multi-child viewport; otherwise preserve the fail-closed behavior.
            if (startedWithoutScroll && withContext(Dispatchers.Main.immediate) {
                    service.isWhollyVisibleInNonScrollingViewport(container)
                }) return fragments.joinToString("\n").trim()
            if (!withContext(Dispatchers.Main.immediate) { service.isClippedAtScrollStart(container) }) {
                return fragments.joinToString("\n").trim()
            }
            if (!selector.reconstructClipped) throw AutomationException("response_incomplete", "Le début de la réponse est hors de la zone accessible.")

            repeat(40) {
                if (cancelled.get()) throw AutomationException("cancelled", "La requête a été arrêtée.")
                val singleBefore = if (fragments.size == 1) withContext(Dispatchers.Main.immediate) {
                    service.findSingleTextNode(container)
                } else null
                try {
                    val moved = withContext(Dispatchers.Main.immediate) {
                        service.requireTargetForeground(config.packageName)
                        if (config.selectors.allowReconstructionGesture) {
                            withTimeoutOrNull(3_000) { service.scrollBackwardShort(config.packageName, config.selectors.scrollToEnd) } ?: false
                        } else {
                            service.scrollBackward(config.packageName, config.selectors.scrollToEnd)
                        }
                    }
                    if (!moved) throw AutomationException("response_incomplete", "Le début de la réponse ne peut pas être atteint.")
                    delay(400)
                    val snapshot = withContext(Dispatchers.Main.immediate) {
                        if (!container.refresh() || !container.isVisibleToUser || container.packageName?.toString() != config.packageName) {
                            throw AutomationException("response_incomplete", "Le conteneur de réponse a changé pendant la lecture.")
                        }
                        val parts = service.responseTextParts(container)
                        val singleAfter = if (parts.size == 1 && singleBefore != null) service.findSingleTextNode(container) else null
                        val sameNode = try { singleAfter != null && singleAfter == singleBefore } finally { singleAfter?.recycle() }
                        Triple(parts, service.isClippedAtScrollStart(container), sameNode)
                    }
                    fragments = ResponseFragmentStitcher.prepend(snapshot.first, fragments, snapshot.third)
                    if (!snapshot.second) {
                        val result = fragments.joinToString("\n").trim()
                        if (result.length > MAX_PROMPT_CHARS) throw AutomationException("response_too_large", "La réponse dépasse la limite de lecture.")
                        scrollToEnd(service, config)
                        return result
                    }
                } finally { singleBefore?.recycle() }

            }
            throw AutomationException("response_incomplete", "Limite de défilement atteinte pendant la lecture de la réponse.")
        } finally { container.recycle() }
    }

    /** Returns true if this conversation has no scrollable container. */
    private suspend fun scrollToEnd(
        service: ClickAccessibilityService,
        config: ProviderConfig,
        allowNonScrolling: Boolean = false,
        validateVisibleAnswer: Boolean = true
    ): Boolean {
        if (config.selectors.scrollToEnd.isEmpty()) return false
        repeat(MAX_SCROLL_STEPS) {
            if (cancelled.get()) throw AutomationException("cancelled", "La requête a été arrêtée.")
            val moved = withContext(Dispatchers.Main.immediate) {
                try {
                    service.scrollForward(config.packageName, config.selectors.scrollToEnd)
                } catch (e: AutomationException) {
                    // A short chat may have no scroll container; never ignore
                    // ambiguity or a wrong application. If an existing answer is
                    // visible, require proof that it is not clipped before sending.
                    if (!allowNonScrolling || e.errorCode != "selector_missing") throw e
                    if (validateVisibleAnswer && service.hasAnyMatch(config.packageName, config.selectors.assistantMessage)) {
                        val (container, _) = service.latestResponseContainer(config.packageName, config.selectors.assistantMessage, "assistant_message")
                        val complete = try { service.isWhollyVisibleInNonScrollingViewport(container) } finally { container.recycle() }
                        if (!complete) throw AutomationException("response_incomplete", "Impossible de vérifier la réponse précédente sans conteneur défilant.")
                    }
                    null
                }
            }
            if (moved == null) return true
            if (!moved) return false
            // Let Compose finish the scroll before asking for the next page.
            delay(250)
        }
        throw AutomationException("action_failed", "Limite de défilement atteinte avant la fin de la conversation.")
    }

    private suspend fun awaitInput(service: ClickAccessibilityService, config: ProviderConfig): AccessibilityNodeInfo {
        val found = withContext(Dispatchers.Main.immediate) {
            try { service.findUniqueNode(config.packageName, config.selectors.input, "input") }
            catch (e: AutomationException) { if (e.errorCode == "selector_missing") null else throw e }
        }
        if (found != null) return found
        if (config.selectors.inputActivation.isNotEmpty()) {
            val activation = awaitNode(service, config, config.selectors.inputActivation, "input_activation")
            withContext(Dispatchers.Main.immediate) {
                try {
                    try { service.click(activation, "input_activation") }
                    catch (e: AutomationException) {
                        if (e.errorCode != "action_failed") throw e
                        if (!config.selectors.allowInputGesture) throw AutomationException("user_intervention_required", "Le champ de saisie n’expose pas de clic accessible. Ouvrez-le manuellement puis réessayez.")
                        val completed = withTimeoutOrNull(3_000) {
                            service.activateInputByGesture(config.packageName, activation)
                        } ?: false
                        if (!completed) throw AutomationException("action_failed", "L’activation du champ par geste a échoué.")
                    }
                } finally { activation.recycle() }
            }
        }
        return awaitNode(service, config, config.selectors.input, "input")
    }

    private suspend fun awaitNode(
        service: ClickAccessibilityService,
        config: ProviderConfig,
        selectors: List<com.ialocalbridge.models.UiSelector>,
        label: String
    ): AccessibilityNodeInfo {
        val deadline = System.currentTimeMillis() + config.timeouts.elementMs
        while (true) {
            if (cancelled.get()) throw AutomationException("cancelled", "La requête a été arrêtée.")
            val node = withContext(Dispatchers.Main.immediate) {
                service.requireTargetForeground(config.packageName)
                try { service.findUniqueNode(config.packageName, selectors, label) }
                catch (e: AutomationException) { if (e.errorCode == "selector_missing") null else throw e }
            }
            if (node != null) return node
            if (System.currentTimeMillis() >= deadline) {
                throw AutomationException("selector_missing", "Le contrôle $label est introuvable après ${config.timeouts.elementMs / 1000} secondes.")
            }
            delay(config.timeouts.pollIntervalMs)
        }
    }

    companion object {
        // Finite bound to avoid runaway loops if an app misreports scrolling.
        private const val MAX_SCROLL_STEPS = 600
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
