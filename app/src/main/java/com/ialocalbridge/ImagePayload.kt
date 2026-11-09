package com.ialocalbridge

import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** Conservative multimodal parsing: data-URL images from explicit user history, reattached in order. */
object ImagePayload {
    data class Payload(val bytes: ByteArray, val mime: String)
    data class Parsed(val messages: JSONArray, val images: List<Payload>)
    fun parse(messages: JSONArray): Parsed {
        var lastUser = -1
        for (i in 0 until messages.length()) if (messages.optJSONObject(i)?.optString("role")?.equals("user", true) == true) lastUser = i
        val out = JSONArray(); val images = mutableListOf<Payload>()
        for (i in 0 until messages.length()) {
            val original = messages.optJSONObject(i) ?: throw AutomationException("invalid_request", "Message JSON invalide.")
            val copy = JSONObject(original.toString()); val parts = copy.optJSONArray("content")
            if (parts != null) {
                val texts = mutableListOf<String>()
                for (j in 0 until parts.length()) {
                    val part = parts.optJSONObject(j) ?: throw AutomationException("unsupported_content", "Contenu multimodal invalide.")
                    when (part.optString("type")) {
                        "text", "input_text" -> texts.add(part.optString("text"))
                        "image_url", "input_image" -> {
                            if (!copy.optString("role").equals("user", true)) throw AutomationException("unsupported_content", "Les images ne sont acceptées que dans les messages utilisateur.")
                            if (images.size >= 8) throw AutomationException("unsupported_content", "Huit images maximum dans l’historique fourni.")
                            val v = part.opt("image_url")
                            val detail = if (v is JSONObject) v.optString("detail", "auto") else part.optString("detail", "auto")
                            if (detail != "auto") throw AutomationException("unsupported_parameter", "Le réglage detail des images n’est pas pris en charge.")
                            val url = if (v is JSONObject) v.optString("url") else (v as? String ?: "")
                            images.add(decode(url))
                            texts.add("[Image ${images.size} attached]")
                        }
                        else -> throw AutomationException("unsupported_content", "Type de contenu multimodal non pris en charge.")
                    }
                }
                copy.put("content", texts.joinToString("\n").ifBlank { if (i == lastUser && images.isNotEmpty()) "[Image attached]" else "" })
            }
            out.put(copy)
        }
        return Parsed(out, images)
    }
    private fun decode(url: String): Payload {
        val match = Regex("^data:(image/(?:png|jpeg|webp));base64,([A-Za-z0-9+/=\\r\\n]+)$", RegexOption.IGNORE_CASE).matchEntire(url)
            ?: throw AutomationException("unsupported_content", "L’image doit être une data URL base64 PNG, JPEG ou WebP ; les URL externes ne sont pas téléchargées.")
        val mime = match.groupValues[1].lowercase()
        val encoded = match.groupValues[2]
        if (encoded.length > 5_592_408) throw AutomationException("request_too_large", "Image supérieure à 4 Mo.")
        val bytes = try { Base64.decode(encoded, Base64.DEFAULT) } catch (_: IllegalArgumentException) { throw AutomationException("invalid_request", "Image base64 invalide.") }
        if (bytes.isEmpty() || bytes.size > 4 * 1024 * 1024) throw AutomationException("request_too_large", "Image vide ou supérieure à 4 Mo.")
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outMimeType != mime || options.outWidth !in 1..8192 || options.outHeight !in 1..8192)
            throw AutomationException("invalid_request", "Image invalide, type incorrect ou dimensions supérieures à 8192 pixels.")
        return Payload(bytes, mime)
    }
}
