package com.ialocalbridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.ialocalbridge.models.UiSelector

class ClickAccessibilityService : AccessibilityService() {

    data class AccessibleElement(
        val resourceId: String?,
        val contentDescription: String?,
        val text: String?,
        val className: String?,
        val clickable: Boolean,
        val editable: Boolean
    )

    @Volatile
    private var targetPackageName: String? = null

    override fun onServiceConnected() {
        instance = this
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        targetPackageName = null
        return super.onUnbind(intent)
    }

    fun configureTarget(packageName: String) {
        targetPackageName = packageName
        serviceInfo = serviceInfo.apply { packageNames = arrayOf(packageName) }
    }

    fun currentPackageName(): String? {
        val root = rootInActiveWindow ?: return null
        return try {
            root.packageName?.toString()
        } finally {
            root.recycle()
        }
    }

    fun requireTargetForeground(packageName: String) {
        val root = findTargetRoot(packageName)
            ?: throw AutomationException("wrong_app", "Ouvre l’application cible avant d’envoyer une requête.")
        root.recycle()
    }

    fun findUniqueNode(packageName: String, selectors: List<UiSelector>, label: String): AccessibilityNodeInfo {
        return findNode(packageName, selectors, label, allowMultiple = false)
    }

    fun findLatestNode(packageName: String, selectors: List<UiSelector>, label: String): AccessibilityNodeInfo {
        return findNode(packageName, selectors, label, allowMultiple = true)
    }

    fun hasAnyMatch(packageName: String, selectors: List<UiSelector>): Boolean {
        if (selectors.isEmpty()) return false
        requireTargetForeground(packageName)
        val nodes = readTree(packageName)
        try {
            return selectors.any { selector -> nodes.any { matches(it, selector) } }
        } finally {
            nodes.forEach { it.recycle() }
        }
    }

    fun latestResponseContainer(packageName: String, selectors: List<UiSelector>, label: String): Pair<AccessibilityNodeInfo, UiSelector> {
        for (selector in selectors) {
            val anchor = try {
                findLatestNode(packageName, listOf(selector), label)
            } catch (e: AutomationException) {
                if (e.errorCode == "selector_missing") continue else throw e
            }
            var node = anchor
            try {
                repeat(selector.ancestorLevels) {
                    val parent = node.parent ?: throw AutomationException("selector_missing", "Le conteneur parent de $label est introuvable.")
                    node.recycle()
                    node = parent
                }
                return node to selector
            } catch (e: Exception) {
                node.recycle()
                throw e
            }
        }
        throw AutomationException("selector_missing", "Aucun élément '$label' ne correspond aux sélecteurs du provider.")
    }

    fun readLatestText(packageName: String, selectors: List<UiSelector>, label: String, requireComplete: Boolean = false): String {
        val (node, selector) = latestResponseContainer(packageName, selectors, label)
        return try {
            if (requireComplete && selector.rejectClipped && isClippedAtScrollStart(node)) {
                throw AutomationException("response_incomplete", "Le début de la réponse est hors de la zone accessible ; lecture complète impossible.")
            }
            val text = StringBuilder()
            appendNodeText(node, text, selector.textOnly)
            text.toString().trim()
        } finally { node.recycle() }
    }

    fun responseTextParts(node: AccessibilityNodeInfo): List<String> {
        val result = mutableListOf<String>()
        fun collect(current: AccessibilityNodeInfo) {
            current.text?.toString()?.takeIf(String::isNotBlank)?.let { result.add(it) }
            if (result.sumOf { it.length } > MAX_RESPONSE_TEXT_CHARS) throw AutomationException("response_too_large", "La réponse dépasse la limite de lecture.")
            for (index in 0 until current.childCount) {
                val child = current.getChild(index) ?: continue
                try { collect(child) } finally { child.recycle() }
            }
        }
        collect(node)
        return result
    }

