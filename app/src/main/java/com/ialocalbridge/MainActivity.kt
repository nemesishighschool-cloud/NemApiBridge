package com.ialocalbridge

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.provider.Settings
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.ialocalbridge.databinding.ActivityMainBinding
import com.ialocalbridge.models.ProviderConfigParser
import com.ialocalbridge.utils.NetworkHelper
import com.ialocalbridge.utils.PermissionHelper
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var providerRepository: ProviderRepository
    private lateinit var apiKeyStore: ApiKeyStore
    private val handler = Handler(Looper.getMainLooper())
    private var showingApiKey = false
    private var visiblePage = 0
    private val statusRefresh = object : Runnable {
        override fun run() {
            renderStatus()
            handler.postDelayed(this, 1500)
        }
    }
    private var serverStarting = false
    private fun bridgeLocked(): Boolean = serverStarting || FloatingWindowService.runtimeState.running

    private val openProviderFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importProvider(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = getColor(R.color.nem_background)
        window.navigationBarColor = getColor(R.color.nem_background)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        providerRepository = ProviderRepository(this)
        apiKeyStore = ApiKeyStore(this)
        val prefs = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        binding.edtPort.setText(prefs.getInt(PORT_KEY, DEFAULT_PORT).toString())
        binding.switchStreaming.isChecked = getSharedPreferences("server_settings", MODE_PRIVATE)
            .getBoolean("stream_enabled", true)
        setupActions()
        refreshProviderList()
        showApiKey(apiKeyStore.getOrCreate())
        showPage(savedInstanceState?.getInt("page") ?: 0)
        renderStatus()
    }

    override fun onResume() {
        super.onResume()
        FloatingWindowService.setDashboardVisible(true)
        if (::binding.isInitialized && ::providerRepository.isInitialized) {
            refreshProviderList()
            handler.removeCallbacks(statusRefresh)
            handler.post(statusRefresh)
        }
    }

    override fun onPause() {
        handler.removeCallbacks(statusRefresh)
        FloatingWindowService.setDashboardVisible(false)
        if (showingApiKey) {
            showingApiKey = false
            binding.edtApiKey.transformationMethod = PasswordTransformationMethod.getInstance()
            binding.btnToggleApiKey.text = "Afficher"
        }
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("page", visiblePage)
        super.onSaveInstanceState(outState)
    }

    private fun showPage(page: Int) {
        visiblePage = page.coerceIn(0, 2)
        val pages = listOf(binding.pageDashboard, binding.pageProviders, binding.pageSettings)
        val tabs = listOf(binding.navDashboard, binding.navProviders, binding.navSettings)
        pages.forEachIndexed { index, view -> view.visibility = if (index == visiblePage) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { index, button ->
            button.setBackgroundResource(if (index == visiblePage) R.drawable.nem_nav_selected else android.R.color.transparent)
            button.setTextColor(getColor(if (index == visiblePage) R.color.nem_teal else R.color.nem_muted))
        }
    }

    private fun setupActions() {
        binding.navDashboard.setOnClickListener { showPage(0) }
        binding.navProviders.setOnClickListener { showPage(1) }
        binding.navSettings.setOnClickListener { showPage(2) }
        binding.btnManageProviders.setOnClickListener { showPage(1) }
        binding.btnGoSettings.setOnClickListener { showPage(2) }
        binding.btnGrantPermissions.setOnClickListener {
            if (!PermissionHelper.hasOverlayPermission(this)) PermissionHelper.requestOverlayPermission(this)
            else startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.btnImportProvider.setOnClickListener {
            if (bridgeLocked()) { Toast.makeText(this, "Arrêtez le serveur avant d’importer un provider.", Toast.LENGTH_LONG).show(); return@setOnClickListener }
            openProviderFile.launch(arrayOf("application/json", "text/json", "text/plain"))
        }
        binding.btnDeleteProvider.setOnClickListener { confirmDeleteProvider() }
        binding.spinnerProvider.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val name = parent?.getItemAtPosition(position)?.toString() ?: return
                if (providerRepository.get(name) != null) {
                    providerRepository.setActive(name)
                    getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE).edit().putString(ACTIVE_PROVIDER_KEY, name).apply()
                    renderStatus()
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        binding.edtPort.doAfterTextChanged {
            val port = it?.toString()?.toIntOrNull()
            if (!FloatingWindowService.runtimeState.running && port != null && port in 1..65535) {
                getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE).edit().putInt(PORT_KEY, port).apply()
            }
            renderStatus()
        }
        binding.switchStreaming.setOnCheckedChangeListener { _, checked ->
            getSharedPreferences("server_settings", MODE_PRIVATE).edit().putBoolean("stream_enabled", checked).apply()
        }
        binding.switchApiKeyRequired.setOnCheckedChangeListener { _, checked ->
            getSharedPreferences("server_settings", MODE_PRIVATE).edit().putBoolean("api_key_required", checked).apply()
        }
        binding.btnStartServer.setOnClickListener { startBridge() }
        binding.btnStopServer.setOnClickListener {
            stopService(Intent(this, FloatingWindowService::class.java))
            handler.postDelayed({ renderStatus() }, 150)
        }
        binding.btnOpenTarget.setOnClickListener { openTarget() }
        binding.btnCopyUrl.setOnClickListener {
            copyText("NemApiBridge URL", binding.txtHomeUrl.text.toString(), sensitive = false)
            Toast.makeText(this, "URL copiée", Toast.LENGTH_SHORT).show()
        }
        binding.btnCopyApiKey.setOnClickListener {
            copyText("NemApiBridge key", binding.edtApiKey.text.toString(), sensitive = true)
            Toast.makeText(this, "Clé API copiée", Toast.LENGTH_SHORT).show()
        }
        binding.btnToggleApiKey.setOnClickListener {
            showingApiKey = !showingApiKey
            binding.edtApiKey.transformationMethod = if (showingApiKey) null else PasswordTransformationMethod.getInstance()
            binding.edtApiKey.setSelection(binding.edtApiKey.text.length)
            binding.btnToggleApiKey.text = if (showingApiKey) "Masquer" else "Afficher"
        }
        binding.btnRotateApiKey.setOnClickListener {
            AlertDialog.Builder(this).setTitle("Renouveler la clé API ?")
                .setMessage("Les clients utilisant l’ancienne clé devront être reconfigurés.")
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Renouveler") { _, _ -> showApiKey(apiKeyStore.rotate()) }
                .show()
        }
    }

    private fun copyText(label: String, value: String, sensitive: Boolean) {
        val clip = ClipData.newPlainText(label, value)
        if (sensitive && Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
    }

    private fun importProvider(uri: Uri) {
        if (bridgeLocked()) { Toast.makeText(this, "Arrêtez le serveur avant d’importer un provider.", Toast.LENGTH_LONG).show(); return }
        try {
            val config = ProviderConfigParser.parse(readProviderFile(uri))
            val nameInput = EditText(this).apply { setText(config.provider); hint = "Nom du provider"; setSingleLine(true) }
            AlertDialog.Builder(this).setTitle("Nommer le provider")
                .setMessage("Le nom sera aussi l’identifiant du modèle dans l’API. Un seul provider est conservé : enregistrer remplace les providers existants. Exportez vos fichiers JSON avant de continuer si nécessaire.")
                .setView(nameInput).setNegativeButton("Annuler", null)
                .setPositiveButton("Enregistrer") { _, _ ->
                    try {
                        check(!bridgeLocked()) { "Arrêtez le serveur avant de remplacer le provider." }
                        val namedConfig = config.copy(provider = nameInput.text.toString().trim())
                        ProviderConfigParser.validate(namedConfig)
                        providerRepository.replace(namedConfig)
                        providerRepository.setActive(namedConfig.provider)
                        getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE).edit()
                            .putString(ACTIVE_PROVIDER_KEY, namedConfig.provider).apply()
                        refreshProviderList()
                        Toast.makeText(this, "Provider « ${namedConfig.provider} » importé.", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(this, e.message ?: "Nom de provider invalide.", Toast.LENGTH_LONG).show()
                    }
                }.show()
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "Le fichier provider est invalide.", Toast.LENGTH_LONG).show()
        }
    }

    private fun readProviderFile(uri: Uri): String {
        val input = contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("Le fichier sélectionné ne peut pas être lu.")
        val output = ByteArrayOutputStream()
        input.use { stream ->
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_PROVIDER_BYTES) { "Le fichier provider dépasse 512 Ko." }
                output.write(buffer, 0, count)
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun confirmDeleteProvider() {
        val name = binding.spinnerProvider.selectedItem?.toString()
        if (name.isNullOrBlank() || providerRepository.get(name) == null) {
            Toast.makeText(this, "Aucun provider à supprimer.", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this).setTitle("Supprimer le provider")
            .setMessage("Supprimer « $name » et sa configuration ?")
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Supprimer") { _, _ ->
                if (bridgeLocked()) { Toast.makeText(this, "Arrêtez le serveur avant de supprimer le provider.", Toast.LENGTH_LONG).show(); return@setPositiveButton }
                providerRepository.delete(name)
                refreshProviderList()
                Toast.makeText(this, "Provider supprimé.", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun refreshProviderList() {
        val providers = providerRepository.getAll().map { it.provider }
        val adapter = ArrayAdapter(this, R.layout.nem_spinner_item, providers)
        adapter.setDropDownViewResource(R.layout.nem_spinner_dropdown)
        binding.spinnerProvider.adapter = adapter
        val selected = providers.indexOfFirst { it.equals(providerRepository.getActiveName(), ignoreCase = true) }
        if (selected >= 0) binding.spinnerProvider.setSelection(selected)
        binding.txtProviderEmpty.visibility = if (providers.isEmpty()) View.VISIBLE else View.GONE
        binding.txtProviderCount.text = "${providers.size} provider${if (providers.size > 1) "s" else ""} configuré${if (providers.size > 1) "s" else ""}"
        renderStatus()
    }

    private fun startBridge() {
        if (bridgeLocked()) return
        if (!PermissionHelper.hasOverlayPermission(this) || ClickAccessibilityService.instance == null) {
            showPage(2)
            renderStatus()
            Toast.makeText(this, "Accordez les autorisations avant de démarrer.", Toast.LENGTH_LONG).show()
            return
        }
        val name = binding.spinnerProvider.selectedItem?.toString()
        if (name.isNullOrBlank() || providerRepository.get(name) == null) {
            showPage(1)
            Toast.makeText(this, "Importez et sélectionnez un provider JSON.", Toast.LENGTH_LONG).show()
            return
        }
        val port = binding.edtPort.text.toString().toIntOrNull()
        if (port == null || port !in 1..65535) {
            showPage(2)
            binding.edtPort.error = "Choisissez un port entre 1 et 65535."
            return
        }
        providerRepository.setActive(name)
        getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE).edit().putInt(PORT_KEY, port).putString(ACTIVE_PROVIDER_KEY, name).apply()
        val intent = Intent(this, FloatingWindowService::class.java).putExtra("port", port).putExtra("provider", name)
        serverStarting = true
        renderStatus()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            awaitServerThenOpen(0)
        } catch (e: Exception) {
            serverStarting = false
            renderStatus()
            Toast.makeText(this, "Impossible de démarrer le serveur.", Toast.LENGTH_LONG).show()
        }
    }

    private fun awaitServerThenOpen(attempt: Int) {
        handler.postDelayed({
            val state = FloatingWindowService.runtimeState
            if (state.running) {
                serverStarting = false
                renderStatus()
                openTarget()
            } else if (state.error != null || attempt >= 20) {
                serverStarting = false
                renderStatus()
                Toast.makeText(this, state.error ?: "Le serveur n’a pas démarré.", Toast.LENGTH_LONG).show()
            } else awaitServerThenOpen(attempt + 1)
        }, 250)
    }

    private fun openTarget() {
        val name = providerRepository.getActiveName()
        val config = name?.let { providerRepository.get(it) }
        if (config == null) {
            Toast.makeText(this, "Sélectionnez un provider.", Toast.LENGTH_LONG).show()
            return
        }
        val intent = packageManager.getLaunchIntentForPackage(config.packageName)
        if (intent == null) {
            Toast.makeText(this, "Application cible introuvable : ${config.packageName}", Toast.LENGTH_LONG).show()
            return
        }
        try { startActivity(intent) } catch (e: Exception) {
            Toast.makeText(this, "Impossible d’ouvrir l’application cible.", Toast.LENGTH_LONG).show()
        }
    }

    private fun renderStatus() {
        if (!::binding.isInitialized || !::providerRepository.isInitialized) return
        val state = FloatingWindowService.runtimeState
        val hasAccess = ClickAccessibilityService.instance != null
        val hasOverlay = PermissionHelper.hasOverlayPermission(this)
        val name = providerRepository.getActiveName()
        val config = name?.let { providerRepository.get(it) }
        // The web/API configuration can change while the Android controls are locked.
        if (state.running && name != null) {
            val adapter = binding.spinnerProvider.adapter
            val index = (0 until (adapter?.count ?: 0)).firstOrNull {
                adapter?.getItem(it)?.toString() == name
            }
            if (index != null && binding.spinnerProvider.selectedItemPosition != index) {
                binding.spinnerProvider.setSelection(index)
            }
        }
        val savedStreaming = getSharedPreferences("server_settings", MODE_PRIVATE)
            .getBoolean("stream_enabled", true)
        if (binding.switchStreaming.isChecked != savedStreaming) {
            binding.switchStreaming.isChecked = savedStreaming
        }
        val keyRequired = getSharedPreferences("server_settings", MODE_PRIVATE)
            .getBoolean("api_key_required", false)
        if (binding.switchApiKeyRequired.isChecked != keyRequired) {
            binding.switchApiKeyRequired.isChecked = keyRequired
        }
        binding.txtHeaderStatus.text = if (state.running) "●  EN LIGNE" else "●  ARRÊTÉ"
        binding.txtHomeBadge.text = if (state.running) "EN LIGNE" else if (state.error != null) "ERREUR" else "HORS LIGNE"
        binding.txtHomeServer.text = if (state.running) "Passerelle active" else if (state.error != null) "Démarrage impossible" else "Serveur arrêté"
        binding.txtHomeDetail.text = if (state.running) "Le panneau flottant est disponible sur l’application cible." else state.error ?: "Démarrez la passerelle pour ouvrir le panneau flottant."
        binding.txtActiveProvider.text = name ?: "Aucun provider"
        binding.txtProviderPackageHome.text = config?.let { "Application · ${it.packageName}" } ?: "Importez une configuration JSON."
        binding.txtProviderPackage.text = config?.let { "Application : ${it.packageName}\nVersion du profil : ${it.version}" } ?: "Aucune application configurée."
        binding.txtServiceStatus.text = if (hasAccess) "●  Accessibilité activée" else "●  Accessibilité à activer"
        binding.txtServiceStatus.setTextColor(getColor(if (hasAccess) R.color.status_success else R.color.status_warning))
        binding.txtOverlayStatus.text = if (hasOverlay) "●  Panneau flottant autorisé" else "●  Panneau flottant à autoriser"
        binding.txtPermissionStatus.text = "Accessibilité : ${if (hasAccess) "activée" else "à activer"}\nSuperposition : ${if (hasOverlay) "autorisée" else "à autoriser"}"
        binding.btnGrantPermissions.text = if (hasAccess && hasOverlay) "Ouvrir les réglages d’accessibilité" else "Configurer les autorisations"
        val port = if (state.running) state.port else binding.edtPort.text.toString().toIntOrNull()
        val validPort = port != null && port in 1..65535
        val wifiIp = NetworkHelper.getWifiIPAddress(this)
        val wifiUrl = if (validPort && wifiIp != null) "http://$wifiIp:$port/v1" else "Wi-Fi local indisponible"
        val usbUrl = if (validPort) "http://127.0.0.1:$port/v1" else "Port invalide"
        binding.txtHomeUrl.text = if (wifiIp != null && validPort) wifiUrl else usbUrl
        binding.txtServerAddress.text = wifiUrl
        binding.txtUsbAddress.text = usbUrl
        binding.txtWebAddress.text = if (!validPort) "Web : port invalide" else if (wifiIp != null)
            "Web Wi-Fi : http://$wifiIp:$port/\nWeb USB : http://127.0.0.1:$port/"
            else "Web USB : http://127.0.0.1:$port/"
        binding.txtAdbCommand.text = if (validPort) "Sur le PC : adb forward tcp:$port tcp:$port" else "Choisissez un port valide."
        binding.btnCopyUrl.isEnabled = validPort
        val locked = bridgeLocked()
        binding.edtPort.isEnabled = !locked
        binding.switchStreaming.isEnabled = !locked
        binding.spinnerProvider.isEnabled = !locked
        binding.btnImportProvider.isEnabled = !locked
        binding.btnDeleteProvider.isEnabled = !locked && config != null
        binding.btnStartServer.isEnabled = !locked && config != null
        binding.btnStopServer.isEnabled = state.running
        binding.btnOpenTarget.isEnabled = config != null
        listOf(binding.btnStartServer, binding.btnStopServer, binding.btnOpenTarget,
            binding.btnImportProvider, binding.btnDeleteProvider).forEach { it.alpha = if (it.isEnabled) 1f else 0.4f }
        binding.txtRunningLockHint.text = if (state.running) "Arrêtez le serveur pour modifier le port ou le streaming." else "Modifiable quand le serveur est arrêté."
    }

    private fun showApiKey(key: String) {
        binding.edtApiKey.setText(key)
        binding.edtApiKey.transformationMethod = if (showingApiKey) null else PasswordTransformationMethod.getInstance()
    }

    companion object {
        private const val PREFERENCES_NAME = "app_prefs"
        private const val ACTIVE_PROVIDER_KEY = "active_provider"
        private const val PORT_KEY = "server_port"
        private const val DEFAULT_PORT = 8090
        private const val MAX_PROVIDER_BYTES = 512 * 1024
    }
}
