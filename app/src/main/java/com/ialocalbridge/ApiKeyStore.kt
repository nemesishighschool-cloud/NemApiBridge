package com.ialocalbridge

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ApiKeyStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun getOrCreate(): String = get() ?: rotate()

    @Synchronized
    fun get(): String? {
        val encodedIv = preferences.getString(IV_KEY, null) ?: return null
        val encodedValue = preferences.getString(VALUE_KEY, null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getSecretKey(),
                GCMParameterSpec(GCM_TAG_LENGTH, Base64.decode(encodedIv, Base64.NO_WRAP))
            )
            String(cipher.doFinal(Base64.decode(encodedValue, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            throw IllegalStateException("Impossible de déchiffrer la clé API stockée.", e)
        }
    }

    @Synchronized
    fun rotate(): String {
        val key = "sk-bridge-${UUID.randomUUID().toString().replace("-", "")}"
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
            val encryptedValue = cipher.doFinal(key.toByteArray(Charsets.UTF_8))
            val saved = preferences.edit()
                .putString(IV_KEY, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString(VALUE_KEY, Base64.encodeToString(encryptedValue, Base64.NO_WRAP))
                .commit()
            check(saved) { "Impossible d’enregistrer la clé API." }
            return key
        } catch (e: Exception) {
            throw IllegalStateException("Impossible de générer la clé API.", e)
        }
    }

    private fun getSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val PREFERENCES_NAME = "api_security"
        private const val IV_KEY = "api_key_iv"
        private const val VALUE_KEY = "api_key_encrypted"
        private const val KEY_ALIAS = "api_bridge_api_key_encryption"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
    }
}
