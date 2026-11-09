package com.ialocalbridge

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ialocalbridge.models.ProviderConfig
import com.ialocalbridge.models.ProviderConfigParser

class ProviderRepository(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    @Synchronized
    fun getAll(): List<ProviderConfig> {
        val json = preferences.getString(PROVIDERS_KEY, null) ?: return emptyList()
        val type = object : TypeToken<List<ProviderConfig>>() {}.type
        return try {
            (gson.fromJson<List<ProviderConfig>>(json, type) ?: emptyList())
                .also { configs -> configs.forEach(ProviderConfigParser::validate) }
        } catch (e: Exception) {
            throw IllegalStateException("Les providers enregistrés sont illisibles.", e)
        }
    }

    @Synchronized
    fun get(providerName: String): ProviderConfig? =
        getAll().firstOrNull { it.provider.equals(providerName, ignoreCase = true) }

    /** Replace only on explicit user import; old installations are never migrated destructively. */
    @Synchronized
    fun replace(config: ProviderConfig) {
        ProviderConfigParser.validate(config)
        check(preferences.edit()
            .putString(PROVIDERS_KEY, gson.toJson(listOf(config)))
            .putString(ACTIVE_PROVIDER_KEY, config.provider)
            .commit()) { "Impossible d’enregistrer le provider." }
    }

    @Synchronized
    fun delete(providerName: String) {
        val configs = getAll().filterNot { it.provider.equals(providerName, ignoreCase = true) }
        persist(configs)
        if (preferences.getString(ACTIVE_PROVIDER_KEY, null).equals(providerName, ignoreCase = true)) {
            preferences.edit().remove(ACTIVE_PROVIDER_KEY).apply()
        }
    }

    fun getActiveName(): String? = preferences.getString(ACTIVE_PROVIDER_KEY, null)

    @Synchronized
    fun setActive(providerName: String) {
        require(get(providerName) != null) { "Le provider sélectionné n’existe pas." }
        preferences.edit().putString(ACTIVE_PROVIDER_KEY, providerName).apply()
    }

    private fun persist(configs: List<ProviderConfig>) {
        check(preferences.edit().putString(PROVIDERS_KEY, gson.toJson(configs)).commit()) {
            "Impossible d’enregistrer la configuration des providers."
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "provider_configs"
        private const val PROVIDERS_KEY = "providers"
        private const val ACTIVE_PROVIDER_KEY = "active_provider"
    }
}
