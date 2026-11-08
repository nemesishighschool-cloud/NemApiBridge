package com.ialocalbridge

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.ClipData
import android.content.Intent
import android.content.ClipboardManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.ialocalbridge.utils.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

class FloatingWindowService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var floatingView: View
    private var apiServer: LocalApiServer? = null
    private var isServerRunning = false
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var selectedProvider = ""
    private var selectedPort = DEFAULT_PORT

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val preferences = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val requestedPort = intent?.getIntExtra("port", preferences.getInt("server_port", DEFAULT_PORT))
            ?: preferences.getInt("server_port", DEFAULT_PORT)
        val requestedProvider = intent?.getStringExtra("provider")
            ?: preferences.getString("active_provider", "").orEmpty()
        if (isServerRunning && requestedPort != selectedPort) stopServer()
        selectedPort = requestedPort
        selectedProvider = requestedProvider
        super.startForeground(NOTIFICATION_ID, buildNotification("Préparation du serveur local"))
        if (!android.provider.Settings.canDrawOverlays(this)) {
            runtimeState = RuntimeState(error = "Autorisation de superposition manquante.")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!isServerRunning) startServer() else runtimeState = RuntimeState(true, selectedPort, selectedProvider)

        return START_STICKY
    }

    private fun startServer() {
        val repository = ProviderRepository(this)
        // The active profile can also be changed through the authenticated API.
        selectedProvider = repository.getActiveName() ?: selectedProvider
        if (repository.get(selectedProvider) == null) {
            runtimeState = RuntimeState(error = "Provider manquant.")
            updateFloatingState(false, "Provider manquant")
            Toast.makeText(this, "Importe et sélectionne un provider avant de démarrer.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            apiServer?.stop()
            ApiKeyStore(this).getOrCreate()
            apiServer = LocalApiServer(selectedPort, this)
            apiServer?.start()
            isServerRunning = true
            runtimeState = RuntimeState(true, selectedPort, selectedProvider)
            val address = "http://${NetworkHelper.getIPAddress()}:$selectedPort"
            updateFloatingState(true, address)
            updateNotification("NemApiBridge actif sur le port $selectedPort")
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start local server: ${e.javaClass.simpleName}")
            apiServer?.stop()
            apiServer = null
            isServerRunning = false
            runtimeState = RuntimeState(error = "Impossible de démarrer le serveur sur le port $selectedPort.")
            updateFloatingState(false, "Erreur de démarrage")
            Toast.makeText(this, "Impossible de démarrer le serveur : ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("InflateParams")
    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val themedContext = androidx.appcompat.view.ContextThemeWrapper(this, androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
        floatingView = LayoutInflater.from(themedContext).inflate(R.layout.floating_control_window, null)
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 120
        }
        try {
            windowManager.addView(floatingView, params)
            setupControls()
            applyPanelVisibility()
        } catch (e: SecurityException) {
            Log.e(TAG, "Overlay permission is missing", e)
            stopSelf()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupControls() {
        val dragHandle = floatingView.findViewById<View>(R.id.btn_drag)
        val startStop = floatingView.findViewById<Button>(R.id.btn_start_api)
        val stopGeneration = floatingView.findViewById<Button>(R.id.btn_stop_generation)
        val inspect = floatingView.findViewById<Button>(R.id.btn_inspect_ui)
        val close = floatingView.findViewById<ImageButton>(R.id.btn_close)
        val expand = floatingView.findViewById<Button>(R.id.btn_expand)
        val extra = floatingView.findViewById<View>(R.id.extra_actions)
        expand.setOnClickListener {
            val expanded = extra.visibility != View.VISIBLE
            extra.visibility = if (expanded) View.VISIBLE else View.GONE
            expand.contentDescription = if (expanded) "Masquer les outils du panneau" else "Afficher les outils du panneau"
        }

        dragHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (initialTouchX - event.rawX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(floatingView, params)
                    true
                }
                else -> false
            }
        }

        startStop.setOnClickListener {
            if (isServerRunning) stopServer() else startServer()
        }
        stopGeneration.setOnClickListener {
            Thread({
                val message = try {
                    val stopped = runBlocking(Dispatchers.IO) {
                        AutomationCoordinator(this@FloatingWindowService).stopGeneration()
                    }
                    if (stopped) "Arrêt demandé" else "Aucun contrôle d’arrêt disponible."
                } catch (e: Exception) {
                    "Échec de l’arrêt : ${e.message ?: e.javaClass.simpleName}"
                }
                android.os.Handler(mainLooper).post {
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                }
            }, "api-bridge-stop").start()
        }
        inspect.setOnClickListener { inspectTargetUi() }
        close.setOnClickListener { stopSelf() }
    }

    private fun applyPanelVisibility() {
        if (::floatingView.isInitialized) floatingView.visibility = if (dashboardVisible) View.GONE else View.VISIBLE
    }

    private fun inspectTargetUi() {
        val providerName = ProviderRepository(this).getActiveName() ?: selectedProvider
        val config = ProviderRepository(this).get(providerName)
        val service = ClickAccessibilityService.instance
        if (config == null || service == null) {
            Toast.makeText(this, "Sélectionne un provider et active l’accessibilité.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            service.configureTarget(config.packageName)
            val elements = service.inspectAccessibleElements(config.packageName)
            if (elements.isEmpty()) {
                Toast.makeText(this, "Aucun élément accessible détecté dans l’application cible.", Toast.LENGTH_LONG).show()
                return
            }
            val labels = elements.map { element ->
                listOfNotNull(
                    element.resourceId?.let { "ID: $it" },
                    element.contentDescription?.let { "Desc: ${it.take(80)}" },
                    element.text?.let { "Texte: ${it.replace('\n', ' ').take(80)}" },
                    element.className?.let { "Classe: $it" },
                    if (element.editable) "éditable" else null,
                    if (element.clickable) "cliquable" else null
                ).joinToString(" | ")
            }.toTypedArray()

            val dialog = AlertDialog.Builder(this)
                .setTitle("Éléments accessibles")
                .setMessage("Appuie sur un élément pour copier ses sélecteurs JSON.")
                .setItems(labels) { _, index ->
                    val element = elements[index]
                    val selectors = JSONArray()
                    element.resourceId?.let { selectors.put(selector("resource_id", it)) }
                    element.contentDescription?.let { selectors.put(selector("content_description", it)) }
                    element.text?.let { selectors.put(selector("text", it)) }
                    element.className?.let { selectors.put(selector("class_name", it)) }
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("NemApiBridge selectors", selectors.toString(2)))
                    Toast.makeText(this, "Sélecteurs copiés.", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Fermer", null)
                .create()
            dialog.window?.setType(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
            )
            dialog.show()
        } catch (e: AutomationException) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Inspection impossible : ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }

    private fun selector(type: String, value: String): JSONObject =
        JSONObject().put("by", type).put("value", value)

    private fun stopServer() {
        AutomationCoordinator.stopCurrentJob()
        apiServer?.stop()
        apiServer = null
        isServerRunning = false
        runtimeState = RuntimeState()
        updateFloatingState(false, "Serveur arrêté")
        updateNotification("NemApiBridge arrêté")
    }

    private fun updateFloatingState(running: Boolean, status: String) {
        if (!::floatingView.isInitialized) return
        floatingView.findViewById<Button>(R.id.btn_start_api)?.apply {
            text = if (running) "Arrêter" else "Démarrer"
            isEnabled = running || ProviderRepository(this@FloatingWindowService).get(selectedProvider) != null
        }
        floatingView.findViewById<TextView>(R.id.txt_floating_status)?.text = status
    }

    private fun createNotificationChannel(): String {
        val channelId = "api_bridge_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "NemApiBridge", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return channelId
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, createNotificationChannel())
            .setContentTitle("NemApiBridge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        AutomationCoordinator.stopCurrentJob()
        apiServer?.stop()
        apiServer = null
        isServerRunning = false
        runtimeState = RuntimeState()
        if (instance === this) instance = null
        if (::floatingView.isInitialized && floatingView.windowToken != null) {
            windowManager.removeView(floatingView)
        }
        super.onDestroy()
    }

    data class RuntimeState(val running: Boolean = false, val port: Int? = null, val provider: String? = null, val error: String? = null)

    companion object {
        @Volatile private var instance: FloatingWindowService? = null
        @Volatile private var dashboardVisible = false
        fun setDashboardVisible(visible: Boolean) {
            dashboardVisible = visible
            instance?.applyPanelVisibility()
        }

        @Volatile var runtimeState = RuntimeState()
            private set
        private const val TAG = "FloatingWindowService"
        private const val NOTIFICATION_ID = 1
        private const val DEFAULT_PORT = 8090
    }
}
