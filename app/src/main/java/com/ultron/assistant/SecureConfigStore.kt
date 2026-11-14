package com.ultron.assistant

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the API URL and API key encrypted with a key held by Android Keystore.
 * Existing V2 SharedPreferences values are migrated on first load.
 */
data class UltronApiConfig(
    val baseUrl: String = "",
    val apiKey: String = ""
)

object SecureConfigStore {
    private const val SECURE_PREFS = "ultron_api_secure_config"
    private const val LEGACY_PREFS = "ultron_api_config"
    private const val KEY_ALIAS = "ultron_api_config_aes_gcm_v1"
    private const val URL_FIELD = "base_url_enc"
    private const val API_KEY_FIELD = "api_key_enc"
    private const val IV_LENGTH = 12

    fun load(context: Context): UltronApiConfig {
        val secure = context.getSharedPreferences(SECURE_PREFS, Context.MODE_PRIVATE)
        val encryptedUrl = secure.getString(URL_FIELD, null)
        val encryptedKey = secure.getString(API_KEY_FIELD, null)

        if (!encryptedUrl.isNullOrBlank() || !encryptedKey.isNullOrBlank()) {
            return UltronApiConfig(
                baseUrl = encryptedUrl?.let { runCatching { decrypt(it) }.getOrDefault("") } ?: "",
                apiKey = encryptedKey?.let { runCatching { decrypt(it) }.getOrDefault("") } ?: ""
            )
        }

        // One-time migration from the V2 plaintext private preferences.
        val legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        val oldUrl = legacy.getString("base_url", "") ?: ""
        val oldKey = legacy.getString("api_key", "") ?: ""
        val migrated = UltronApiConfig(oldUrl, oldKey)

        if (oldUrl.isNotBlank() || oldKey.isNotBlank()) {
            save(context, oldUrl, oldKey)
            legacy.edit()
                .remove("base_url")
                .remove("api_key")
                .commit()
        }

        return migrated
    }

    fun save(context: Context, baseUrl: String, apiKey: String) {
        val editor = context.getSharedPreferences(SECURE_PREFS, Context.MODE_PRIVATE).edit()
        if (baseUrl.isBlank()) {
            editor.remove(URL_FIELD)
        } else {
            editor.putString(URL_FIELD, encrypt(baseUrl.trim()))
        }

        if (apiKey.isBlank()) {
            editor.remove(API_KEY_FIELD)
        } else {
            editor.putString(API_KEY_FIELD, encrypt(apiKey.trim()))
        }

        check(editor.commit()) { "Unable to persist encrypted ULTRON configuration." }

        // Remove legacy plaintext values only after the encrypted write succeeds.
        context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove("base_url")
            .remove("api_key")
            .commit()
    }

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + encrypted
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        require(packed.size > IV_LENGTH) { "Encrypted configuration is truncated." }
        val iv = packed.copyOfRange(0, IV_LENGTH)
        val ciphertext = packed.copyOfRange(IV_LENGTH, packed.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
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
}
