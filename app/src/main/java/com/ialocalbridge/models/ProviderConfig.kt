package com.ialocalbridge.models

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.annotations.SerializedName

data class ProviderConfig(
    val provider: String = "",
    val version: String = "1.0",
    @SerializedName("package_name")
    val packageName: String = "",
    val selectors: ProviderSelectors = ProviderSelectors(),
    val timeouts: ProviderTimeouts = ProviderTimeouts(),
    @SerializedName("max_history_chars")
    val maxHistoryChars: Int = 8000
)

data class ProviderSelectors(
    val input: List<UiSelector> = emptyList(),
    @SerializedName("input_activation")
    val inputActivation: List<UiSelector> = emptyList(),
    @SerializedName("copy_response")
    val copyResponse: List<UiSelector> = emptyList(),
    @SerializedName("allow_input_gesture")
    val allowInputGesture: Boolean = false,
    @SerializedName("allow_reconstruction_gesture")
    val allowReconstructionGesture: Boolean = false,
    @SerializedName("detect_identical_responses")
    val detectIdenticalResponses: Boolean = false,
    @SerializedName("image_attach") val imageAttach: List<UiSelector> = emptyList(),
    @SerializedName("image_document") val imageDocument: List<UiSelector> = emptyList(),
    @SerializedName("image_delete") val imageDelete: List<UiSelector> = emptyList(),
    val send: List<UiSelector> = emptyList(),
    @SerializedName("assistant_message")
    val assistantMessage: List<UiSelector> = emptyList(),
    val generating: List<UiSelector> = emptyList(),
    val stop: List<UiSelector> = emptyList(),
    @SerializedName("scroll_to_end")
    val scrollToEnd: List<UiSelector> = emptyList()
)

data class UiSelector(
    val by: String = "",
    val value: String = "",
    val match: String = "exact",
    @SerializedName("ancestor_levels")
    val ancestorLevels: Int = 0,
    @SerializedName("text_only")
    val textOnly: Boolean = false,
    @SerializedName("reject_clipped")
    val rejectClipped: Boolean = false,
    @SerializedName("reconstruct_clipped")
    val reconstructClipped: Boolean = false
)

data class ProviderTimeouts(
    @SerializedName("element_ms")
    val elementMs: Long = 10_000,
    @SerializedName("response_ms")
    val responseMs: Long = 180_000,
    @SerializedName("poll_interval_ms")
    val pollIntervalMs: Long = 750,
    @SerializedName("stable_polls")
    val stablePolls: Int = 3
)

object ProviderConfigParser {
    private val gson = Gson()
    private val packagePattern = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")
    private val selectorTypes = setOf("resource_id", "content_description", "text", "class_name", "scrollable")

    fun parse(json: String): ProviderConfig {
        require(json.length <= MAX_CONFIG_CHARS) { "Le fichier de configuration dépasse 512 Ko." }

        val config = try {
            gson.fromJson(json, ProviderConfig::class.java)
        } catch (e: JsonParseException) {
            throw IllegalArgumentException("Le fichier JSON est invalide.", e)
        } ?: throw IllegalArgumentException("Le fichier JSON est vide.")

        validate(config)
        return config
    }