    /** Return a retained node only when exactly one text-bearing node exists. */
    fun findSingleTextNode(container: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        var count = 0
        fun visit(node: AccessibilityNodeInfo) {
            if (!node.text.isNullOrBlank()) {
                count++
                if (count == 1) found = AccessibilityNodeInfo.obtain(node)
            }
            if (count > 1) return
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try { visit(child) } finally { child.recycle() }
                if (count > 1) break
            }
        }
        visit(container)
        if (count != 1) { found?.recycle(); return null }
        return found
    }

    fun isClippedAtScrollStart(node: AccessibilityNodeInfo): Boolean {
        val messageBounds = android.graphics.Rect()
        node.getBoundsInScreen(messageBounds)
        var parent = node.parent
        while (parent != null) {
            val current = parent
            try {
                if (current.isScrollable) {
                    val scrollBounds = android.graphics.Rect()
                    current.getBoundsInScreen(scrollBounds)
                    return messageBounds.top <= scrollBounds.top
                }
                parent = current.parent
            } finally { current.recycle() }
        }
        // A configured clipping check must fail closed if its scroll container is unavailable.
        throw AutomationException("response_incomplete", "Conteneur défilant introuvable pour vérifier la réponse.")
    }

    /**
     * Fail closed when a response is clipped or there is no meaningful viewport.
     * Only used when the current conversation exposes no scrollable container.
     */
    fun isWhollyVisibleInNonScrollingViewport(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser) return false
        val message = Rect().also { node.getBoundsInScreen(it) }
        if (message.isEmpty) return false
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.getRealMetrics(metrics)
        if (message.left < 0 || message.top < 0 || message.right > metrics.widthPixels || message.bottom > metrics.heightPixels) return false
        var candidate: Rect? = null
        var parent = node.parent
        while (parent != null) {
            val current = parent
            try {
                if (current.isScrollable) return false
                val bounds = Rect().also { current.getBoundsInScreen(it) }
                if (candidate == null && current.isVisibleToUser && current.childCount > 1 &&
                    bounds.height() > message.height() && bounds.contains(message)) {
                    candidate = bounds
                }
                parent = current.parent
            } finally { current.recycle() }
        }
        val viewport = candidate ?: return false
        return ViewportVisibility.strictlyInside(
            ViewportBounds(message.left, message.top, message.right, message.bottom),
            ViewportBounds(viewport.left, viewport.top, viewport.right, viewport.bottom),
            metrics.widthPixels, metrics.heightPixels
        )
    }

    fun scrollForward(packageName: String, selectors: List<UiSelector>): Boolean =
        scroll(packageName, selectors, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)

    fun scrollBackward(packageName: String, selectors: List<UiSelector>): Boolean =
        scroll(packageName, selectors, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)

    private fun scroll(packageName: String, selectors: List<UiSelector>, action: Int): Boolean {
        if (selectors.isEmpty()) return false
        val node = findUniqueNode(packageName, selectors, "scroll_to_end")
        return try {
            if (!node.isScrollable || !node.isEnabled) throw AutomationException("action_failed", "La zone de conversation ne peut pas défiler.")
            node.performAction(action)
        } finally { node.recycle() }
    }

    class ResponseBaseline(val answer: AccessibilityNodeInfo?, val prompt: AccessibilityNodeInfo?) {
        fun recycle() { answer?.recycle(); prompt?.recycle() }
    }

    fun responseBaseline(packageName: String, answerSelectors: List<UiSelector>, prompt: String): ResponseBaseline {
        val answer = try { findLatestNode(packageName, answerSelectors, "assistant_message") }
            catch (e: AutomationException) { if (e.errorCode == "selector_missing") null else throw e }
        return try {
            val input = try { findLatestNode(packageName, listOf(UiSelector("text", prompt)), "user_message") }
                catch (e: AutomationException) { if (e.errorCode == "selector_missing") null else throw e }
            ResponseBaseline(answer, input)
        } catch (e: Exception) { answer?.recycle(); throw e }
    }

    /** The submitted prompt is present as a new, non-editable visible node. */
    fun hasNewSubmittedPrompt(packageName: String, prompt: String, baseline: ResponseBaseline): Boolean {
        val nodes = readTree(packageName)
        return try {
            nodes.any { it.isVisibleToUser && !it.isEditable && it.text?.toString() == prompt &&
                (baseline.prompt == null || it != baseline.prompt) }
        } finally { nodes.forEach { it.recycle() } }
    }

    fun hasNewAnswerIdentity(packageName: String, selectors: List<UiSelector>, baseline: ResponseBaseline): Boolean {
        val answer = try { findLatestNode(packageName, selectors, "assistant_message") }
            catch (e: AutomationException) { if (e.errorCode == "selector_missing") return false else throw e }
        return try { baseline.answer == null || answer != baseline.answer } finally { answer.recycle() }
    }

    fun hasNewAnswerAfterPrompt(packageName: String, answerSelectors: List<UiSelector>, prompt: String, baseline: ResponseBaseline): Boolean {
        val nodes = readTree(packageName)
        return try {
            val promptIndex = nodes.indexOfLast { it.isVisibleToUser && it.text?.toString() == prompt }
            if (promptIndex < 0 || (baseline.prompt != null && nodes[promptIndex] == baseline.prompt)) return false
            var answerIndex = -1
            for (selector in answerSelectors) {
                answerIndex = nodes.indexOfLast { matches(it, selector) }
                if (answerIndex >= 0) break
            }
            answerIndex > promptIndex && (baseline.answer == null || nodes[answerIndex] != baseline.answer)
        } finally { nodes.forEach { it.recycle() } }
    }

    fun inspectAccessibleElements(packageName: String, limit: Int = 150): List<AccessibleElement> {
        requireTargetForeground(packageName)
        val nodes = readTree(packageName)
        return try {
            nodes.asSequence()
                .filter { it.isVisibleToUser }
                .map { node ->
                    AccessibleElement(
                        resourceId = node.viewIdResourceName?.takeIf(String::isNotBlank),
                        contentDescription = node.contentDescription?.toString()?.takeIf(String::isNotBlank),
                        text = node.text?.toString()?.takeIf(String::isNotBlank),
                        className = node.className?.toString()?.takeIf(String::isNotBlank),
                        clickable = node.isClickable,
                        editable = node.isEditable
                    )
                }
                .filter { it.resourceId != null || it.contentDescription != null || it.text != null || it.className != null }
                .distinct()
                .take(limit)
                .toList()
        } finally {
            nodes.forEach { it.recycle() }
        }
    }

    fun click(node: AccessibilityNodeInfo, label: String) {
        // Compose often puts the description on an icon inside the actual clickable parent.
        var current = AccessibilityNodeInfo.obtain(node)
        try {
            repeat(9) {
                if (current.isEnabled && current.isClickable) {
                    if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
                    throw AutomationException("action_failed", "Impossible d’actionner le contrôle $label.")
                }
                val parent = current.parent ?: throw AutomationException(
                    "action_failed", "Le contrôle $label n’expose aucune action de clic accessible."
                )
                current.recycle()
                current = parent
            }
            throw AutomationException("action_failed", "Aucun parent cliquable trouvé pour $label.")
        } finally {
            current.recycle()
        }
    }

    // Explicitly opted-in fallback: use the current semantic node bounds, never stored coordinates.
    suspend fun activateInputByGesture(packageName: String, node: AccessibilityNodeInfo): Boolean {
        if (currentPackageName() != packageName || node.packageName?.toString() != packageName || !node.isVisibleToUser || !node.isEnabled) {
            throw AutomationException("wrong_app", "Le champ cible n’est pas visible dans l’application active.")
        }
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.getRealMetrics(metrics)
        if (bounds.isEmpty || bounds.left < 0 || bounds.top < 0 || bounds.right > metrics.widthPixels || bounds.bottom > metrics.heightPixels) {
            throw AutomationException("action_failed", "La zone du champ n’est pas entièrement visible.")
        }
        val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        return suspendCancellableCoroutine { continuation ->
            val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(true)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(false)
                }
            }, null)
            if (!accepted && continuation.isActive) continuation.resume(false)
        }
    }

    // Optional short swipe on the currently identified scrollable region, used only when reconstructing clipped messages.
    suspend fun scrollBackwardShort(packageName: String, selectors: List<UiSelector>): Boolean {
        if (currentPackageName() != packageName) throw AutomationException("wrong_app", "L’application cible n’est pas au premier plan.")
        val node = findUniqueNode(packageName, selectors, "scroll_to_end")
        val bounds = Rect()
        try {
            if (!node.isScrollable || !node.isVisibleToUser || !node.isEnabled) throw AutomationException("action_failed", "La zone de conversation ne peut pas défiler.")
            node.getBoundsInScreen(bounds)
        } finally { node.recycle() }
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.getRealMetrics(metrics)
        if (bounds.isEmpty || bounds.left < 0 || bounds.top < 0 || bounds.right > metrics.widthPixels || bounds.bottom > metrics.heightPixels) {
            throw AutomationException("action_failed", "La zone de conversation n’est pas entièrement visible.")
        }
        val x = bounds.exactCenterX()
        val path = Path().apply {
            moveTo(x, bounds.top + bounds.height() * 0.40f)
            lineTo(x, bounds.top + bounds.height() * 0.62f)
        }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 300)).build()
        return suspendCancellableCoroutine { continuation ->
            val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(true)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(false)
                }
            }, null)
            if (!accepted && continuation.isActive) continuation.resume(false)
        }
    }

    /** Copy a complete answer via a provider-supplied selector. This intentionally
     * overwrites the global clipboard; it is not a private channel on Android. */
    suspend fun copyLatestResponse(config: com.ialocalbridge.models.ProviderConfig): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
        val packageName = config.packageName
        suspend fun awaitTarget() {
            val deadline = android.os.SystemClock.elapsedRealtime() + 3_000L
            while (currentPackageName() != packageName && android.os.SystemClock.elapsedRealtime() < deadline) kotlinx.coroutines.delay(100)
            if (currentPackageName() != packageName) throw AutomationException("wrong_app", "L’application cible n’est plus au premier plan.")
        }
        awaitTarget()
        if (hasAnyMatch(packageName, config.selectors.generating)) throw AutomationException("response_incomplete", "La génération est encore en cours.")
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val marker = "NEM_WAIT_" + java.util.UUID.randomUUID().toString()
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("NemApiBridge", marker))
        // Verify that the marker was actually installed before trusting any
        // later clipboard content, avoiding a stale answer if copying fails.
        readFocusedClipboard(clipboard, marker, expectMarker = true)
        awaitTarget()
        if (hasAnyMatch(packageName, config.selectors.generating)) throw AutomationException("response_incomplete", "La génération est encore en cours.")
        val anchor = findLatestNode(packageName, config.selectors.copyResponse, "copy_response")
        try { click(anchor, "copy_response") } finally { anchor.recycle() }
        val result = readFocusedClipboard(clipboard, marker, expectMarker = false)
        awaitTarget()
        if (result.length > MAX_RESPONSE_TEXT_CHARS) throw AutomationException("response_too_large", "La réponse copiée dépasse la limite de lecture.")
        result
    }

    /** A visible focusable accessibility overlay permits clipboard access on
     * Android 10+ without saved coordinates or a background Activity launch. */
    private suspend fun readFocusedClipboard(clipboard: android.content.ClipboardManager, marker: String, expectMarker: Boolean): String {
        val manager = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        val banner = android.widget.TextView(this).apply {
            text = "NemApiBridge : lecture de la réponse copiée…"
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.rgb(31, 38, 50))
            setPadding(20, 14, 20, 14)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val params = android.view.WindowManager.LayoutParams(
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply { gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL }
        var added = false
        try {
            manager.addView(banner, params)
            added = true
            banner.requestFocus()
            val deadline = android.os.SystemClock.elapsedRealtime() + 8_000L
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                if (banner.hasWindowFocus()) {
                    val clip = clipboard.primaryClip
                    if (clip != null && clip.itemCount == 1) {
                        val text = clip.getItemAt(0).text?.toString()
                        if (!text.isNullOrEmpty() && ((text == marker) == expectMarker)) return text
                    }
                }
                kotlinx.coroutines.delay(100)
            }
            throw AutomationException("response_incomplete", "Impossible de vérifier la copie de la réponse dans le presse-papiers.")
        } finally {
            if (added) manager.removeViewImmediate(banner)
        }
    }

    fun setText(node: AccessibilityNodeInfo, text: String) {
        if (!node.isEnabled || !node.isEditable) {
            throw AutomationException("action_failed", "Le champ de saisie trouvé n’est pas modifiable.")
        }
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
            throw AutomationException("action_failed", "L’application cible a refusé la saisie du texte.")
        }
    }

    private fun findNode(
        packageName: String,
        selectors: List<UiSelector>,
        label: String,
        allowMultiple: Boolean
    ): AccessibilityNodeInfo {
        requireTargetForeground(packageName)
        val nodes = readTree(packageName)
        var selected: AccessibilityNodeInfo? = null
        try {
            for (selector in selectors) {
                val matches = nodes.filter {
                    matches(it, selector) && (label != "scroll_to_end" || isPlausibleConversationScroll(it))
                }
                if (matches.isEmpty()) continue
                if (matches.size > 1 && !allowMultiple) {
                    throw AutomationException(
                        "selector_ambiguous",
                        "Le sélecteur '$label' correspond à ${matches.size} éléments. Corrige sa configuration."
                    )
                }
                selected = if (allowMultiple) matches.last() else matches.single()
                break
            }
            val found = selected ?: throw AutomationException(
                "selector_missing",
                "Aucun élément '$label' ne correspond aux sélecteurs du provider."
            )
            nodes.filter { it !== found }.forEach { it.recycle() }
            return found
        } catch (e: Exception) {
            nodes.filter { it !== selected }.forEach { it.recycle() }
            selected?.recycle()
            throw e
        }
    }

    private fun readTree(packageName: String): List<AccessibilityNodeInfo> {
        val root = findTargetRoot(packageName)
            ?: throw AutomationException("wrong_app", "L’application cible n’est pas visible.")
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, nodes)
        return nodes
    }

    private fun findTargetRoot(packageName: String): AccessibilityNodeInfo? {
        val activeRoot = rootInActiveWindow
        if (activeRoot?.packageName?.toString() == packageName) return activeRoot
        activeRoot?.recycle()

        for (window in windows) {
            val root = try {
                window.root
            } finally {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) window.recycle()
            } ?: continue
            if (root.packageName?.toString() == packageName) return root
            root.recycle()
        }
        return null
    }

    private fun collectNodes(node: AccessibilityNodeInfo, result: MutableList<AccessibilityNodeInfo>) {
        if (result.size >= MAX_ACCESSIBILITY_NODES) {
            node.recycle()
            return
        }
        result.add(node)
        for (index in 0 until node.childCount) {
            if (result.size >= MAX_ACCESSIBILITY_NODES) break
            val child = node.getChild(index) ?: continue
            collectNodes(child, result)
        }
    }

    /** Exclude small horizontal toolbars that also report isScrollable=true. */
    private fun isPlausibleConversationScroll(node: AccessibilityNodeInfo): Boolean {
        if (!node.isScrollable) return false
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.getRealMetrics(metrics)
        return ViewportVisibility.isPlausibleConversationScroll(
            ViewportBounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
            metrics.widthPixels, metrics.heightPixels
        )
    }

    private fun matches(node: AccessibilityNodeInfo, selector: UiSelector): Boolean {
        if (!node.isVisibleToUser) return false
        val actual = when (selector.by) {
            "resource_id" -> node.viewIdResourceName
            "content_description" -> node.contentDescription?.toString()
            "text" -> node.text?.toString()
            "class_name" -> node.className?.toString()
            "scrollable" -> node.isScrollable.toString()
            else -> null
        } ?: return false

        return when (selector.match) {
            "exact" -> actual == selector.value
            "contains" -> actual.contains(selector.value, ignoreCase = true)
            "starts_with" -> actual.startsWith(selector.value, ignoreCase = true)
            else -> false
        }
    }

    private fun appendNodeText(node: AccessibilityNodeInfo, output: StringBuilder, textOnly: Boolean = false) {
        val ownText = node.text?.toString()?.takeIf(String::isNotBlank)
            ?: if (!textOnly) node.contentDescription?.toString()?.takeIf(String::isNotBlank) else null
        if (ownText != null) {
            if (output.length + ownText.length > MAX_RESPONSE_TEXT_CHARS) {
                throw AutomationException("response_too_large", "La réponse dépasse la limite de lecture.")
            }
            if (output.isNotEmpty()) output.append('\n')
            output.append(ownText)
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                appendNodeText(child, output, textOnly)
            } finally {
                child.recycle()
            }
        }
    }

    companion object {
        private const val TAG = "ClickAccessibility"
        private const val MAX_ACCESSIBILITY_NODES = 2_000
        private const val MAX_RESPONSE_TEXT_CHARS = 500_000

        @Volatile
        var instance: ClickAccessibilityService? = null
            private set
    }
}

class AutomationException(val errorCode: String, override val message: String) : Exception(message)
