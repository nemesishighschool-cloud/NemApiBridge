package com.ialocalbridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
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

    fun readLatestText(packageName: String, selectors: List<UiSelector>, label: String): String {
        val node = findLatestNode(packageName, selectors, label)
        return try {
            val text = StringBuilder()
            appendNodeText(node, text)
            text.toString().trim()
        } finally {
            node.recycle()
        }
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
        if (!node.isEnabled || !node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            throw AutomationException("action_failed", "Impossible d’actionner le contrôle $label.")
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
                val matches = nodes.filter { matches(it, selector) }
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

    private fun matches(node: AccessibilityNodeInfo, selector: UiSelector): Boolean {
        if (!node.isVisibleToUser) return false
        val actual = when (selector.by) {
            "resource_id" -> node.viewIdResourceName
            "content_description" -> node.contentDescription?.toString()
            "text" -> node.text?.toString()
            "class_name" -> node.className?.toString()
            else -> null
        } ?: return false

        return when (selector.match) {
            "exact" -> actual == selector.value
            "contains" -> actual.contains(selector.value, ignoreCase = true)
            "starts_with" -> actual.startsWith(selector.value, ignoreCase = true)
            else -> false
        }
    }

    private fun appendNodeText(node: AccessibilityNodeInfo, output: StringBuilder) {
        val ownText = node.text?.toString()?.takeIf(String::isNotBlank)
            ?: node.contentDescription?.toString()?.takeIf(String::isNotBlank)
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
                appendNodeText(child, output)
            } finally {
                child.recycle()
            }
        }
    }

    companion object {
        private const val TAG = "ClickAccessibility"
        private const val MAX_ACCESSIBILITY_NODES = 2_000
        private const val MAX_RESPONSE_TEXT_CHARS = 100_000

        @Volatile
        var instance: ClickAccessibilityService? = null
            private set
    }
}

class AutomationException(val errorCode: String, override val message: String) : Exception(message)
