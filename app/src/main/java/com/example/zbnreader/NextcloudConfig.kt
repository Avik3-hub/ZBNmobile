package com.example.zbnreader

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class NextcloudSettings(
    val baseUrl: String,
    val username: String,
    val appPassword: String,
    val aircraftType: String
) {
    val isComplete: Boolean
        get() = baseUrl.isNotBlank() && username.isNotBlank() && appPassword.isNotBlank()
}

object NextcloudConfig {
    const val DEFAULT_BASE_URL = "https://81.89.69.171/nextcloud"
    val AIRCRAFT_TYPES = arrayOf("Ми-8 АМТ", "Ми-8 Т")

    private const val PREFS = "NextcloudSettings"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD = "password_encrypted"
    private const val KEY_PASSWORD_IV = "password_iv"
    private const val KEY_AIRCRAFT_TYPE = "aircraft_type"
    private const val KEYSTORE_ALIAS = "zbn_nextcloud_credentials"

    fun load(context: Context): NextcloudSettings {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return NextcloudSettings(
            baseUrl = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL).orEmpty(),
            username = prefs.getString(KEY_USERNAME, "").orEmpty(),
            appPassword = decryptPassword(
                prefs.getString(KEY_PASSWORD, null),
                prefs.getString(KEY_PASSWORD_IV, null)
            ),
            aircraftType = prefs.getString(KEY_AIRCRAFT_TYPE, AIRCRAFT_TYPES.first()).orEmpty()
        )
    }

    fun save(
        context: Context,
        baseUrl: String,
        username: String,
        newPassword: String?,
        aircraftType: String
    ) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
            .putString(KEY_BASE_URL, normalizeBaseUrl(baseUrl))
            .putString(KEY_USERNAME, username.trim())
            .putString(KEY_AIRCRAFT_TYPE, aircraftType)

        if (!newPassword.isNullOrBlank()) {
            val encrypted = encryptPassword(newPassword)
            editor
                .putString(KEY_PASSWORD, encrypted.first)
                .putString(KEY_PASSWORD_IV, encrypted.second)
        }
        editor.apply()
    }

    fun browserUrl(context: Context): String =
        load(context).baseUrl.trimEnd('/') + "/index.php/apps/files/files"

    private fun normalizeBaseUrl(value: String): String =
        value.trim().ifEmpty { DEFAULT_BASE_URL }.trimEnd('/')

    private fun encryptPassword(password: String): Pair<String, String> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return Base64.encodeToString(cipher.doFinal(password.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP) to
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
    }

    private fun decryptPassword(encrypted: String?, iv: String?): String {
        if (encrypted.isNullOrBlank() || iv.isNullOrBlank()) return ""
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
            )
            String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }
}