    fun validate(config: ProviderConfig) {
        val provider = requireNotNull(config.provider) { "Le nom du provider est requis." }
        val version = requireNotNull(config.version) { "La version du provider est requise." }
        val packageName = requireNotNull(config.packageName) { "package_name est requis." }
        val selectors = requireNotNull(config.selectors) { "La section selectors est requise." }
        val timeouts = requireNotNull(config.timeouts) { "La section timeouts est invalide." }

        require(provider.isNotBlank() && provider.length <= 64) {
            "Le nom du provider doit contenir entre 1 et 64 caractères."
        }
        require(provider == provider.trim() && provider.all {
            it.isLetterOrDigit() || it == ' ' || it == '.' || it == '_' || it == '-'
        }) {
            "Le nom du provider ne peut contenir que des lettres, chiffres, espaces, points, tirets et underscores."
        }
        require(version.isNotBlank() && version.length <= 32) {
            "La version du provider est invalide."
        }
        require(packagePattern.matches(packageName)) {
            "package_name doit être un identifiant Android complet, par exemple com.example.ai."
        }
        require(config.maxHistoryChars in 1..100_000) {
            "max_history_chars doit être compris entre 1 et 100000."
        }
        require(timeouts.elementMs in 500..60_000) {
            "timeouts.element_ms doit être compris entre 500 et 60000."
        }
        require(timeouts.responseMs in 1_000..10_800_000) {
            "timeouts.response_ms doit être compris entre 1000 et 10800000."
        }
        require(timeouts.pollIntervalMs in 250..5_000) {
            "timeouts.poll_interval_ms doit être compris entre 250 et 5000."
        }
        require(timeouts.stablePolls in 2..10) {
            "timeouts.stable_polls doit être compris entre 2 et 10."
        }
        val input = requireNotNull(selectors.input) { "La liste selectors.input est invalide." }
        val activation = requireNotNull(selectors.inputActivation) { "La liste selectors.input_activation est invalide." }
        val send = requireNotNull(selectors.send) { "La liste selectors.send est invalide." }
        val assistantMessages = requireNotNull(selectors.assistantMessage) {
            "La liste selectors.assistant_message est invalide."
        }
        val copyResponse = requireNotNull(selectors.copyResponse) { "La liste selectors.copy_response est invalide." }
        val generating = requireNotNull(selectors.generating) { "La liste selectors.generating est invalide." }
        val stop = requireNotNull(selectors.stop) { "La liste selectors.stop est invalide." }
        require(!selectors.allowInputGesture || activation.isNotEmpty()) { "allow_input_gesture nécessite input_activation." }
        require(input.isNotEmpty()) { "Le provider doit définir au moins un sélecteur input." }
        require(send.isNotEmpty()) { "Le provider doit définir au moins un sélecteur send." }
        require(assistantMessages.isNotEmpty()) {
            "Le provider doit définir au moins un sélecteur assistant_message."
        }

        val scrollToEnd = requireNotNull(selectors.scrollToEnd) { "La liste selectors.scroll_to_end est invalide." }
        val safeAssistantMessages = assistantMessages.map { requireNotNull(it) { "Un sélecteur est null." } }
        require(!selectors.allowReconstructionGesture || safeAssistantMessages.any { it.reconstructClipped }) { "allow_reconstruction_gesture nécessite reconstruct_clipped." }
        require(safeAssistantMessages.none { it.reconstructClipped && (!it.rejectClipped || !it.textOnly || scrollToEnd.isEmpty()) }) {
            "reconstruct_clipped nécessite reject_clipped, text_only et scroll_to_end."
        }
        val imageAttach = requireNotNull(selectors.imageAttach) { "selectors.image_attach invalide." }
        val imageDocument = requireNotNull(selectors.imageDocument) { "selectors.image_document invalide." }
        val imageDelete = requireNotNull(selectors.imageDelete) { "selectors.image_delete invalide." }
        require(listOf(imageAttach, imageDocument, imageDelete).all { it.isEmpty() } || listOf(imageAttach, imageDocument, imageDelete).all { it.isNotEmpty() }) { "Les trois sélecteurs d’image doivent être définis ensemble." }
        val allSelectors = input + activation + send + assistantMessages + copyResponse + generating + stop + scrollToEnd + imageAttach + imageDocument + imageDelete
        allSelectors.forEach { selector ->
            val nonNullSelector = requireNotNull(selector) { "Un sélecteur est null." }
            val by = requireNotNull(nonNullSelector.by) { "Le champ by d’un sélecteur est requis." }
            val value = requireNotNull(nonNullSelector.value) { "Le champ value d’un sélecteur est requis." }
            val match = requireNotNull(nonNullSelector.match) { "Le champ match d’un sélecteur est invalide." }
            require(by in selectorTypes) {
                "Type de sélecteur inconnu '$by'. Valeurs autorisées : ${selectorTypes.joinToString()}."
            }
            require(value.isNotBlank() && value.length <= MAX_SELECTOR_CHARS) {
                "La valeur d’un sélecteur est vide ou trop longue."
            }
            require(by != "scrollable" || value in setOf("true", "false")) { "Un sélecteur scrollable doit valoir true ou false." }
            require(nonNullSelector.ancestorLevels in 0..8) { "ancestor_levels doit être compris entre 0 et 8." }
            require(match in setOf("exact", "contains", "starts_with")) {
                "Le mode de correspondance '$match' n’est pas pris en charge."
            }
        }
    }

    private const val MAX_CONFIG_CHARS = 512 * 1024
    private const val MAX_SELECTOR_CHARS = 512
}
