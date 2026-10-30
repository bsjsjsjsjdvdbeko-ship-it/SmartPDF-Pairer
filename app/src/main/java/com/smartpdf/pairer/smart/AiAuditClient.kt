package com.smartpdf.pairer.smart

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** OpenAI-compatible semantic translation auditor. API credentials are encrypted with Android Keystore. */
object AiAuditClient {
    data class Settings(val baseUrl: String, val model: String, val apiKey: String)
    data class Audit(val status: String, val confidence: Double, val correctedArabic: String, val explanation: String)

    private const val PREFS = "smartpdf_ai_audit"
    private const val KEY_ALIAS = "smartpdf_ai_audit_key_v1"

    fun loadSettings(context: Context): Settings {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Settings(
            prefs.getString("base_url", "") ?: "",
            prefs.getString("model", "") ?: "",
            decrypt(prefs.getString("api_key_enc", null))
        )
    }

    fun saveSettings(context: Context, baseUrl: String, model: String, apiKey: String) {
        require(baseUrl.startsWith("https://")) {
            "يجب استخدام عنوان HTTPS لحماية مفتاح API أثناء الإرسال."
        }
        require(model.isNotBlank()) { "اسم النموذج مطلوب." }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit().putString("base_url", baseUrl.trim().trimEnd('/'))
            .putString("model", model.trim())
        if (apiKey.isNotBlank()) editor.putString("api_key_enc", encrypt(apiKey.trim()))
        editor.apply()
    }

    fun isConfigured(context: Context): Boolean {
        val s = loadSettings(context)
        return s.baseUrl.isNotBlank() && s.model.isNotBlank() && s.apiKey.isNotBlank()
    }

    fun audit(context: Context, sourceEnglish: String, targetArabic: String): Audit? {
        val settings = loadSettings(context)
        if (settings.baseUrl.isBlank() || settings.model.isBlank() || settings.apiKey.isBlank()) return null
        val prompt = """
You are a careful English-to-Arabic translation quality reviewer.
Compare the complete English source with the Arabic translation. Judge meaning, omissions, mistranslations and factual accuracy, not word-for-word similarity. Preserve technical terms and numbers. Do not penalize valid paraphrasing.
Return ONLY one JSON object with keys: status (good, incomplete, inaccurate), confidence (0 to 1), corrected_ar (a complete corrected Arabic translation; empty only when status=good), explanation (short Arabic reason).
Use status=good when the Arabic translation fully conveys the source meaning. Use incomplete for omitted meaning and inaccurate for materially wrong meaning. Do not invent information.

SOURCE ENGLISH:\n$sourceEnglish

CURRENT ARABIC TRANSLATION:\n$targetArabic
""".trimIndent()
        val request = JSONObject().apply {
            put("model", settings.model)
            put("temperature", 0.1)
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        }
        val endpoint = settings.baseUrl.trimEnd('/').let {
            if (it.endsWith("/chat/completions")) it else "$it/chat/completions"
        }
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20000
            readTimeout = 45000
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        return try {
            connection.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            check(code in 200..299) { "AI API HTTP $code: ${body.take(400)}" }
            val content = JSONObject(body).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content").trim()
            val jsonText = content.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val parsed = JSONObject(jsonText)
            val status = parsed.optString("status", "").lowercase()
            if (status !in setOf("good", "incomplete", "inaccurate")) return null
            Audit(status, parsed.optDouble("confidence", 0.0).coerceIn(0.0, 1.0),
                parsed.optString("corrected_ar", "").trim(), parsed.optString("explanation", "").trim())
        } catch (e: Exception) {
            throw IllegalStateException("تعذر فحص جودة الترجمة بالذكاء الاصطناعي: ${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            connection.disconnect()
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun secretKey(): SecretKey {
        val store = keyStore()
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String?): String {
        if (encoded.isNullOrBlank()) return ""
        return try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, 12)
            val payload = bytes.copyOfRange(12, bytes.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(payload), Charsets.UTF_8)
        } catch (_: Exception) { "" }
    }
}
