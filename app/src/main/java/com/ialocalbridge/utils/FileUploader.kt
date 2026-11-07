package com.ialocalbridge.utils

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class FileUploader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    private val TAG = "FileUploader"

    data class UploadResult(
        val success: Boolean,
        val url: String? = null,
        val errorLog: String = ""
    )

    fun uploadWithFallback(file: File, filename: String): UploadResult {
        val errors = StringBuilder()
        Log.d(TAG, "Starting configured upload fallback chain")
        
        // 1. Catbox
        try {
            val url = uploadToCatbox(file, filename)
            if (url != null) return UploadResult(true, url)
            else errors.append("1. Catbox: Réponse invalide\n")
        } catch (e: Exception) { errors.append("1. Catbox: ${e.message}\n") }

        // 2. Tmp.ninja
        try {
            val url = uploadToTmpNinja(file, filename)
            if (url != null) return UploadResult(true, url)
            else errors.append("2. Tmp.ninja: Réponse invalide\n")
        } catch (e: Exception) { errors.append("2. Tmp.ninja: ${e.message}\n") }

        // 3. 0x0.st
        try {
            val url = uploadTo0x0(file, filename)
            if (url != null) return UploadResult(true, url)
            else errors.append("3. 0x0.st: Échec\n")
        } catch (e: Exception) { errors.append("3. 0x0.st: ${e.message}\n") }

        // 4. Pomf.cat
        try {
            val url = uploadToPomf(file, filename)
            if (url != null) return UploadResult(true, url)
            else errors.append("4. Pomf.cat: Échec\n")
        } catch (e: Exception) { errors.append("4. Pomf.cat: ${e.message}\n") }

        // 5. File.io
        try {
            val url = uploadToFileIo(file, filename)
            if (url != null) return UploadResult(true, url)
            else errors.append("5. File.io: Échec\n")
        } catch (e: Exception) { errors.append("5. File.io: ${e.message}\n") }

        // 6. GoFile (Hébergeur final)
        try {
            val url = uploadToGoFile(file, filename)
            if (url != null) return UploadResult(true, url)
            else errors.append("6. GoFile: Échec\n")
        } catch (e: Exception) { errors.append("6. GoFile: ${e.message}\n") }

        return UploadResult(false, errorLog = errors.toString())
    }

    private fun uploadToCatbox(file: File, filename: String): String? {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("reqtype", "fileupload")
            .addFormDataPart("fileToUpload", filename, file.asRequestBody("application/octet-stream".toMediaTypeOrNull()))
            .build()
        val request = Request.Builder().url("https://catbox.moe/user/api.php").post(body).header("User-Agent", "Mozilla/5.0").build()
        client.newCall(request).execute().use { resp ->
            val out = resp.body?.string()?.trim()
            return if (resp.isSuccessful && out?.startsWith("http") == true) out else null
        }
    }

    private fun uploadToTmpNinja(file: File, filename: String): String? {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", filename, file.asRequestBody("application/octet-stream".toMediaTypeOrNull()))
            .build()
        val request = Request.Builder().url("https://tmp.ninja/api.php?d=upload").post(body).build()
        client.newCall(request).execute().use { resp ->
            val out = resp.body?.string()?.trim()
            return if (resp.isSuccessful && out?.startsWith("http") == true) out else null
        }
    }

    private fun uploadTo0x0(file: File, filename: String): String? {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", filename, file.asRequestBody("application/octet-stream".toMediaTypeOrNull()))
            .build()
        val request = Request.Builder().url("https://0x0.st").post(body).build()
        client.newCall(request).execute().use { resp ->
            val out = resp.body?.string()?.trim()
            return if (resp.isSuccessful && out?.startsWith("http") == true) out else null
        }
    }

    private fun uploadToPomf(file: File, filename: String): String? {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("files[]", filename, file.asRequestBody("application/octet-stream".toMediaTypeOrNull()))
            .build()
        val request = Request.Builder().url("https://pomf.cat/upload.php").post(body).build()
        client.newCall(request).execute().use { resp ->
            val out = resp.body?.string()
            if (resp.isSuccessful && out != null) {
                val json = JSONObject(out)
                if (json.optBoolean("success")) {
                    val fileObj = json.getJSONArray("files").getJSONObject(0)
                    return "https://a.pomf.cat/" + fileObj.getString("url")
                }
            }
            return null
        }
    }

    private fun uploadToFileIo(file: File, filename: String): String? {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", filename, file.asRequestBody("application/octet-stream".toMediaTypeOrNull()))
            .build()
        val request = Request.Builder().url("https://file.io").post(body).build()
        client.newCall(request).execute().use { resp ->
            val out = resp.body?.string()
            if (resp.isSuccessful && out != null) {
                val json = JSONObject(out)
                return if (json.optBoolean("success")) json.optString("link") else null
            }
            return null
        }
    }

    private fun uploadToGoFile(file: File, filename: String): String? {
        // GoFile nécessite de trouver un serveur d'abord
        val serverReq = Request.Builder().url("https://api.gofile.io/getServer").build()
        client.newCall(serverReq).execute().use { resp ->
            val out = resp.body?.string()
            if (resp.isSuccessful && out != null) {
                val server = JSONObject(out).getJSONObject("data").getString("server")
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", filename, file.asRequestBody("application/octet-stream".toMediaTypeOrNull()))
                    .build()
                val upReq = Request.Builder().url("https://$server.gofile.io/uploadFile").post(body).build()
                client.newCall(upReq).execute().use { upResp ->
                    val upOut = upResp.body?.string()
                    if (upResp.isSuccessful && upOut != null) {
                        return JSONObject(upOut).getJSONObject("data").getString("downloadPage")
                    }
                }
            }
            return null
        }
    }
}
