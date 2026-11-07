package com.ialocalbridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.ialocalbridge.databinding.ActivityMainBinding
import com.ialocalbridge.models.ProviderConfigParser
import com.ialocalbridge.utils.NetworkHelper
import com.ialocalbridge.utils.PermissionHelper
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var providerRepository: ProviderRepository
    private lateinit var apiKeyStore: ApiKeyStore
    private var showingApiKey = false

    private val openProviderFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importProvider(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        providerRepository = ProviderRepository(this)
        apiKeyStore = ApiKeyStore(this)
        setupActions()
        refreshProviderList()
        showApiKey(apiKeyStore.getOrCreate())
        checkPermissions()
    }

    override fun onResume() {
        super.onResume()
        refreshProviderList()
        checkPermissions()
    }

    private fun setupActions() {
        binding.btnGrantPermissions.setOnClickListener {
            if (!PermissionHelper.hasOverlayPermission(this)) {
                PermissionHelper.requestOverlayPermission(this)
            } else {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        binding.btnImportProvider.setOnClickListener {
            openProviderFile.launch(arrayOf("application/json", "text/json", "text/plain"))
        }
        binding.btnDeleteProvider.setOnClickListener { confirmDeleteProvider() }
        binding.spinnerProvider.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                val name = parent?.getItemAtPosition(position)?.toString() ?: return
                if (providerRepository.get(name) != null) {
                    providerRepository.setActive(name)
                    getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE).edit()
                        .putString(ACTIVE_PROVIDER_KEY, name)
                        .apply()
                    updateServerAddress()
                }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        binding.btnStartServer.setOnClickListener { startBridge() }
        binding.btnStopServer.setOnClickListener {
            stopService(Intent(this, FloatingWindowService::class.java))
        }
        binding.btnCopyApiKey.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("API Bridge key", binding.edtApiKey.text))
            Toast.makeText(this, "Clé API copiée", Toast.LENGTH_SHORT).show()
        }
        binding.btnToggleApiKey.setOnClickListener {
            showingApiKey = !showingApiKey
            binding.edtApiKey.transformationMethod =
                if (showingApiKey) null else PasswordTransformationMethod.getInstance()
            binding.edtApiKey.setSelection(binding.edtApiKey.text.length)
            binding.btnToggleApiKey.text = if (showingApiKey) "Masquer" else "Afficher"
        }
        binding.btnRotateApiKey.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Renouveler la clé API ?")
                .setMessage("Les clients utilisant l’ancienne clé devront être reconfigurés.")
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Renouveler") { _, _ -> showApiKey(apiKeyStore.rotate()) }
                .show()
        }
    }

    private fun importProvider(uri: Uri) {
        try {
            val json = readProviderFile(uri)
            val config = ProviderConfigParser.parse(json)
            val nameInput = EditText(this).apply {
                setText(config.provider)
                hint = "Nom du provider"
                setSingleLine(true)
            }
            AlertDialog.Builder(this)
                .setTitle("Nommer le provider")
                .setMessage("Le nom sera aussi l’identifiant du modèle dans l’API.")
                .setView(nameInput)
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Enregistrer") { _, _ ->
                    try {
                        val namedConfig = config.copy(provider = nameInput.text.toString().trim())
                        ProviderConfigParser.validate(namedConfig)
                        providerRepository.save(namedConfig)
                        getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE).edit()
                            .putString(ACTIVE_PROVIDER_KEY, namedConfig.provider)
                            .apply()
                        refreshProviderList()
                        Toast.makeText(this, "Provider « ${namedConfig.provider} » importé.", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(this, e.message ?: "Nom de provider invalide.", Toast.LENGTH_LONG).show()
                    }
                }
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "Le fichier provider est invalide.", Toast.LENGTH_LONG).show()
        }
    }

    private fun readProviderFile(uri: Uri): String {
        val input = contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Le fichier sélectionné ne peut pas être lu.")
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
        AlertDialog.Builder(this)
            .setTitle("Supprimer le provider")
            .setMessage("Supprimer « $name » et sa configuration ?")
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Supprimer") { _, _ ->
                providerRepository.delete(name)
                refreshProviderList()
                Toast.makeText(this, "Provider supprimé.", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun refreshProviderList() {
        val providers = providerRepository.getAll().map { it.provider }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, providers)
        binding.spinnerProvider.adapter = adapter
        val activeName = providerRepository.getActiveName()
        val selectedIndex = providers.indexOfFirst { it.equals(activeName, ignoreCase = true) }
        if (selectedIndex >= 0) binding.spinnerProvider.setSelection(selectedIndex)
        binding.txtProviderEmpty.visibility = if (providers.isEmpty()) View.VISIBLE else View.GONE
        binding.btnDeleteProvider.isEnabled = providers.isNotEmpty()
        binding.btnStartServer.isEnabled = providers.isNotEmpty()
    }

    private fun startBridge() {
        if (!PermissionHelper.hasOverlayPermission(this) || ClickAccessibilityService.instance == null) {
            checkPermissions()
            Toast.makeText(this, "Accorde les autorisations affichées avant de démarrer.", Toast.LENGTH_LONG).show()
            return
        }
        val providerName = binding.spinnerProvider.selectedItem?.toString()
        if (providerName.isNullOrBlank() || providerRepository.get(providerName) == null) {
            Toast.makeText(this, "Importe et sélectionne d’abord un provider JSON.", Toast.LENGTH_LONG).show()
            return
        }
        val port = binding.edtPort.text.toString().toIntOrNull()
        if (port == null || port !in 1..65535) {
            binding.edtPort.error = "Choisis un port compris entre 1 et 65535."
            return
        }
        providerRepository.setActive(providerName)
        getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE).edit()
            .putInt(PORT_KEY, port)
            .putString(ACTIVE_PROVIDER_KEY, providerName)
            .apply()

        val intent = Intent(this, FloatingWindowService::class.java)
            .putExtra("port", port)
            .putExtra("provider", providerName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        updateServerAddress(port)
        Toast.makeText(this, "Démarrage d’API Bridge…", Toast.LENGTH_SHORT).show()
    }

    private fun checkPermissions() {
        val hasOverlay = PermissionHelper.hasOverlayPermission(this)
        val hasAccessibility = ClickAccessibilityService.instance != null
        binding.cardPermissions.visibility = if (hasOverlay && hasAccessibility) View.GONE else View.VISIBLE
        binding.txtPermissionStatus.text = buildString {
            append("Superposition : ${if (hasOverlay) "autorisée" else "à autoriser"}\n")
            append("Accessibilité : ${if (hasAccessibility) "activée" else "à activer"}")
        }
        binding.txtServiceStatus.text = when {
            !hasAccessibility -> "Service d’accessibilité désactivé"
            !hasOverlay -> "Autorisation de superposition requise"
            else -> "Service d’accessibilité prêt"
        }
        binding.txtServiceStatus.setTextColor(
            getColor(if (hasAccessibility && hasOverlay) R.color.status_success else R.color.status_warning)
        )
    }

    private fun updateServerAddress(port: Int? = null) {
        val selectedPort = port ?: getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            .getInt(PORT_KEY, DEFAULT_PORT)
        binding.txtServerAddress.text = "http://${NetworkHelper.getIPAddress()}:$selectedPort"
    }

    private fun showApiKey(key: String) {
        binding.edtApiKey.setText(key)
        binding.edtApiKey.transformationMethod =
            if (showingApiKey) null else PasswordTransformationMethod.getInstance()
    }

    companion object {
        private const val PREFERENCES_NAME = "app_prefs"
        private const val ACTIVE_PROVIDER_KEY = "active_provider"
        private const val PORT_KEY = "server_port"
        private const val DEFAULT_PORT = 8080
        private const val MAX_PROVIDER_BYTES = 512 * 1024
    }
}
