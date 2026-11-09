package com.ialocalbridge

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Additional web-managed API keys, encrypted at rest with Android Keystore. */
class WebApiKeyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("nem_web_api_keys", Context.MODE_PRIVATE)
    private fun secret(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    private fun read(): JSONArray {
        val iv = prefs.getString("iv", null) ?: return JSONArray()
        val encrypted = prefs.getString("value", null) ?: throw IllegalStateException("Clés web illisibles")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        return JSONArray(String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8))
    }
    private fun write(keys: JSONArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret())
        val encrypted = cipher.doFinal(keys.toString().toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("value", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit())
    }
    fun list(): List<JSONObject> = synchronized(LOCK) {
        val keys = read()
        (0 until keys.length()).map { keys.getJSONObject(it) }
    }
    fun create(name: String): JSONObject = synchronized(LOCK) {
        require(name.length <= 60 && name.none { it.isISOControl() }) { "Nom de clé invalide." }
        val keys = read()
        require(keys.length() < 50) { "Maximum de 50 clés atteint." }
        val key = JSONObject()
            .put("id", UUID.randomUUID().toString())
            .put("name", name)
            .put("key", "sk-nem-" + UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""))
            .put("created_at", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        keys.put(key)
        write(keys)
        key
    }
    fun delete(id: String): Boolean = synchronized(LOCK) {
        val keys = read()
        val keep = JSONArray()
        var found = false
        for (i in 0 until keys.length()) {
            val item = keys.getJSONObject(i)
            if (item.optString("id") == id) found = true else keep.put(item)
        }
        if (found) write(keep)
        found
    }
    companion object {
        private const val ALIAS = "nem_web_api_keys_v1"
        private val LOCK = Any()
    }
}
