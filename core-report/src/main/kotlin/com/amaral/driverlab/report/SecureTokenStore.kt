package com.amaral.driverlab.report

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Where the GitHub token lives.
 *
 * Encrypted at rest, revocable from the UI, and never written to a log. There is
 * no client secret in the APK: publication uses the Device Flow, which is designed
 * for exactly this situation — a public client that cannot keep a secret.
 */
public class SecureTokenStore(context: Context) {

    private val preferences: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        FILE_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    public fun token(): String? = preferences.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }

    public fun store(token: String) {
        preferences.edit().putString(KEY_TOKEN, token).apply()
    }

    /** Clears the local copy. The user still has to revoke it on GitHub; the UI says so. */
    public fun clear() {
        preferences.edit().remove(KEY_TOKEN).apply()
    }

    public fun hasToken(): Boolean = token() != null

    private companion object {
        const val FILE_NAME = "amaral-driver-lab-credentials"
        const val KEY_TOKEN = "github_token"
    }
}
