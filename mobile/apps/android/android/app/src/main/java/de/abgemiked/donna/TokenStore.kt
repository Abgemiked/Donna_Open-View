package com.yourcompany.donna

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * DONNA-103: Sicherer Token-Speicher via EncryptedSharedPreferences (Android Keystore).
 *
 * Speichert/liest den ADMIN_TOKEN verschlüsselt auf dem Gerät.
 * Kein Token im Source-Code oder in Logs.
 */
object TokenStore {

    private const val PREFS_FILE = "donna_secure_prefs"
    private const val KEY_TOKEN = "admin_token"

    private fun getPrefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        PREFS_FILE,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun getToken(context: Context): String? =
        getPrefs(context).getString(KEY_TOKEN, null)

    fun saveToken(context: Context, token: String) {
        getPrefs(context).edit().putString(KEY_TOKEN, token).apply()
    }

    fun hasToken(context: Context): Boolean =
        getPrefs(context).contains(KEY_TOKEN)

    fun clearToken(context: Context) {
        getPrefs(context).edit().remove(KEY_TOKEN).apply()
    }
}
